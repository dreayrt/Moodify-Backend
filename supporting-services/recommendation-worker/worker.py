"""Small-catalog recommendation jobs. Never calls the emotion-search model.

SQL is the interaction source; MongoDB holds serving projections. ALS artifacts are
published only after a chronological holdout beats a popularity baseline. Missing
data produces a content-only serving profile, never synthetic interactions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse, unquote
from zoneinfo import ZoneInfo

import numpy as np

UTC = timezone.utc
ROOT = Path(__file__).resolve().parents[2]
FEATURE_VERSION = "behavior-v1"


def timestamp(value, legacy_zone="UTC"):
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if value.tzinfo is None:
        value = value.replace(tzinfo=ZoneInfo(legacy_zone))
    return value.astimezone(UTC)


def canonical_tracks(tracks):
    # Internal IDs always win, independent of input order (same policy as Java).
    canonical = {str(track["_id"]) for track in tracks}
    aliases = {key:key for key in canonical}
    for track in tracks:
        key = str(track["_id"])
        external = track.get("spotify_id")
        if not external or external in canonical:
            continue
        if external in aliases and aliases[external] != key:
            raise ValueError("Conflicting catalog aliases; repair IDs before training")
        aliases[external] = key
    return aliases


def effective_licensed_tracks(rows, aliases, local_day):
    selected = {}
    for row in rows:
        key = aliases.get(row["track_id"])
        if key is None:
            continue
        old = selected.get(key)
        priority = (row["track_id"] == key, int(row["id"]))
        if old is None or priority > (old["track_id"] == key, int(old["id"])):
            selected[key] = row
    return {key for key, row in selected.items() if row["status"]=="ACTIVE"
            and (row.get("issue_date") is None or row["issue_date"] <= local_day)
            and (row.get("expiry_date") is None or row["expiry_date"] >= local_day)}


def aggregate_daily(history, library, aliases, now, legacy_zone="Asia/Ho_Chi_Minh"):
    result = {}
    for row in history:
        key = aliases.get(row["track_id"])
        if key is None:
            continue
        at = timestamp(row["started_at"], "UTC" if row.get("is_revenue") else legacy_zone)
        if at > now or at < now - timedelta(days=180):
            continue
        duration = max(0, int(row.get("listened_duration_ms") or 0))
        daily_key = (int(row["user_id"]), key, at.date())
        bucket = result.setdefault(daily_key, {"valid_ms": 0, "engaged": 0, "skips": 0, "saves": 0, "last": at})
        # Bounded per-listener/day contribution; no heartbeat becomes an independent example.
        bucket["valid_ms"] = min(3_600_000, bucket["valid_ms"] + min(duration, 900_000))
        bucket["engaged"] = min(5, bucket["engaged"] + int(duration >= 30_000))
        bucket["skips"] = min(5, bucket["skips"] + int(bool(row.get("skipped")) and duration < 15_000))
        bucket["last"] = max(bucket["last"], at)
    for row in library:
        key = aliases.get(row["track_id"])
        if key is None:
            continue
        at = timestamp(row["added_at"], legacy_zone)
        if at > now:
            continue
        daily_key = (int(row["user_id"]), key, at.date())
        bucket = result.setdefault(daily_key, {"valid_ms": 0, "engaged": 0, "skips": 0, "saves": 0, "last": at})
        bucket["saves"] = 1
    return result


def interaction_matrix(daily, cutoff, days=180):
    observations = defaultdict(float)
    for (user, track, day), values in daily.items():
        # Whole-day aggregates before cutoff only: never include a partially future day.
        if day >= cutoff.date() or day < (cutoff - timedelta(days=days)).date():
            continue
        age = (cutoff.date() - day).days
        weight = (min(5, values["engaged"]) + 4 * values["saves"]) * 2 ** (-age / 45)
        if weight > 0:
            observations[(user, track)] += weight
    users = sorted({u for u, _ in observations})
    tracks = sorted({t for _, t in observations})
    user_index = {u: i for i, u in enumerate(users)}
    track_index = {t: i for i, t in enumerate(tracks)}
    if len(users) * len(tracks) > 2_000_000:
        raise ValueError("Dense ALS capacity exceeded before allocation; deploy sparse training")
    matrix = np.zeros((len(users), len(tracks)), dtype=np.float64)
    for (user, track), weight in observations.items():
        matrix[user_index[user], track_index[track]] = min(weight, 30)
    return matrix, users, tracks


def fit_implicit_als(matrix, factors=16, iterations=12, regularization=0.2, alpha=8, seed=42):
    """Confidence-weighted implicit ALS; unknown pairs retain low-confidence prior.

    Uses dense solves intentionally for the bounded small-catalog deployment.
    No signed sum of skip/positive labels is passed into the confidence matrix.
    """
    if matrix.ndim != 2 or not matrix.size or np.any(matrix < 0) or not np.all(np.isfinite(matrix)):
        raise ValueError("ALS requires a finite nonnegative interaction matrix")
    factors = min(factors, max(1, min(matrix.shape)))
    rng = np.random.default_rng(seed)
    user_factors = rng.normal(0, 0.05, (matrix.shape[0], factors))
    item_factors = rng.normal(0, 0.05, (matrix.shape[1], factors))
    confidence = 1 + alpha * np.log1p(matrix)
    preference = (matrix > 0).astype(float)
    ridge = regularization * np.eye(factors)
    for _ in range(iterations):
        for u in range(matrix.shape[0]):
            c = confidence[u]
            user_factors[u] = np.linalg.solve(item_factors.T @ (item_factors * c[:, None]) + ridge,
                                              item_factors.T @ (c * preference[u]))
        for i in range(matrix.shape[1]):
            c = confidence[:, i]
            item_factors[i] = np.linalg.solve(user_factors.T @ (user_factors * c[:, None]) + ridge,
                                              user_factors.T @ (c * preference[:, i]))
    return user_factors, item_factors


def ndcg_at_k(ranking, relevant, k=10):
    if not relevant:
        return 0.0
    dcg = sum(1 / math.log2(position + 2) for position, item in enumerate(ranking[:k]) if item in relevant)
    ideal = sum(1 / math.log2(position + 2) for position in range(min(k, len(relevant))))
    return dcg / ideal if ideal else 0.0


def evaluate(daily, now, min_evaluation_users=5):
    train_cutoff = (now - timedelta(days=7)).replace(hour=0, minute=0, second=0, microsecond=0)
    matrix, users, tracks = interaction_matrix(daily, train_cutoff)
    evidence = {"train_cutoff": train_cutoff.isoformat(), "evaluation_users": 0, "accepted": False}
    if len(users) < 5 or len(tracks) < 5 or np.count_nonzero(matrix) < 30:
        return evidence
    if matrix.size > 2_000_000:
        raise ValueError("Dense ALS capacity exceeded; deploy sparse training before increasing this limit")
    user_factors, item_factors = fit_implicit_als(matrix)
    scores = user_factors @ item_factors.T
    track_indices = {track: i for i, track in enumerate(tracks)}
    user_indices = {user: i for i, user in enumerate(users)}
    holdout = defaultdict(set)
    for (user, track, day), values in daily.items():
        if train_cutoff.date() <= day < now.date() and (values["engaged"] or values["saves"]):
            if user in user_indices and track in track_indices:
                holdout[user].add(track_indices[track])
    popular = np.argsort(-np.count_nonzero(matrix, axis=0), kind="stable").tolist()
    als_ndcg, baseline_ndcg = [], []
    for user, relevant in holdout.items():
        index = user_indices[user]
        ranking = np.argsort(-scores[index], kind="stable").tolist()
        # Repeat listening is permitted for music; evaluate both rankers over the same full trained-item set.
        als_ndcg.append(ndcg_at_k(ranking, relevant))
        baseline_ndcg.append(ndcg_at_k(popular, relevant))
    if als_ndcg:
        als_mean, baseline_mean = float(np.mean(als_ndcg)), float(np.mean(baseline_ndcg))
        evidence.update(evaluation_users=len(als_ndcg), als_ndcg_at_10=als_mean,
                        popularity_ndcg_at_10=baseline_mean,
                        accepted=len(als_ndcg) >= min_evaluation_users and als_mean > baseline_mean + 0.005)
    return evidence


def connections(env_path):
    from dotenv import load_dotenv
    import pymysql
    from pymongo import MongoClient
    load_dotenv(env_path, override=False)
    parsed = urlparse(os.environ["DATABASE_SOURCE_URL"].removeprefix("jdbc:"))
    sql = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306,
                          user=os.environ["DATABASE_USERNAME"], password=os.environ["DATABASE_PASSWORD"],
                          database=unquote(parsed.path.lstrip("/")), charset="utf8mb4",
                          cursorclass=pymysql.cursors.DictCursor, connect_timeout=8,
                          init_command="SET time_zone = '+00:00'")
    client = MongoClient(os.environ["MONGODB_URI"], tz_aware=True, serverSelectionTimeoutMS=8000)
    mongo = client[os.environ.get("MONGODB_DATABASE", "music_streaming")]
    return sql, mongo, client


def sql_statements(source):
    """Split DDL while preserving quoted semicolons and ignoring SQL line comments."""
    statements, buffer = [], []
    quote = None
    index = 0
    while index < len(source):
        char = source[index]
        if quote:
            buffer.append(char)
            if char == "\\" and index + 1 < len(source):
                index += 1
                buffer.append(source[index])
            elif char == quote:
                if index + 1 < len(source) and source[index + 1] == quote:
                    index += 1
                    buffer.append(source[index])
                else:
                    quote = None
        elif source[index:index+2] == "--" and (index+2 == len(source) or source[index+2].isspace()):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
            buffer.append("\n")
        elif char in ("'", '"', '`'):
            quote = char
            buffer.append(char)
        elif char == ";":
            statement = "".join(buffer).strip()
            if statement:
                statements.append(statement)
            buffer = []
        else:
            buffer.append(char)
        index += 1
    if quote:
        raise ValueError("Unterminated SQL string")
    final = "".join(buffer).strip()
    if final:
        statements.append(final)
    return statements


def initialize_storage(mongo):
    """Only two new collections; indexes match the Java serving application."""
    mongo.recommendation_state.create_index([("kind", 1), ("user_id", 1)], name="rec_state_user")
    mongo.recommendation_state.create_index([("kind", 1), ("status", 1), ("starts_at_utc", 1)], name="rec_state_campaign")
    mongo.recommendation_state.create_index([("kind", 1), ("refresh_due_at", 1)], name="rec_state_refresh")
    mongo.recommendation_state.create_index("expires_at", name="rec_state_expiry", expireAfterSeconds=0)
    mongo.recommendation_events.create_index([("kind", 1), ("user_id", 1), ("occurred_at", -1)], name="rec_log_user")
    mongo.recommendation_events.create_index("expires_at", name="rec_log_expiry", expireAfterSeconds=0)


def load_sources(sql, mongo, now):
    tracks = list(mongo.tracks.find({}, {"spotify_id": 1, "genres": 1, "artist_spotify_id": 1, "audio_features": 1}))
    aliases = canonical_tracks(tracks)
    # A batch owns a short read-only snapshot. Session advisory locks survive rollback.
    sql.rollback()
    try:
        with sql.cursor() as transaction:
            transaction.execute("START TRANSACTION READ ONLY")
        with sql.cursor() as cursor:
            cursor.execute("""SELECT h.id,h.user_id,h.track_id,h.started_at,h.listened_duration_ms,h.source,h.source_id,
                EXISTS(SELECT 1 FROM revenue_playback_sessions r WHERE r.listening_history_id=h.id) is_revenue,
                EXISTS(SELECT 1 FROM playback_events p WHERE p.listening_history_id=h.id AND p.event_type='SKIP_NEXT') skipped
                FROM listening_history h JOIN users u ON u.id=h.user_id
                WHERE u.role='USER' AND u.status='ACTIVE' AND h.started_at >= %s ORDER BY h.id LIMIT 1000001""",
                ((now - timedelta(days=181)).replace(tzinfo=None),))
            history = cursor.fetchall()
            if len(history) > 1_000_000:
                raise ValueError("History capacity exceeded; use incremental extraction")
            cursor.execute("""SELECT l.user_id,l.track_id,l.added_at FROM user_library_tracks l
                              JOIN users u ON u.id=l.user_id WHERE u.role='USER' AND u.status='ACTIVE'""")
            library = cursor.fetchall()
    finally:
        sql.rollback()

    return tracks, aliases, history, library


def normalize_top_scores(row, tracks, limit=500):
    """Bound the per-user serving document independently of the training catalog size."""
    if not len(row):
        return {}
    if not np.all(np.isfinite(row)):
        raise ValueError("Cannot publish non-finite recommendation scores")
    low, high = float(row.min()), float(row.max())
    indices = sorted(range(len(tracks)), key=lambda i: (-row[i], tracks[i]))[:limit]
    return {tracks[i]: float((row[i]-low)/(high-low)) if high > low else 0.0 for i in indices}


def publish_profiles(mongo, daily, now, train, artifact_dir):
    if not train:
        observed = [(user, track) for (user, track, day), value in daily.items()
                    if (now-timedelta(days=180)).date() <= day < now.date() and (value["engaged"] or value["saves"])]
        return {"users":len({u for u,_ in observed}),"tracks":len({t for _,t in observed}),
                "training":{"accepted":False,"reason":"training_not_requested"}}
    matrix, users, tracks = interaction_matrix(daily, now)
    evidence = evaluate(daily, now) if train else {"accepted": False, "reason": "training_not_requested"}
    scores = None
    version = "content-session-v1"
    if train and evidence.get("accepted"):
        if matrix.size > 2_000_000:
            raise ValueError("Dense ALS capacity exceeded")
        user_factors, item_factors = fit_implicit_als(matrix)
        scores = user_factors @ item_factors.T
        version = "implicit-als-" + now.strftime("%Y%m%dT%H%M%S%fZ")
        artifact_dir.mkdir(parents=True, exist_ok=True)
        artifact = artifact_dir / (version + ".npz")
        temporary = artifact.with_suffix(".tmp.npz")
        np.savez_compressed(temporary, user_factors=user_factors, item_factors=item_factors,
                            user_ids=np.array(users), track_ids=np.array(tracks))
        temporary.replace(artifact)
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        mapping_digest = hashlib.sha256(json.dumps({"users": users, "tracks": tracks}, sort_keys=True).encode()).hexdigest()
        manifest = {"version": version, "feature_schema_version": FEATURE_VERSION, "train_cutoff": now.isoformat(),
                    "artifact_sha256": digest, "mapping_sha256": mapping_digest, "evaluation": evidence,
                    "config": {"factors": 16, "iterations": 12, "regularization": 0.2, "alpha": 8, "seed": 42}}
        (artifact_dir / (version + ".json")).write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    # Profiles are complete per-user documents, not a second source of raw listening history.
    # Non-training aggregation runs preserve an existing learned projection until its 3-day serving TTL.
    kept_profiles = 0
    for index, user in enumerate(users):
        existing = mongo.recommendation_state.find_one({"_id":"profile:"+str(user),"kind":"PROFILE"})
        if scores is None and existing and existing.get("collaborative_scores") and existing.get("as_of") and timestamp(existing["as_of"]) > now-timedelta(days=3):
            kept_profiles += 1
            continue
        normalized_scores = {}
        if scores is not None:
            normalized_scores = normalize_top_scores(scores[index], tracks)
        mongo.recommendation_state.replace_one({"_id": "profile:"+str(user)},
            {"_id": "profile:"+str(user), "kind":"PROFILE", "user_id": user, "as_of": now, "feature_schema_version": FEATURE_VERSION,
             "model_version": version, "collaborative_scores": normalized_scores,
             "interaction_count": int(np.count_nonzero(matrix[index])), "training_evaluation": evidence,
             "expires_at":now+timedelta(days=3)}, upsert=True)
    return {"users": len(users), "tracks": len(tracks), "training": evidence, "model_version": version,
            "previous_learned_profiles_kept":kept_profiles}


def run_once(sql, mongo, train=False, artifact_dir=None):
    initialize_storage(mongo)
    now = datetime.now(UTC)
    legacy_zone = os.environ.get("RECOMMENDATIONS_LEGACY_TIMEZONE", "Asia/Ho_Chi_Minh")
    tracks, aliases, history, library = load_sources(sql, mongo, now)
    daily = aggregate_daily(history, library, aliases, now, legacy_zone)
    # Daily confidence stays in memory; runtime trends read existing listening_history.
    summary = publish_profiles(mongo, daily, now, train, artifact_dir or ROOT / "storage/recommendation-models")
    summary.update(as_of=now.isoformat(), history_rows=len(history), daily_rows=len(daily))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, default=ROOT / ".env")
    parser.add_argument("--init-storage", action="store_true", help="Initialize indexes for the two Mongo recommendation collections; no SQL DDL")
    parser.add_argument("--train", action="store_true", help="Evaluate ALS chronologically before considering publication")
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=int, default=900)
    parser.add_argument("--train-interval-hours", type=int, default=24)
    parser.add_argument("--audit", action="store_true", help="Read-only aggregate counts, no jobs or schema writes")
    args = parser.parse_args()
    sql, mongo, client = connections(args.env)
    try:
        if args.audit:
            with sql.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) count FROM listening_history")
                history_count = cursor.fetchone()["count"]
                cursor.execute("SELECT COUNT(*) count FROM users WHERE role='USER'")
                user_count = cursor.fetchone()["count"]
                cursor.execute("SELECT status,COUNT(*) count FROM song_licenses GROUP BY status")
                licenses = cursor.fetchall()
                local_day = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
                cursor.execute("SELECT id,track_id,status,issue_date,expiry_date FROM song_licenses")
                license_rows = cursor.fetchall()
            from collections import Counter
            catalog_metadata = list(mongo.tracks.find({}, {"spotify_id":1,"status": 1, "visibility": 1, "moderation_status": 1, "local_path": 1}))
            licensed_tracks = effective_licensed_tracks(license_rows, canonical_tracks(catalog_metadata), local_day)
            states = Counter((track.get("status"), track.get("visibility"), track.get("moderation_status"))
                             for track in catalog_metadata)
            eligible_count = sum(str(track["_id"]) in licensed_tracks and bool(track.get("local_path"))
                and str(track.get("status", "")).lower()=="published" and str(track.get("visibility", "")).lower()=="public"
                and str(track.get("moderation_status", "")).lower()=="approved" for track in catalog_metadata)
            print(json.dumps({"tracks": mongo.tracks.count_documents({}), "public_playlists": mongo.playlists.count_documents({"is_public": True}),
                              "listener_accounts": user_count, "listening_history_rows": history_count,
                              "strictly_eligible_tracks": eligible_count,
                              "catalog_states": [{"status": key[0], "visibility": key[1], "moderation": key[2], "count": count}
                                                 for key, count in states.items()], "licenses": licenses}))
            return
        if args.init_storage:
            initialize_storage(mongo)
        # Cross-process lock: prevents two workers from publishing overlapping snapshots.
        with sql.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('moodify_recommendation_worker_v1',0) acquired")
            if cursor.fetchone()["acquired"] != 1:
                raise RuntimeError("Another recommendation worker is already running")
        last_training = None
        while True:
            sql.ping(reconnect=False)
            should_train = args.train and (last_training is None or time.monotonic() - last_training >= max(1,args.train_interval_hours)*3600)
            print(json.dumps(run_once(sql, mongo, should_train), default=str), flush=True)
            if should_train:
                last_training = time.monotonic()
            if not args.watch:
                break
            time.sleep(max(60, args.interval))
    finally:
        sql.close()
        client.close()


if __name__ == "__main__":
    main()
