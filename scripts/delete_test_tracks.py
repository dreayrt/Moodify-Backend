#!/usr/bin/env python3
"""Hard-delete specific test tracks (and optionally extra review-history rows).

Defaults to a read-only dry run.

  python scripts/delete_test_tracks.py --track-ids ID... [--request-ids N...]
  python scripts/delete_test_tracks.py --track-ids ID... --apply --expected <count>

Needs pymysql + pymongo. Connections come from the backend .env (--env to override).
Revenue ledger rows (qualified_streams) are listed but never deleted: they are an
independent reconciliation record.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

import pymongo
import pymysql
from bson import ObjectId

BACKEND = Path(__file__).resolve().parent.parent
KEEP_TITLE = "Lan Nay Anh Buong"

# (table, column) pairs that hold a track id; cascades handle playback_events etc.
TRACK_TABLES = [
    ("song_licenses", "track_id"),
    ("offline_downloads", "track_id"),
    ("favorite_songs", "track_id"),
    ("user_library_tracks", "track_id"),
    ("listening_history", "track_id"),
    ("album_tracks", "track_id"),
    ("karaoke_room_queue", "track_id"),
]


def load_env(path: Path) -> dict[str, str]:
    env = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$", line)
        if m and not line.lstrip().startswith("#"):
            env[m.group(1)] = m.group(2)
    return env


def id_variants(value: str) -> list:
    return [value, ObjectId(value)] if ObjectId.is_valid(value) else [value]


def upload_files(track: dict) -> set[Path]:
    root = (BACKEND / "uploads").resolve()
    found = set()
    for raw in (track.get("local_path"), track.get("image_url")):
        if not raw:
            continue
        path = urlparse(str(raw)).path.replace("\\", "/")
        i = path.find("uploads/")
        if i < 0:
            continue
        candidate = (BACKEND / path[i:]).resolve()
        if candidate.is_relative_to(root) and candidate.is_file():
            found.add(candidate)
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--env", default=str(BACKEND / ".env"))
    ap.add_argument("--track-ids", nargs="+", required=True, help="Mongo ids of the tracks to delete")
    ap.add_argument("--request-ids", nargs="*", default=[], help="extra content_review_requests ids to delete (history rows only)")
    ap.add_argument("--apply", action="store_true", help="actually delete (default is dry run)")
    ap.add_argument("--expected", type=int, help="number of tracks that must be deleted; required with --apply")
    args = ap.parse_args()

    env = load_env(Path(args.env))
    jdbc = urlparse(env["DATABASE_SOURCE_URL"].removeprefix("jdbc:"))
    sql = pymysql.connect(host=jdbc.hostname, port=jdbc.port or 3306, user=env["DATABASE_USERNAME"],
                          password=env["DATABASE_PASSWORD"], database=jdbc.path.lstrip("/"),
                          charset="utf8mb4", autocommit=False)
    mongo = pymongo.MongoClient(env["MONGODB_URI"])[env["MONGODB_DATABASE"]]
    cur = sql.cursor()

    targets = list(mongo.tracks.find({"_id": {"$in": [v for i in args.track_ids for v in id_variants(i)]}}))
    notfound = set(args.track_ids) - {str(t["_id"]) for t in targets}
    if notfound:
        print(f"Track ids not found in Mongo: {sorted(notfound)}", file=sys.stderr)
        return 1
    keep_files = set()
    missing = set()
    plan = []
    for t in targets:
        tid = str(t["_id"])
        aliases = list({tid, *(str(a) for a in [t.get("spotify_id")] if a)})
        marks = ",".join(["%s"] * len(aliases))
        counts = {}
        for table, col in TRACK_TABLES:
            cur.execute(f"SELECT COUNT(*) FROM {table} WHERE {col} IN ({marks})", aliases)
            counts[table] = cur.fetchone()[0]
        cur.execute(f"SELECT COUNT(*) FROM content_review_requests WHERE content_type='TRACK' AND content_id IN ({marks})", aliases)
        counts["content_review_requests"] = cur.fetchone()[0]
        cur.execute(f"SELECT COUNT(*) FROM qualified_streams WHERE track_id IN ({marks})", aliases)
        ledger = cur.fetchone()[0]
        files = upload_files(t) - keep_files
        plan.append((t, aliases, files))
        shown = {k: v for k, v in counts.items() if v}
        print(f"DELETE {tid}  {t.get('name')}  by {t.get('artist_name')}  created={t.get('created_at')}\n"
              f"       sql rows={shown}  files={len(files)}  revenue-ledger rows kept={ledger}")
    if missing:
        print(f"NOTE   review requests point to {len(missing)} track id(s) already gone from Mongo: {sorted(missing)}")
    for rid in args.request_ids:
        cur.execute("SELECT id, content_title, status, request_type, content_type FROM content_review_requests WHERE id=%s", (rid,))
        row = cur.fetchone()
        if not row or row[4] != "TRACK":
            print(f"Request {rid} not found or not a TRACK request.", file=sys.stderr)
            return 1
        print(f"DELETE history row {row[:4]}")
    print(f"\n{len(targets)} track(s) would be deleted.")

    if not args.apply:
        print("Dry run only. Re-run with --apply --expected <N> to delete.")
        return 0
    if args.expected != len(targets):
        print(f"Refusing: --expected must equal {len(targets)}.", file=sys.stderr)
        return 1

    try:
        for t, aliases, _ in plan:
            marks = ",".join(["%s"] * len(aliases))
            cur.execute(f"DELETE a FROM content_review_actions a JOIN content_review_requests r ON r.id=a.review_request_id "
                        f"WHERE r.content_type='TRACK' AND r.content_id IN ({marks})", aliases)
            cur.execute(f"DELETE FROM content_review_requests WHERE content_type='TRACK' AND content_id IN ({marks})", aliases)
            for table, col in TRACK_TABLES:
                cur.execute(f"DELETE FROM {table} WHERE {col} IN ({marks})", aliases)
            cur.execute(f"UPDATE karaoke_rooms SET current_track_id=NULL, playback_status='IDLE', playback_position_ms=0 "
                        f"WHERE current_track_id IN ({marks})", aliases)
        for rid in args.request_ids:
            cur.execute("DELETE FROM content_review_actions WHERE review_request_id=%s", (rid,))
            cur.execute("DELETE FROM content_review_requests WHERE id=%s AND content_type='TRACK'", (rid,))
        sql.commit()
    except Exception:
        sql.rollback()
        raise

    for t, aliases, files in plan:
        mongo.tracks.delete_one({"_id": {"$in": id_variants(str(t["_id"]))}})
        mongo.playlists.update_many({"track_spotify_ids": {"$in": aliases}}, {"$pull": {"track_spotify_ids": {"$in": aliases}}})
        mongo.albums.update_many({"track_ids": {"$in": aliases}}, {"$pull": {"track_ids": {"$in": aliases}}})
        mongo.catalog_operations.delete_many({"track_id": {"$in": aliases}})
        for f in files:
            f.unlink(missing_ok=True)
    print(f"Deleted {len(plan)} track(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
