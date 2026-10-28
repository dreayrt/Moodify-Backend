"""Exercise the packaged backend against disposable local SQL/Mongo databases.

Never copies user data. Only databases matching the generated test prefix can be
dropped. Run after mvnw -DskipTests package; credentials come from backend .env.
"""
from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
from urllib import request, error
from urllib.parse import urlparse
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor

from bson import ObjectId
import worker
import compact_storage


def execute_script(connection, source):
    # These schema files contain DDL only, not stored routines or DELIMITER blocks.
    with connection.cursor() as cursor:
        for statement in worker.sql_statements(source):
            statement = statement.strip()
            if not statement:
                continue
            if re.match(r"^(CREATE\s+DATABASE|USE\s)", statement, re.I):
                continue
            cursor.execute(statement)
    connection.commit()


def jwt(username, role, user_id, secret):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")
    now = int(time.time())
    payload = {"sub": username, "userId": user_id, "role": role, "status": "ACTIVE", "tokenType": "ACCESS", "iat": now, "exp": now+3600}
    body = encode({"alg": "HS256", "typ": "JWT"}) + "." + encode(payload)
    return body + "." + base64.urlsafe_b64encode(hmac.new(secret.encode(), body.encode(), hashlib.sha256).digest()).decode().rstrip("=")


