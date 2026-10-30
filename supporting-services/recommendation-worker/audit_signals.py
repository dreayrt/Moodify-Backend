"""Read-only inventory of existing preference sources; never exports raw IDs or search text."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path

import worker


def aliases(documents):
    result = {}
    ambiguous = set()
    canonical = {str(d["_id"]) for d in documents}
    for d in documents:
        key = str(d["_id"])
        result[key] = key
    for d in documents:
        key, external = str(d["_id"]), d.get("spotify_id")
        if not external or external in canonical or external in ambiguous:
            continue
        if external in result and result[external] != key:
            result.pop(external)
            ambiguous.add(external)
        else:
            result[external] = key
    return result


def inventory(sql, mongo):
    sql.rollback()
    with sql.cursor() as cursor:
        cursor.execute("START TRANSACTION READ ONLY")
        cursor.execute("SHOW TABLES")
        table_names = sorted(next(iter(row.values())) for row in cursor.fetchall())
        tables = {}
        sources = {}
        for table, column, at in (("favorite_songs", "track_id", "created_at"),
                                  ("favorite_artists", "artist_id", "created_at"),
                                  ("favorite_albums", "album_id", "created_at"),
                                  ("user_library_tracks", "track_id", "added_at")):
            cursor.execute(f"SELECT f.user_id,f.`{column}` target_id,f.`{at}` at,u.role,u.status FROM `{table}` f LEFT JOIN users u ON u.id=f.user_id LIMIT 100001")
            rows = cursor.fetchall()
            if len(rows) > 100000:
                raise RuntimeError("Preference inventory exceeds its bounded extraction")
            sources[table] = rows
        cursor.execute("SELECT id,album_id,track_id FROM album_tracks LIMIT 100001")
        album_tracks = cursor.fetchall()
        cursor.execute("SELECT id FROM albums LIMIT 100001")
        sql_albums = {str(row["id"]) for row in cursor.fetchall()}
        cursor.execute("""SELECT s.search_type,s.selected_target_type,s.selected_target_id,s.searched_at,u.role,u.status
            FROM search_history s LEFT JOIN users u ON u.id=s.user_id LIMIT 100001""")
        searches = cursor.fetchall()
        cursor.execute("""SELECT p.target_type,p.target_id,p.referrer_type,p.platform,p.session_id,p.user_id,u.role,u.status
            FROM platform_traffic_events p LEFT JOIN users u ON u.id=p.user_id LIMIT 100001""")
        visits = cursor.fetchall()
        cursor.execute("""SELECT o.track_id,o.status download_status,d.user_id,u.role,u.status
            FROM offline_downloads o LEFT JOIN user_devices d ON d.id=o.device_id
            LEFT JOIN users u ON u.id=d.user_id LIMIT 100001""")
        downloads = cursor.fetchall()
        if any(len(rows) > 100000 for rows in (album_tracks, searches, visits, downloads)):
            raise RuntimeError("Behavior inventory exceeds bounded extraction")
        for table in ("favorite_songs", "favorite_artists", "favorite_albums", "user_library_tracks", "search_history",
                      "platform_traffic_events", "offline_downloads", "albums", "album_tracks", "listening_history", "playback_events"):
            cursor.execute(f"SHOW COLUMNS FROM `{table}`")
            columns = [row["Field"] for row in cursor.fetchall()]
            cursor.execute(f"SELECT COUNT(*) n FROM `{table}`")
            tables[table] = {"rows": cursor.fetchone()["n"], "columns": columns}

    tracks = list(mongo.tracks.find({}, {"spotify_id": 1,"artist_spotify_id": 1,"album_spotify_id": 1,"album_ids": 1}))
    artists = list(mongo.artists.find({}, {"spotify_id": 1}))
    albums = list(mongo.albums.find({}, {"spotify_id": 1,"track_ids": 1,"tracks": 1}))
    playlists = list(mongo.playlists.find({}, {"spotify_id": 1,"source_spotify_id": 1}))
    track_alias, artist_alias, album_alias = aliases(tracks), aliases(artists), aliases(albums)
    playlist_alias = aliases([{"_id":d["_id"],"spotify_id":d.get("spotify_id") or d.get("source_spotify_id")} for d in playlists])
    artist_docs = {str(d["_id"]): d for d in artists}
    album_docs = {str(d["_id"]): d for d in albums}
    track_artist_ids = {d.get("artist_spotify_id") for d in tracks if d.get("artist_spotify_id")}
    track_album_ids = {d.get("album_spotify_id") for d in tracks if d.get("album_spotify_id")}
    sql_album_track_map = defaultdict(set)
    for row in album_tracks:
        if row["track_id"] in track_alias:
            sql_album_track_map[str(row["album_id"])].add(track_alias[row["track_id"]])

    def resolves(table, raw):
        if table in {"favorite_songs", "user_library_tracks"}:
            return raw in track_alias
        if table == "favorite_artists":
            return raw in artist_alias
        return raw in album_alias or raw in sql_albums

    source_summary = {}
    for table, rows in sources.items():
        user_rows = [r for r in rows if r.get("role") == "USER"]
        valid_rows = [r for r in user_rows if r.get("status") == "ACTIVE"]
        source_summary[table] = {
            "all_rows": len(rows), "by_role": dict(Counter(r.get("role") or "UNKNOWN" for r in rows)),
            "user_rows": len(user_rows), "active_user_rows": len(valid_rows),
            "resolved_rows_all_roles": sum(resolves(table, r["target_id"]) for r in rows),
            "resolved_rows_active_users": sum(resolves(table, r["target_id"]) for r in valid_rows),
            "distinct_active_users": len({r["user_id"] for r in valid_rows}),
            "first_at": min((r["at"] for r in rows), default=None),
            "last_at": max((r["at"] for r in rows), default=None),
            "live_write_path": "not assessed by SQL; see source-code inventory in the document"}
    song_pairs = {(r["user_id"],track_alias[r["target_id"]]) for r in sources["favorite_songs"] if r["target_id"] in track_alias}
    library_pairs = {(r["user_id"],track_alias[r["target_id"]]) for r in sources["user_library_tracks"] if r["target_id"] in track_alias}
    artist_mapping = Counter()
    for row in sources["favorite_artists"]:
        raw = row["target_id"]
        matched = artist_docs.get(artist_alias.get(raw))
        artist_mapping["matches_artist_document"] += int(matched is not None)
        artist_mapping["matches_at_least_one_track_artist"] += int(raw in track_artist_ids or (matched is not None and matched.get("spotify_id") in track_artist_ids))
    album_mapping = Counter()
    for row in sources["favorite_albums"]:
        raw = row["target_id"]
        matched = album_docs.get(album_alias.get(raw))
        album_mapping["matches_mysql_album"] += int(raw in sql_albums)
        album_mapping["matches_mongo_album"] += int(matched is not None)
        album_mapping["namespace_ambiguous_matches"] += int(raw in sql_albums and matched is not None)
        references = set(sql_album_track_map.get(raw, set()))
        if matched:
            for key in matched.get("track_ids") or []:
                if isinstance(key,str) and key in track_alias:
                    references.add(track_alias[key])
            keys = {str(matched["_id"]),matched.get("spotify_id")}-{None}
            for track in tracks:
                if track.get("album_spotify_id") in keys or keys.intersection(set(track.get("album_ids") or [])):
                    references.add(str(track["_id"]))
        album_mapping["favorites_with_resolved_track_expansion"] += int(bool(references))
        album_mapping["resolved_track_expansion_occurrences"] += len(references)

    search_counts = Counter()
    search_resolution_by_type = defaultdict(Counter)
    for row in searches:
        kind, raw = row.get("selected_target_type"), row.get("selected_target_id")
        if not raw:
            continue
        search_counts["selected_rows"] += 1
        matched = (kind == "TRACK" and raw in track_alias) or (kind == "ARTIST" and raw in artist_alias) or (kind == "ALBUM" and (raw in album_alias or raw in sql_albums)) or (kind == "PLAYLIST" and raw in playlist_alias)
        search_counts["selected_rows_resolved_known_catalog"] += int(matched)
        search_counts["selected_rows_active_user"] += int(row.get("role") == "USER" and row.get("status") == "ACTIVE")
        search_counts["selected_rows_resolved_active_user"] += int(matched and row.get("role") == "USER" and row.get("status") == "ACTIVE")
        search_resolution_by_type[kind]["rows"] += 1
        search_resolution_by_type[kind]["resolved"] += int(matched)
    field_presence = {}
    for name in ("artists", "albums", "users", "genres"):
        field_presence[name] = {d["_id"]:d["n"] for d in mongo[name].aggregate([
            {"$project":{"fields":{"$objectToArray":"$$ROOT"}}},{"$unwind":"$fields"},
            {"$group":{"_id":"$fields.k","n":{"$sum":1}}}])}
    mongo_counts = {name: mongo[name].count_documents({}) for name in ("tracks","artists","albums","playlists","users","genres")}
    return {"generated_at_utc":datetime.now(timezone.utc).isoformat(),"scope":"local application; SQL READ ONLY; no search text, emotion output or raw identities exported",
        "sql_table_names":table_names,"mongo_collection_names":sorted(mongo.list_collection_names()),"tables":tables,
        "preference_sources":source_summary,"favorite_song_library_overlap":{
            "resolved_favorite_song_user_track_pairs":len(song_pairs),"resolved_library_user_track_pairs":len(library_pairs),
            "intersection_pairs":len(song_pairs & library_pairs)},
        "favorite_artist_mapping":dict(artist_mapping),"favorite_album_mapping":dict(album_mapping),
        "search":{**dict(search_counts),"search_type_counts":dict(Counter(r["search_type"] for r in searches)),
            "selected_target_type_counts":dict(Counter(r["selected_target_type"] or "NONE" for r in searches)),
            "resolution_by_target_type":{key:dict(value) for key,value in search_resolution_by_type.items()}},
        "traffic":{"rows":len(visits),"target_type_counts":dict(Counter(r["target_type"] for r in visits)),
            "user_rows":sum(r.get("role")=="USER" for r in visits),"anonymous_rows":sum(r["user_id"] is None for r in visits),
            "referrer_counts":dict(Counter(r["referrer_type"] for r in visits)),
            "with_session_id_rows":sum(bool(r["session_id"]) for r in visits)},
        "downloads":{"rows":len(downloads),"statuses":dict(Counter(r["download_status"] for r in downloads)),
            "active_user_downloaded_rows":sum(r["role"]=="USER" and r["status"]=="ACTIVE" and r["download_status"]=="DOWNLOADED" for r in downloads)},
        "catalog_counts":mongo_counts,"mongo_field_presence":field_presence}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    sql,mongo,client=worker.connections(worker.ROOT/'.env')
    try:
        from urllib.parse import urlparse
        import os
        local={"127.0.0.1","localhost","::1"}
        if urlparse(os.environ['DATABASE_SOURCE_URL'].removeprefix('jdbc:')).hostname not in local or client.address[0] not in local:
            raise RuntimeError("Audit restricted to reviewed local databases")
        result=inventory(sql,mongo)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
        print(json.dumps({"output":str(args.output),"preference_sources":result["preference_sources"],
            "favorite_song_library_overlap":result["favorite_song_library_overlap"],"favorite_artist_mapping":result["favorite_artist_mapping"],
            "favorite_album_mapping":result["favorite_album_mapping"],"search":result["search"],"traffic":result["traffic"],
            "downloads":result["downloads"],"catalog_counts":result["catalog_counts"]},ensure_ascii=False,default=str))
    finally:
        sql.rollback();sql.close();client.close()


if __name__=='__main__':
    main()