def run():
    sql, source_mongo, client = worker.connections(worker.ROOT / ".env")
    parsed = urlparse(os.environ["DATABASE_SOURCE_URL"].removeprefix("jdbc:"))
    if parsed.hostname not in {"localhost", "127.0.0.1", "::1"} or client.address[0] not in {"localhost", "127.0.0.1", "::1"}:
        raise RuntimeError("Smoke tests require local database servers")
    database = "moodify_recommendation_test_" + secrets.token_hex(6)
    if not re.fullmatch(r"moodify_recommendation_test_[a-f0-9]{12}", database):
        raise RuntimeError("Invalid disposable database name")
    process = None
    log = None
    checked = []
    try:
        with sql.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        sql.select_db(database)
        execute_script(sql, (worker.ROOT / "database/Moodify.annotated.schema.sql").read_text(encoding="utf-8"))
        # These are existing business tables in the application, absent from the older annotated fixture schema.
        with sql.cursor() as cursor:
            cursor.execute("""CREATE TABLE albums (id BIGINT PRIMARY KEY,title VARCHAR(200) NOT NULL,user_id BIGINT NOT NULL,
                description TEXT,cover_url VARCHAR(500),release_date DATE,is_public BOOLEAN NOT NULL DEFAULT TRUE,
                status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(user_id) REFERENCES users(id))""")
            cursor.execute("""CREATE TABLE album_tracks (id BIGINT AUTO_INCREMENT PRIMARY KEY,album_id BIGINT NOT NULL,
                track_id VARCHAR(80) NOT NULL,track_order INT NOT NULL DEFAULT 0,added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(album_id) REFERENCES albums(id))""")
        sql.commit()
        mongo = client[database]
        now = datetime.now(timezone.utc)
        with sql.cursor() as cursor:
            for index, role in [(1,"USER"), (2,"USER"), (3,"ADMIN"), (4,"USER"), (5,"USER")]:
                cursor.execute("INSERT INTO users(id,full_name,email,username,role,status) VALUES (%s,%s,%s,%s,%s,'ACTIVE')",
                               (index, f"Recommendation test {index}", f"test{index}@example.invalid", f"rec_test_{index}", role))
            for i in range(12):
                cursor.execute("""INSERT INTO song_licenses(track_id,license_type,copyright_owner,status,issue_date,expiry_date)
                                  VALUES (%s,'TEST','Isolated integration fixture','ACTIVE',%s,%s)""",
                               (f"track-{i}", (now-timedelta(days=1)).date(), (now+timedelta(days=30)).date()))
            cursor.execute("INSERT INTO user_library_tracks(user_id,track_id) VALUES (1,'external-0')")
            cursor.execute("UPDATE users SET status='BANNED' WHERE id=5")
        sql.commit()
        for i in range(12):
            mongo.tracks.insert_one({"_id": f"track-{i}", "spotify_id": f"external-{i}", "name": f"Fixture track {i}",
                "artist_name": f"Artist {i//2}", "artist_spotify_id": f"artist-{i//2}", "genres": ["pop"], "duration_ms": 180000,
                "status": "published", "moderation_status": "approved", "visibility": "public", "local_path": f"data/audio/test-{i}.mp3",
                "audio_features": {"energy": .3+i*.02,"valence": .5,"acousticness": .6,"danceability": .3,"bpm": 85.0}})
        mongo.playlists.insert_many([
            {"_id":"public-playlist","name":"Public fixture","username":"rec_test_2","is_public":True,"track_spotify_ids":["external-0","external-1"],"created_at":now,"updated_at":now},
            {"_id":"private-playlist","name":"Private fixture","username":"rec_test_2","is_public":False,"track_spotify_ids":["external-2"],"created_at":now,"updated_at":now}])

        object_artist_id=ObjectId()
        mongo.artists.insert_many([{"_id":object_artist_id if i==4 else f"artist-profile-{i}","spotify_id":f"artist-{i}","name":f"Fixture artist {i}"} for i in range(6)])
        mongo.albums.insert_many([
            {"_id":"catalog-album","spotify_id":"album-external","name":"Catalog album","artist_name":"Fixture artist 3",
             "track_ids":["external-6"],"created_at":now,"updated_at":now},
            {"_id":"private-album","name":"Private fixture album","is_public":False,"owner_user_id":2,"track_ids":["external-6"]}])
        mongo.playlists.insert_one({"_id":"unknown-visibility","name":"Legacy fixture","tracks":[]})

        secret = secrets.token_urlsafe(48)[:48]
        with socket.socket() as free_socket:
            free_socket.bind(("127.0.0.1",0))
            port = free_socket.getsockname()[1]
        port = int(os.environ.get("RECOMMENDATION_SMOKE_PORT", port))
        env = os.environ.copy()
        env.update(SERVER_PORT=str(port), DATABASE_SOURCE_URL=f"jdbc:mysql://{parsed.hostname}:{parsed.port or 3306}/{database}?useSSL=false&serverTimezone=UTC",
                   MONGODB_DATABASE=database, JWT_SECRET=secret, SYNC_SCHEDULER_ENABLED="false",
                   APP_SCHEDULING_ENABLED="false", SPRING_JPA_SHOW_SQL="false", SPRING_JPA_HIBERNATE_DDL_AUTO="none",
                   APP_CORS_ALLOWED_ORIGINS="http://localhost:38100", SERVER_ADDRESS="127.0.0.1",
                   ORACLE_AUDIO_BASE_URL=f"http://127.0.0.1:{port}/test-no-audio")
        java = Path("C:/Program Files/Java/jdk-26/bin/java.exe")
        if not java.exists():
            java = Path("java")
        jar = worker.ROOT / "target/moodify-0.0.1-SNAPSHOT.jar"
        if not jar.exists():
            raise RuntimeError("Build the backend jar before running smoke tests")
        with zipfile.ZipFile(jar) as packaged:
            entries = set(packaged.namelist())
            assert "BOOT-INF/classes/db/recommendation_v1.sql" not in entries, "Run mvnw clean package to remove the obsolete SQL resource"
            assert "BOOT-INF/classes/com/laphuth/moodify/recommendation/RecommendationSchema.class" not in entries
        log_path = worker.ROOT / "target/recommendation-smoke-server.log"
        log = log_path.open("w", encoding="utf-8")
        process = subprocess.Popen([str(java),"-jar",str(jar),"--spring.config.import=optional:file:target/no-integration-env.properties",
                                    "--app.scheduling.enabled=false", "--subscription.maintenance.enabled=false"],
                                   cwd=worker.ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        base = f"http://127.0.0.1:{port}"
        tokens = {index: jwt(f"rec_test_{index}", "ADMIN" if index==3 else "USER", index, secret) for index in (1,2,3,4,5)}

        def call(path, user=1, method="GET", body=None, expected=200):
            headers = {"Content-Type":"application/json"}
            if user is not None:
                headers["Authorization"] = "Bearer " + tokens[user]
            req = request.Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
            try:
                with request.urlopen(req, timeout=25) as response:
                    status, raw = response.status, response.read()
            except error.HTTPError as response:
                status, raw = response.code, response.read()
            if status not in ((expected,) if isinstance(expected,int) else expected):
                raise AssertionError(f"{method} {path}: expected {expected}, got {status}: {raw.decode(errors='replace')[:600]}")
            return json.loads(raw) if raw else None

        def eventually(predicate, seconds=5):
            deadline=time.monotonic()+seconds
            while time.monotonic()<deadline:
                if predicate(): return
                time.sleep(.025)
            raise AssertionError("Asynchronous recommendation outcome did not reach the expected state")

        def operation_count(name):
            return call("/api/admin/recommendations/operations",user=3)["runtime"]["counters"].get(name,0)

        deadline = time.time()+90
        while time.time()<deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Backend failed to start; inspect {log_path}")
            try:
                call("/api/recommendations/home", user=None, expected=401)
                break
            except (OSError, error.URLError):
                time.sleep(.5)
        else:
            raise RuntimeError(f"Backend startup timeout; inspect {log_path}")
        checked.append("anonymous access rejected")
        # Every worker batch must leave its snapshot, even when a different connection commits.
        first_sources=worker.load_sources(sql,mongo,now)
        other,_,other_client=worker.connections(worker.ROOT/".env")
        try:
            other.select_db(database)
            with other.cursor() as cursor:
                cursor.execute("INSERT INTO listening_history(user_id,track_id,started_at,listened_duration_ms) VALUES (4,'external-3',%s,45000)",(now.replace(tzinfo=None),))
                added_id=cursor.lastrowid
                cursor.execute("INSERT INTO listening_history(user_id,track_id,started_at,listened_duration_ms) VALUES (5,'external-3',%s,45000)",(now.replace(tzinfo=None),))
                banned_id=cursor.lastrowid
            other.commit()
            second_sources=worker.load_sources(sql,mongo,now)
            assert len(second_sources[2])==len(first_sources[2])+1
            assert all(row["user_id"]!=5 for row in second_sources[2])
            with other.cursor() as cursor:
                cursor.execute("DELETE FROM listening_history WHERE id IN (%s,%s)",(added_id,banned_id))
            other.commit()
            assert len(worker.load_sources(sql,mongo,now)[2])==len(first_sources[2])
        finally:
            other.close();other_client.close()
        checked.append("worker refreshes read-only snapshots between batches and excludes banned accounts")
        call("/api/recommendations/home",user=5,expected=(401,403))
        call("/api/playlists/unknown-visibility",expected=404)
        checked.append("inactive users and ownerless legacy playlist visibility fail closed")

        with ThreadPoolExecutor(max_workers=3) as executor:
            concurrent_homes = list(executor.map(lambda _: call("/api/recommendations/home"), range(3)))
        home = concurrent_homes[0]
        concurrent_mix_versions = [{i["entityId"]: i["versionId"] for s in h["sections"] for i in s["items"]
                                    if i["entityType"]=="PERSONALIZED_PLAYLIST"} for h in concurrent_homes]
        assert all(versions==concurrent_mix_versions[0] for versions in concurrent_mix_versions)
        checked.append("concurrent generation publishes one consistent version per mix")
        all_items = [item for section in home["sections"] for item in section["items"]]
        assert all_items and all(item["entityId"]!="private-playlist" for item in all_items)
        checked.append("home returns real catalog and hides private playlists")
        mix_card = next(item for item in all_items if item["entityType"]=="PERSONALIZED_PLAYLIST")
        mix_id, version_id = mix_card["entityId"], mix_card["versionId"]
        mix = call(f"/api/me/personalized-playlists/{mix_id}")
        assert mix["trackCount"]>0 and mix["versionId"]==version_id
        call(f"/api/me/personalized-playlists/{mix_id}", user=2, expected=404)
        call(f"/api/me/personalized-playlists/{mix_id}/versions/{version_id}",user=2,expected=404)
        checked.append("private mix and versions enforce ownership")
        call("/api/playlists/private-playlist",expected=404)
        call("/api/playlists/private-playlist",user=2)
        checked.append("existing private playlist read path enforces ownership")
        second_home = call("/api/recommendations/home")
        same_mix = next(item for section in second_home["sections"] for item in section["items"] if item["entityId"]==mix_id)
        assert same_mix["versionId"]==version_id
        checked.append("GET does not regenerate playlist versions")
        event = {"eventId":str(uuid.uuid4()),"eventType":"IMPRESSION","attributionToken":mix_card["attributionToken"],"occurredAt":now.isoformat()}
        assert call("/api/recommendations/events",method="POST",body={"events":[event]})["accepted"]==1
        assert call("/api/recommendations/events",method="POST",body={"events":[event]})["accepted"]==0
        call("/api/recommendations/events",user=2,method="POST",body={"events":[event]},expected=403)
        checked.append("event retries deduplicate and attribution cannot cross users")
        queue_body = {"entityType":mix_card["entityType"],"entityId":mix_id,"versionId":version_id,"attributionToken":mix_card["attributionToken"]}
        queue = call("/api/playback/queues",method="POST",body=queue_body)
        call("/api/playback/queues",user=2,method="POST",body=queue_body,expected=403)
        first = queue["tracks"][0]
        playback = {"sessionKey":str(uuid.uuid4()),"trackId":first["track"]["id"],"positionMs":0,"eventType":"PLAY","queueToken":first["queueToken"],"deviceType":"WEB"}
        recorded = call("/api/analytics/listening-session",method="POST",body=playback)
        assert recorded["success"]
        with sql.cursor() as cursor:
            cursor.execute("SELECT source,source_id,started_at FROM listening_history WHERE id=%s",(recorded["historyId"],))
            history_row=cursor.fetchone()
        assert history_row["source"]=="PLAYLIST" and history_row["source_id"]==mix_id
        assert abs((datetime.now(timezone.utc)-history_row["started_at"].replace(tzinfo=timezone.utc)).total_seconds())<60, "Playback DATETIME must be stored as UTC independent of JVM timezone"
        sql.commit()  # End the reader snapshot before later requests commit their sessions.
        eventually(lambda:mongo.recommendation_events.count_documents({"kind":"EVENT","event_type":"PLAY_START","playback_session_id":playback["sessionKey"]})==1)
        call("/api/analytics/listening-session",user=2,method="POST",body={**playback,"sessionKey":str(uuid.uuid4())},expected=403)
        checked.append("queue playback preserves playlist attribution without cross-user replay")
        # Mongo analytics failure must not roll back committed MySQL listening/financial data.
        failed_session=str(uuid.uuid4())
        failed_count=operation_count("outcome.failed")
        mongo.command({"collMod":"recommendation_events", "validator":{"event_type":{"$ne":"PLAY_START"}}, "validationLevel":"strict"})
        try:
            failed_log_play=call("/api/analytics/listening-session",method="POST",body={**playback,"sessionKey":failed_session})
            assert failed_log_play["success"]
            eventually(lambda:operation_count("outcome.failed")>failed_count)
            with sql.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) n FROM revenue_playback_sessions WHERE session_key=%s",(failed_session,))
                assert cursor.fetchone()["n"]==1
            assert mongo.recommendation_events.count_documents({"kind":"EVENT","playback_session_id":failed_session})==0
        finally:
            mongo.command({"collMod":"recommendation_events", "validator":{}, "validationLevel":"strict"})
        checked.append("Mongo outcome failure preserves committed listening session")
        copy_body={"requestId":str(uuid.uuid4()),"versionId":version_id}
        copy1=call(f"/api/me/personalized-playlists/{mix_id}/copies",method="POST",body=copy_body)
        copy2=call(f"/api/me/personalized-playlists/{mix_id}/copies",method="POST",body=copy_body)
        assert copy1["id"]==copy2["id"] and not copy1["isPublic"]
        checked.append("save-copy is private and idempotent")
        call(f"/api/me/personalized-playlists/{mix_id}/refresh",method="POST",expected=429)
        feedback={"entityType":"TRACK","entityId":first["track"]["id"],"feedbackType":"NOT_INTERESTED","enabled":True}
        call("/api/recommendations/feedback",method="PUT",body=feedback)
        new_home=call("/api/recommendations/home")
        suggested=[i for s in new_home["sections"] for i in s["items"] if i["entityType"]=="TRACK"]
        assert all(i["entityId"]!=feedback["entityId"] for i in suggested)
        rebuilt_mix=call(f"/api/me/personalized-playlists/{mix_id}")
        assert rebuilt_mix["versionId"]!=version_id
        assert all(track["id"]!=feedback["entityId"] for track in rebuilt_mix["tracks"])
        checked.append("negative feedback is enforced on subsequent recommendations")
        call("/api/admin/recommendations/campaigns",expected=403)
        campaign={"name":"Integration campaign","active":True,"timezone":"Asia/Ho_Chi_Minh",
                  "startsAt":(now-timedelta(hours=1)).isoformat(),"endsAt":(now+timedelta(days=1)).isoformat(),
                  "priority":50,"genres":[],"targets":[{"entityType":"TRACK","entityId":"track-10"}],"expectedRevision":1}
        campaign_id=call("/api/admin/recommendations/campaigns",user=3,method="POST",body=campaign)["id"]
        call(f"/api/admin/recommendations/campaigns/{campaign_id}",user=3,method="PUT",body=campaign)
        call(f"/api/admin/recommendations/campaigns/{campaign_id}",user=3,method="PUT",body=campaign,expected=409)
        checked.append("campaign administration is role-restricted and revision-checked")
        call("/api/admin/recommendations/readiness",expected=403)
        readiness=call("/api/admin/recommendations/readiness",user=3)
        assert readiness["catalogTracks"]==12 and readiness["eligibleTracks"]>0
        checked.append("catalog readiness diagnostics are available only to administrators")
        active_home=call("/api/recommendations/home")
        assert any(s["id"]=="campaigns" and any(i["entityId"]=="track-10" for i in s["items"]) for s in active_home["sections"])
        active_mix=call(f"/api/me/personalized-playlists/{mix_id}")
        ending={**campaign,"expectedRevision":2,"endsAt":(now-timedelta(minutes=1)).isoformat()}
        call(f"/api/admin/recommendations/campaigns/{campaign_id}",user=3,method="PUT",body=ending)
        ended_home=call("/api/recommendations/home")
        assert all(s["id"]!="campaigns" for s in ended_home["sections"])
        ended_mix=call(f"/api/me/personalized-playlists/{mix_id}")
        assert active_mix["versionId"]!=ended_mix["versionId"]
        checked.append("campaign changes affect new recommendations and mix revisions without retraining")
        with sql.cursor() as cursor:
            cursor.execute("UPDATE song_licenses SET status='REVOKED' WHERE track_id='track-10'")
        sql.commit()
        current_mix=call(f"/api/me/personalized-playlists/{mix_id}")
        assert all(track["id"]!="track-10" for track in current_mix["tracks"])
        checked.append("revoked content is filtered even from a previously generated mix")
        assert mongo.recommendation_events.count_documents({"kind":"EVENT","event_type":"SAVE_COPY"})==1
        checked.append("save-copy outcomes are recorded once by the server")
        mongo.playlists.update_one({"_id":"public-playlist"},{"$set":{"is_public":False}})
        private_home=call("/api/recommendations/home")
        assert all(i["entityId"]!="public-playlist" for s in private_home["sections"] for i in s["items"])
        call("/api/playback/queues",method="POST",body={"entityType":"PLAYLIST","entityId":"public-playlist","versionId":None},expected=404)
        checked.append("visibility changes are enforced at recommendation and queue time")
        sql.commit()
        # Preference scores are independent of pretend listening history.
        def track_items(h):
            return {i["entityId"]:i for section in h["sections"] if section["id"]=="for_you" for i in section["items"]}
        def mix_versions(h):
            return {i["entityId"]:i["versionId"] for section in h["sections"] if section["id"]=="personal_mixes" for i in section["items"]}
        baseline=call("/api/recommendations/home")
        call("/api/me/followed-artists/artist-profile-3",method="PUT",body={"enabled":True})
        followed_home=call("/api/recommendations/home")
        assert track_items(followed_home)["track-6"]["score"] > track_items(baseline)["track-6"]["score"]+.1
        assert "artist-3" in call("/api/me/followed-artists")
        assert "artist-3" not in call("/api/me/followed-artists",user=2)
        assert mix_versions(followed_home)!=mix_versions(baseline)
        call("/api/me/followed-artists/artist-3",method="PUT",body={"enabled":True})
        assert mix_versions(call("/api/recommendations/home"))==mix_versions(followed_home)
        with sql.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) n FROM favorite_artists WHERE user_id=1 AND artist_id='artist-3'")
            assert cursor.fetchone()["n"]==1
        sql.commit()
        call("/api/me/followed-artists/artist-3",method="PUT",body={"enabled":False})
        unfollowed_home=call("/api/recommendations/home")
        assert track_items(unfollowed_home)["track-6"]["score"] < track_items(followed_home)["track-6"]["score"]-.1
        assert "track-6" in track_items(unfollowed_home)
        checked.append("artist follow is canonical, private, idempotent, changes score and refreshes mixes; unfollow is neutral")
        call("/api/me/followed-artists/artist-3",method="PUT",body={},expected=400)
        call("/api/me/followed-artists/not-a-real-artist",method="PUT",body={"enabled":True},expected=404)
        call("/api/recommendations/feedback",method="PUT",body={"entityType":"TRACK","entityId":"track-6","feedbackType":"FOLLOW","enabled":True},expected=400)
        call("/api/recommendations/feedback",method="PUT",body={"entityType":"ARTIST","entityId":"artist-profile-3","feedbackType":"FOLLOW","enabled":True})
        call("/api/recommendations/feedback",method="PUT",body={"entityType":"ARTIST","entityId":"artist-profile-3","feedbackType":"HIDE","enabled":True})
        assert "track-6" not in track_items(call("/api/recommendations/home"))
        call("/api/recommendations/feedback",method="PUT",body={"entityType":"ARTIST","entityId":"artist-profile-3","feedbackType":"HIDE","enabled":False})
        call("/api/recommendations/feedback",method="PUT",body={"entityType":"ARTIST","entityId":"artist-profile-3","feedbackType":"FOLLOW","enabled":False})
        assert mongo.recommendation_state.count_documents({"kind":"FEEDBACK","feedback_type":"FOLLOW"})==0
        checked.append("FOLLOW feedback uses the SQL authority and hide wins; invalid targets are rejected")
        album_baseline=call("/api/recommendations/home")
        call("/api/me/favorite-albums/catalog-album",method="PUT",body={"enabled":True})
        liked_album_home=call("/api/recommendations/home")
        assert track_items(liked_album_home)["track-6"]["score"] > track_items(album_baseline)["track-6"]["score"]+.07
        assert mix_versions(liked_album_home)!=mix_versions(album_baseline)
        assert any(a["id"]=="catalog-album" for a in call("/api/albums/favorites/me"))
        assert call("/api/albums/album-external")["tracks"][0]["id"]=="track-6"
        call("/api/me/favorite-albums/private-album",method="PUT",body={"enabled":True},expected=404)
        call("/api/me/favorite-albums/unknown-album",method="PUT",body={"enabled":True},expected=404)
        call("/api/me/favorite-albums/album-external",method="PUT",body={"enabled":False})
        checked.append("Mongo album favorites validate ownership, expand bounded priors, appear in existing APIs and refresh mixes")
        # Keep SQL numeric JSON IDs compatible and reject unqualified cross-store collisions.
        with sql.cursor() as cursor:
            cursor.execute("INSERT INTO albums(id,title,user_id,is_public) VALUES (77,'Fixture SQL album',3,TRUE)")
            cursor.execute("INSERT INTO album_tracks(album_id,track_id,track_order) VALUES (77,'external-6',0)")
        sql.commit()
        mongo.albums.insert_one({"_id":"77","name":"Conflicting Mongo fixture","track_ids":["external-7"]})
        call("/api/me/favorite-albums/77",method="PUT",body={"enabled":True},expected=409)
        call("/api/me/favorite-albums/sql:77",method="PUT",body={"enabled":True})
        call("/api/me/favorite-albums/mongo:77",method="PUT",body={"enabled":True})
        assert call("/api/albums/sql:77")["id"]==77
        assert call("/api/albums/mongo:77")["id"]=="77"
        call("/api/me/favorite-albums/sql:77",method="PUT",body={"enabled":False})
        call("/api/me/favorite-albums/mongo:77",method="PUT",body={"enabled":False})
        call(f"/api/me/followed-artists/{object_artist_id}",method="PUT",body={"enabled":True})
        assert "artist-4" in call("/api/me/followed-artists")
        call("/api/me/followed-artists/artist-4",method="PUT",body={"enabled":False})
        assert call("/api/albums/album-external/favorite",method="POST")["favorited"] is True
        assert call("/api/albums/catalog-album/favorite",method="POST")["favorited"] is False
        checked.append("SQL/Mongo album namespaces preserve existing JSON types, ObjectId artists resolve and original album toggle works")
        # Source duplicates must not double a saved-track preference or revive a removed heart.
        duplicate_baseline=call("/api/recommendations/home")
        with sql.cursor() as cursor:
            cursor.execute("INSERT INTO user_library_tracks(user_id,track_id) VALUES (1,'track-0')")
            cursor.execute("INSERT INTO favorite_songs(user_id,track_id) VALUES (1,'external-0')")
            cursor.execute("INSERT INTO favorite_artists(user_id,artist_id) VALUES (1,'legacy-unresolved')")
            cursor.execute("INSERT INTO favorite_albums(user_id,album_id) VALUES (1,'legacy-unresolved')")
        sql.commit()
        duplicate_home=call("/api/recommendations/home")
        assert {key:item["score"] for key,item in track_items(duplicate_home).items()}=={key:item["score"] for key,item in track_items(duplicate_baseline).items()}
        assert mix_versions(duplicate_home)==mix_versions(duplicate_baseline)
        diagnostics=call("/api/me/recommendation-preferences")["diagnostics"]
        assert diagnostics["unresolved_artists"]>=1 and diagnostics["unresolved_albums"]>=1
        call("/api/me/followed-artists/legacy-unresolved",method="PUT",body={"enabled":False})
        call("/api/me/favorite-albums/legacy-unresolved",method="PUT",body={"enabled":False})
        assert call("/api/users/me/library/tracks/track-0/exists")["exists"] is True
        call("/api/users/me/library/tracks/track-0",method="DELETE")
        assert call("/api/users/me/library/tracks/external-0/exists")["exists"] is False
        assert mix_versions(call("/api/recommendations/home"))!=mix_versions(duplicate_home)
        with sql.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) n FROM user_library_tracks WHERE user_id=1 AND track_id IN ('track-0','external-0')")
            assert cursor.fetchone()["n"]==0
            cursor.execute("DELETE FROM favorite_songs WHERE user_id=1 AND track_id='external-0'")
        sql.commit()
        call("/api/users/me/library/tracks/external-0",method="POST")
        call("/api/users/me/library/tracks/track-0",method="POST")
        with sql.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) n FROM user_library_tracks WHERE user_id=1 AND track_id IN ('track-0','external-0')")
            assert cursor.fetchone()["n"]==1
        sql.commit()
        checked.append("library aliases deduplicate, legacy favorites do not double count and unresolved preferences have diagnostics")
        checked.append("unlike by Mongo identity removes both saved aliases and refreshes preferences without legacy resurrection")
        # Search intents are short-lived context; actual listening supersedes the same selected track.
        search=call("/api/users/me/search-history",method="POST",body={"keyword":"Fixture selection","searchType":"TRACK"},expected=201)
        call(f"/api/users/me/search-history/{search['id']}/selection",method="PATCH",body={"targetType":"TRACK","targetId":"external-6"})
        assert "track-6" in call("/api/me/recommendation-preferences")["intentTracks"]
        with sql.cursor() as cursor:
            # Leave room for MySQL DATETIME(0) rounding; a fixture must not accidentally be in the future.
            cursor.execute("INSERT INTO listening_history(user_id,track_id,started_at,listened_duration_ms) VALUES (1,'external-6',%s,30000)",((datetime.now(timezone.utc)+timedelta(hours=7)-timedelta(seconds=3)).replace(tzinfo=None),))
            selected_play_id=cursor.lastrowid
        sql.commit()
        assert "track-6" not in call("/api/me/recommendation-preferences")["intentTracks"]
        with sql.cursor() as cursor: cursor.execute("DELETE FROM listening_history WHERE id=%s",(selected_play_id,))
        sql.commit()
        mongo.tracks.update_one({"_id":"track-11"},{"$set":{"genres":["indie"]}})
        assert set(track_items(call("/api/recommendations/home?genre=indie")))=={"track-11"}
        assert len(track_items(call("/api/recommendations/home?genre=all")))>1
        checked.append("selected search context deduplicates actual plays and request genre scopes ranking")
        # ID ownership survives account rename; the former name cannot claim a saved copy.
        with sql.cursor() as cursor:
            cursor.execute("UPDATE users SET username='renamed-fixture' WHERE id=1")
            cursor.execute("UPDATE users SET username='rec_test_1' WHERE id=2")
        sql.commit()
        tokens[1]=jwt("renamed-fixture","USER",1,secret);tokens[2]=jwt("rec_test_1","USER",2,secret)
        assert call(f"/api/playlists/{copy1['id']}")["id"]==copy1["id"]
        call(f"/api/playlists/{copy1['id']}",user=2,expected=404)
        with sql.cursor() as cursor:
            cursor.execute("UPDATE users SET username='rec_test_2' WHERE id=2")
            cursor.execute("UPDATE users SET username='rec_test_1' WHERE id=1")
        sql.commit()
        tokens[1]=jwt("rec_test_1","USER",1,secret);tokens[2]=jwt("rec_test_2","USER",2,secret)
        checked.append("immutable playlist owner survives rename and prevents username reuse takeover")
        # Merge unique users after alias resolution; banned listeners never create a trend.
        trend_ids=[]
        with sql.cursor() as cursor:
            def add_listen(user,track,at):
                cursor.execute("INSERT INTO listening_history(user_id,track_id,started_at,listened_duration_ms) VALUES (%s,%s,%s,45000)",
                               (user,track,(at+timedelta(hours=7)).replace(tzinfo=None)))
                trend_ids.append(cursor.lastrowid)
            for user in (1,2,4):add_listen(user,"external-5",now-timedelta(hours=30))
            for user,track in [(1,"track-8"),(1,"external-8"),(2,"external-8"),(4,"track-8"),
                               (1,"track-9"),(2,"external-9"),(5,"track-9")]:add_listen(user,track,now)
        sql.commit()
        trend_home=call("/api/recommendations/home")
        trend_tracks={i["entityId"] for section in trend_home["sections"] if section["id"]=="trending" for i in section["items"]}
        assert "track-8" in trend_tracks and "track-9" not in trend_tracks
        with sql.cursor() as cursor:
            cursor.execute("DELETE FROM listening_history WHERE id IN ("+','.join(['%s']*len(trend_ids))+")",trend_ids)
        sql.commit()
        checked.append("trend canonicalizes aliases before distinct counting and excludes banned-account inflation")

        # Operations remain admin-only, bounded and useful without modifying catalog data.
        call("/api/admin/recommendations/operations",expected=403)
        call("/api/admin/recommendations/catalog-review",expected=403)
        preview=call("/api/admin/recommendations/catalog-review?limit=3",user=3)
        assert preview["readOnly"] and preview["pendingTracks"]>=1 and len(preview["items"])<=3
        assert any("VERIFY_LICENSE_EFFECTIVE_DATES_AND_STATUS" in item["reasons"] for item in preview["items"])
        call("/api/admin/recommendations/catalog-review?limit=101",user=3,expected=400)
        checked.append("admin-only catalog preview is bounded, read-only and identifies revoked rights")

        def controls(enabled=True,collaborative=True,campaigns=True):
            current=call("/api/admin/recommendations/operations",user=3)["runtime"]["flags"]
            return call("/api/admin/recommendations/controls",user=3,method="PUT",body={
                "enabled":enabled,"collaborative":collaborative,"campaigns":campaigns,"expectedRevision":current["revision"]})
        control_snapshot=call("/api/admin/recommendations/operations",user=3)["runtime"]["flags"]
        control_body={"enabled":True,"collaborative":True,"campaigns":True,"expectedRevision":control_snapshot["revision"]}
        call("/api/admin/recommendations/controls",method="PUT",body=control_body,expected=403)
        call("/api/admin/recommendations/controls",user=3,method="PUT",body={},expected=400)
        controls()
        call("/api/admin/recommendations/controls",user=3,method="PUT",body=control_body,expected=409)

        mongo.recommendation_state.replace_one({"_id":"profile:1"},{"_id":"profile:1","kind":"PROFILE","user_id":1,
            "as_of":datetime.now(timezone.utc),"collaborative_scores":{"track-6":1.0},"model_version":"fixture-cf"},upsert=True)
        with_cf=call("/api/recommendations/home")
        assert with_cf["modelVersion"]=="fixture-cf"
        controls(collaborative=False)
        without_cf=call("/api/recommendations/home")
        assert without_cf["modelVersion"]=="content-session-v1"
        assert mix_versions(with_cf)!=mix_versions(without_cf)
        assert mongo.recommendation_state.find_one({"_id":"profile:1"})["model_version"]=="fixture-cf"
        controls(collaborative=False,campaigns=False)
        extra_campaign=call("/api/admin/recommendations/campaigns",user=3,method="POST",body={**campaign,"name":"Flag fixture",
            "targets":[{"entityType":"TRACK","entityId":"track-6"}]})["id"]
        assert all(section["id"]!="campaigns" for section in call("/api/recommendations/home")["sections"])
        controls(collaborative=False,campaigns=True)
        assert any(section["id"]=="campaigns" for section in call("/api/recommendations/home")["sections"])
        call(f"/api/admin/recommendations/campaigns/{extra_campaign}",user=3,method="PUT",body={**campaign,
            "active":False,"expectedRevision":1,"targets":[{"entityType":"TRACK","entityId":"track-6"}]})
        checked.append("runtime controls validate admin and revision; CF and campaign switches change serving and mix versions")

        safe_queue=call("/api/playback/queues",method="POST",body={"entityType":"TRACK","entityId":"track-6","versionId":None})
        safe_play={"sessionKey":str(uuid.uuid4()),"trackId":"track-6","positionMs":0,"eventType":"PLAY",
            "queueToken":safe_queue["tracks"][0]["queueToken"]}
        assert call("/api/analytics/listening-session",method="POST",body=safe_play)["success"]
        controls(enabled=False,collaborative=False,campaigns=False)
        call("/api/recommendations/home",expected=503)
        call("/api/me/personalized-playlists",expected=503)
        call(f"/api/me/personalized-playlists/{mix_id}/refresh",method="POST",expected=503)
        existing_mix=call(f"/api/me/personalized-playlists/{mix_id}")
        assert existing_mix["id"]==mix_id
        call(f"/api/me/personalized-playlists/{mix_id}",user=2,expected=404)
        assert call("/api/analytics/listening-session",method="POST",body={**safe_play,"eventType":"HEARTBEAT"})["success"]
        active_queue=call("/api/playback/queues",method="POST",body={"entityType":"PERSONALIZED_PLAYLIST","entityId":mix_id,"versionId":existing_mix["versionId"]})
        assert active_queue["tracks"]
        checked.append("global disable stops new inference but preserves owned mix reads, queues and existing playback")

        track_hide={"entityType":"TRACK","entityId":"track-6","feedbackType":"HIDE","enabled":True}
        call("/api/recommendations/feedback",method="PUT",body=track_hide)
        call("/api/analytics/listening-session",method="POST",body={**safe_play,"eventType":"HEARTBEAT"},expected=404)
        call("/api/recommendations/feedback",method="PUT",body={**track_hide,"enabled":False})
        with sql.cursor() as cursor:cursor.execute("UPDATE song_licenses SET status='REVOKED' WHERE track_id='track-6'")
        sql.commit()
        call("/api/analytics/listening-session",method="POST",body={**safe_play,"eventType":"HEARTBEAT"},expected=404)
        with sql.cursor() as cursor:cursor.execute("UPDATE song_licenses SET status='ACTIVE' WHERE track_id='track-6'")
        sql.commit()
        controls()
        mongo.recommendation_state.delete_one({"_id":"profile:1"})
        checked.append("old queue token rechecks hide and license revocation even while inference is disabled")

        # Fail only fixture outcome writes, leaving track/owner/security checks available.
        eventually(lambda:call("/api/admin/recommendations/operations",user=3)["outcomeQueue"]["inFlight"]==0)
        failed_before=operation_count("outcome.failed")
        mongo.command({"collMod":"recommendation_events","validator":{"event_type":{"$ne":"PLAY_START"}},"validationLevel":"strict"})
        try:
            for i in range(3):
                assert call("/api/analytics/listening-session",method="POST",body={**safe_play,"sessionKey":str(uuid.uuid4())})["success"]
                eventually(lambda:operation_count("outcome.failed")>=failed_before+i+1)
            eventually(lambda:call("/api/admin/recommendations/operations",user=3)["outcomeQueue"]["circuitOpen"])
            dropped_before=operation_count("outcome.dropped_circuit")
            dropped_session=str(uuid.uuid4())
            assert call("/api/analytics/listening-session",method="POST",body={**safe_play,"sessionKey":dropped_session})["success"]
            assert operation_count("outcome.dropped_circuit")>dropped_before
            with sql.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) n FROM revenue_playback_sessions WHERE session_key=%s",(dropped_session,))
                assert cursor.fetchone()["n"]==1
            sql.commit()
        finally: mongo.command({"collMod":"recommendation_events","validator":{},"validationLevel":"strict"})
        metrics=call("/api/admin/recommendations/operations",user=3)["runtime"]["counters"]
        assert metrics["playback.requests"]>0 and metrics["recommendation.unavailable"]>=1
        assert any(key.startswith("playback.latency_ms.") for key in metrics)
        checked.append("outcome circuit and drop metrics preserve committed sessions during repeated Mongo analytics failures")
        # An empty successful ranking and a failed dependency must have different metrics.
        with sql.cursor() as cursor:
            cursor.execute("SELECT id,status FROM song_licenses")
            original_license_states=cursor.fetchall()
            cursor.execute("UPDATE song_licenses SET status='REVOKED'")
        sql.commit()
        empty_before=operation_count("recommendation.empty")
        try:
            mongo.command({"collMod":"recommendation_events","validator":{"kind":{"$ne":"DECISION"}},"validationLevel":"strict"})
            call("/api/recommendations/home",expected=500)
            assert operation_count("recommendation.empty")==empty_before
            mongo.command({"collMod":"recommendation_events","validator":{},"validationLevel":"strict"})
            empty_home=call("/api/recommendations/home")
            assert all(not section["items"] for section in empty_home["sections"])
            assert operation_count("recommendation.empty")==empty_before+1
        finally:
            mongo.command({"collMod":"recommendation_events","validator":{},"validationLevel":"strict"})
            with sql.cursor() as cursor:
                cursor.executemany("UPDATE song_licenses SET status=%s WHERE id=%s",[(row["status"],row["id"]) for row in original_license_states])
            sql.commit()
        checked.append("empty successful results are counted separately from failed decision persistence")
        worker.run_once(sql,mongo,train=True)
        sql.rollback()
        with sql.cursor() as cursor:
            cursor.execute("SHOW TABLES")
            actual_tables={next(iter(row.values())) for row in cursor.fetchall()}
        forbidden={"recommendation_requests","recommendation_items","recommendation_events","recommendation_feedback",
                   "user_track_daily","content_metrics_windows","editorial_campaigns","recommendation_model_versions"}
        assert not (forbidden & actual_tables), "Recommendation must work without any new MySQL table"
        new_collections=set(mongo.list_collection_names())-{"tracks","playlists","artists","albums"}
        assert new_collections=={"recommendation_state","recommendation_events"}, new_collections
        checked.append("recommendations and worker use zero new MySQL tables and only two Mongo collections")
        assert mongo.recommendation_events.index_information()["rec_log_expiry"]["expireAfterSeconds"]==0
        old_snapshot=mongo.recommendation_state.find_one({"_id":version_id,"kind":"MIX_VERSION"})
        assert old_snapshot["expires_at"]>now
        active_mix=mongo.recommendation_state.find_one({"_id":mix_id,"kind":"MIX","user_id":1})
        current_snapshot=mongo.recommendation_state.find_one({"_id":active_mix["active_version_id"],"kind":"MIX_VERSION"})
        assert "expires_at" not in current_snapshot
        checked.append("transient logs and superseded versions have bounded retention")
        # Exercise backup/import/cleanup only inside this disposable fixture.
        with sql.cursor() as cursor:
            for name in compact_storage.TABLES:
                cursor.execute(f"CREATE TABLE `{name}` (id INT PRIMARY KEY)")
            cursor.execute("ALTER TABLE recommendation_items ADD request_id INT, ADD FOREIGN KEY(request_id) REFERENCES recommendation_requests(id)")
            cursor.execute("SELECT COUNT(*) n FROM users")
            business_users=cursor.fetchone()["n"]
        sql.commit()
        mongo.user_recommendation_profiles.insert_one({"_id":"2","user_id":2,"as_of":now,"collaborative_scores":{},"model_version":"legacy-fixture"})
        mongo.playlist_features.insert_one({"_id":"unused-fixture"})
        review=compact_storage.migrate(sql,mongo)
        assert review["mode"]=="read_only" and "user_track_daily" in review["legacy_sql_rows"]
        migrated=compact_storage.migrate(sql,mongo,apply=True,remove_legacy=True,backup_root=worker.ROOT/"target/recommendation-smoke-backups")
        assert migrated["removed_legacy"]
        assert Path(migrated["backup"],"snapshot.json").exists()
        assert mongo.recommendation_state.find_one({"_id":"profile:2"})["model_version"]=="legacy-fixture"
        with sql.cursor() as cursor:
            cursor.execute("SHOW TABLES")
            assert not (forbidden & {next(iter(row.values())) for row in cursor.fetchall()})
            cursor.execute("SELECT COUNT(*) n FROM users")
            assert cursor.fetchone()["n"]==business_users
        assert set(mongo.list_collection_names())-{"tracks","playlists","artists","albums"}=={"recommendation_state","recommendation_events"}
        checked.append("legacy cleanup verifies backup, preserves business data and removes only its allowlist")
        print(json.dumps({"passed":len(checked),"checks":checked,"disposable_database":database},ensure_ascii=False))
        hold_seconds = int(os.environ.get("RECOMMENDATION_SMOKE_HOLD_SECONDS", "0"))
        if hold_seconds > 0:
            mongo.playlists.update_one({"_id":"public-playlist"},{"$set":{"is_public":True}})
            print("Browser fixture ready on local backend port " + str(port),flush=True)
            time.sleep(min(hold_seconds,900))
    finally:
        if process is not None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        if log is not None:
            log.close()
        # Targets are exact generated disposable DBs, never the configured application DB.
        if re.fullmatch(r"moodify_recommendation_test_[a-f0-9]{12}",database):
            with sql.cursor() as cursor: cursor.execute(f"DROP DATABASE IF EXISTS `{database}`")
            client.drop_database(database)
        sql.close(); client.close()


if __name__=="__main__":
    run()
