"""Read-only local readiness audit; outputs aggregates without user/track identifiers.

Never calls initialize_storage, run_once or publish_profiles. SQL runs inside a
READ ONLY transaction. Mongo is queried only with find/count/index inspection.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

import numpy as np
import worker
from compact_storage import TABLES, COLLECTIONS


def distribution(values):
    values = sorted(float(v) for v in values if v is not None and math.isfinite(float(v)))
    return {} if not values else {"min": values[0], "p50": float(np.median(values)),
        "p90": float(np.percentile(values, 90)), "max": values[-1]}


def audit(sql, mongo, now):
    sql.rollback()
    with sql.cursor() as cursor:
        cursor.execute("START TRANSACTION READ ONLY")
        cursor.execute("SELECT @@transaction_isolation isolation_level, @@autocommit autocommit")
        session = cursor.fetchone()
        cursor.execute("SELECT role,status,COUNT(*) n FROM users GROUP BY role,status")
        accounts = cursor.fetchall()
        cursor.execute("SELECT id,track_id,status,issue_date,expiry_date FROM song_licenses")
        licenses = cursor.fetchall()
        cursor.execute("""SELECT h.user_id,h.track_id,h.started_at,h.listened_duration_ms,h.source,h.device_type,
            u.role,u.status user_status,
            EXISTS(SELECT 1 FROM revenue_playback_sessions r WHERE r.listening_history_id=h.id) is_revenue,
            EXISTS(SELECT 1 FROM playback_events p WHERE p.listening_history_id=h.id AND p.event_type='SKIP_NEXT') skipped
            FROM listening_history h LEFT JOIN users u ON u.id=h.user_id ORDER BY h.id LIMIT 1000001""")
        history = cursor.fetchall()
        if len(history) > 1_000_000:
            raise RuntimeError("Audit extraction limit reached; use a replica/incremental audit")
        cursor.execute("SELECT event_type,COUNT(*) n FROM playback_events GROUP BY event_type")
        playback_events = cursor.fetchall()
        cursor.execute("""SELECT l.user_id,l.track_id,l.added_at FROM user_library_tracks l
            JOIN users u ON u.id=l.user_id WHERE u.role='USER' AND u.status='ACTIVE'""")
        library = cursor.fetchall()
        cursor.execute("SHOW TABLES")
        tables = {next(iter(row.values())) for row in cursor.fetchall()}
        index_columns = {}
        for table in ("listening_history", "playback_events", "revenue_playback_sessions", "user_library_tracks"):
            cursor.execute(f"SHOW INDEX FROM `{table}`")
            indexes = defaultdict(list)
            for row in cursor.fetchall():
                indexes[row["Key_name"]].append((row["Seq_in_index"], row["Column_name"]))
            index_columns[table] = {name: [col for _, col in sorted(cols)] for name, cols in indexes.items()}

    tracks = list(mongo.tracks.find({}, {"spotify_id": 1, "status": 1, "visibility": 1,
        "moderation_status": 1, "local_path": 1, "duration_ms": 1, "genres": 1,
        "artist_spotify_id": 1, "audio_features": 1, "release_date": 1}))
    aliases = worker.canonical_tracks(tracks)  # Reject collisions rather than silently training.
    by_id = {str(t["_id"]): t for t in tracks}
    licensed = worker.effective_licensed_tracks(licenses, aliases, now.astimezone(ZoneInfo("Asia/Ho_Chi_Minh")).date())
    eligibility_keys = ("published", "public", "approved", "audio_path", "effective_license")
    failures = Counter({key: 0 for key in eligibility_keys})
    waterfall = Counter({key: 0 for key in eligibility_keys})
    eligible = set()
    states = Counter()
    feature_counts = Counter()
    audio_vectors = Counter()
    numeric_values = defaultdict(list)
    feature_value_counts = defaultdict(Counter)
    fields = ("energy", "valence", "danceability", "acousticness", "bpm")
    feature_counts.update({field + suffix: 0 for field in fields for suffix in ("_present", "_valid", "_invalid")})
    feature_counts.update({key: 0 for key in ("all_five_valid", "genres_present", "artist_id_present", "release_date_present")})
    genre_counts = Counter()
    for track in tracks:
        key = str(track["_id"])
        states[(track.get("status"), track.get("visibility"), track.get("moderation_status"))] += 1
        checks = [("published", str(track.get("status", "")).lower() == "published"),
                  ("public", str(track.get("visibility", "")).lower() == "public"),
                  ("approved", str(track.get("moderation_status", "")).lower() == "approved"),
                  ("audio_path", bool(str(track.get("local_path") or "").strip())),
                  ("effective_license", key in licensed)]
        passed = True
        for name, ok in checks:
            if not ok:
                failures[name] += 1
            passed = passed and ok
            if passed:
                waterfall[name] += 1
        if passed:
            eligible.add(key)
        audio = track.get("audio_features") or {}
        vector = []
        for field in fields:
            value = audio.get(field)
            numeric = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
            valid = numeric and (value > 0 if field == "bpm" else 0 <= value <= 1)
            if value is not None:
                feature_counts[field + "_present"] += 1
            if valid:
                feature_counts[field + "_valid"] += 1
                numeric_values[field].append(value)
                feature_value_counts[field][value] += 1
            elif value is not None:
                feature_counts[field + "_invalid"] += 1
            vector.append(float(value) if valid else None)
        if all(v is not None for v in vector):
            feature_counts["all_five_valid"] += 1
            audio_vectors[tuple(vector)] += 1
        if track.get("genres"):
            feature_counts["genres_present"] += 1
            genre_counts.update(set(track["genres"]))
        if track.get("artist_spotify_id"):
            feature_counts["artist_id_present"] += 1
        if track.get("release_date"):
            feature_counts["release_date_present"] += 1

    playlists = list(mongo.playlists.find({}, {"is_public": 1, "track_spotify_ids": 1, "tracks": 1}))
    playlist_stats = Counter({key: 0 for key in ("public", "private", "unspecified", "track_references",
        "resolved_references", "eligible_references", "legacy_tracks_array_elements", "legacy_references_resolvable_for_review")})
    for playlist in playlists:
        visibility = "public" if playlist.get("is_public") is True else "private" if playlist.get("is_public") is False else "unspecified"
        playlist_stats[visibility] += 1
        ids = playlist.get("track_spotify_ids") or []
        playlist_stats["track_references"] += len(ids)
        playlist_stats["resolved_references"] += sum(key in aliases for key in ids)
        playlist_stats["eligible_references"] += sum(aliases.get(key) in eligible for key in ids)
        legacy = playlist.get("tracks") or []
        if isinstance(legacy, list):
            playlist_stats["legacy_tracks_array_elements"] += len(legacy)
            for element in legacy:
                candidate = element if isinstance(element, str) else None
                if isinstance(element, dict):
                    candidate = element.get("spotify_id") or element.get("id") or element.get("_id")
                    if not candidate and isinstance(element.get("track"), dict):
                        candidate = element["track"].get("id")
                playlist_stats["legacy_references_resolvable_for_review"] += int(isinstance(candidate,str) and candidate in aliases)

    legacy_zone = os.environ.get("RECOMMENDATIONS_LEGACY_TIMEZONE", "Asia/Ho_Chi_Minh")
    user_history = [row for row in history if row.get("role") == "USER"]
    hist_counts = Counter()
    per_user = Counter()
    per_pair = Counter()
    per_track_users = defaultdict(set)
    starts, normalized = [], []
    active_by_window = {str(days): set() for days in (1, 2, 7, 30, 180)}
    engaged_users = {"recent": set(), "previous": set()}
    trend_users = defaultdict(lambda: {"recent": set(), "previous": set()})
    for row in user_history:
        canonical = aliases.get(row["track_id"])
        hist_counts["resolved_track_rows"] += int(canonical is not None)
        hist_counts["eligible_track_rows"] += int(canonical in eligible)
        hist_counts["revenue_session_rows"] += int(bool(row["is_revenue"]))
        hist_counts["active_account_rows"] += int(row["user_status"] == "ACTIVE")
        duration = int(row.get("listened_duration_ms") or 0)
        hist_counts["zero_duration_rows"] += int(duration == 0)
        hist_counts["negative_duration_rows"] += int(duration < 0)
        hist_counts["under_5s_rows"] += int(0 <= duration < 5000)
        hist_counts["engaged_30s_rows"] += int(duration >= 30000)
        hist_counts["early_skip_rows"] += int(bool(row["skipped"]) and duration < 15000)
        if canonical and by_id[canonical].get("duration_ms"):
            hist_counts["listened_over_track_duration_rows"] += int(duration > by_id[canonical]["duration_ms"] + 2000)
        per_user[row["user_id"]] += 1
        if canonical:
            per_pair[(row["user_id"], canonical)] += 1
            per_track_users[canonical].add(row["user_id"])
        if not row.get("started_at"):
            hist_counts["invalid_timestamp_rows"] += 1
            continue
        at = worker.timestamp(row["started_at"], "UTC" if row["is_revenue"] else legacy_zone)
        starts.append(at)
        hist_counts["future_timestamp_rows"] += int(at > now)
        if at <= now:
            normalized.append((row, at))
            for days in (1, 2, 7, 30, 180):
                if at >= now - timedelta(days=days):
                    active_by_window[str(days)].add(row["user_id"])
            if at >= now - timedelta(days=2) and row["user_status"] == "ACTIVE":
                period = "recent" if at >= now - timedelta(days=1) else "previous"
                engaged_users[period].add(row["user_id"])
                if canonical and duration >= 30000:
                    trend_users[canonical][period].add(row["user_id"])

    cutoff = (now - timedelta(days=181)).replace(tzinfo=None)
    # Match worker's SQL extraction cutoff, followed by the exact in-memory filtering.
    training_history = [row for row in user_history if row["user_status"] == "ACTIVE" and row.get("started_at") and row["started_at"] >= cutoff]
    daily = worker.aggregate_daily(training_history, library, aliases, now, legacy_zone)
    matrix, users, items = worker.interaction_matrix(daily, now)
    eval_cutoff = (now - timedelta(days=7)).replace(hour=0, minute=0, second=0, microsecond=0)
    eval_matrix, eval_users, eval_items = worker.interaction_matrix(daily, eval_cutoff)
    positive_pairs = int(np.count_nonzero(matrix))
    source_counts = Counter(row.get("source") or "UNSPECIFIED" for row in user_history)
    role_counts = Counter(row.get("role") or "NO_MATCHING_USER" for row in history)
    source_purity = max(source_counts.values(), default=0) / max(1, len(user_history))
    collection_names = set(mongo.list_collection_names())
    state_counts, log_counts, model_counts = Counter(), Counter(), Counter()
    if "recommendation_state" in collection_names:
        for d in mongo.recommendation_state.find({}, {"kind": 1, "model_version": 1, "collaborative_scores": 1}):
            state_counts[d.get("kind") or "UNSPECIFIED"] += 1
            if d.get("kind") == "PROFILE":
                model_counts[d.get("model_version") or "UNSPECIFIED"] += 1
    if "recommendation_events" in collection_names:
        for d in mongo.recommendation_events.find({}, {"kind": 1, "event_type": 1}):
            log_counts[d.get("event_type") or d.get("kind") or "UNSPECIFIED"] += 1

    field_presence = {}
    for name in ("tracks", "playlists"):
        field_presence[name] = {row["_id"]: row["n"] for row in mongo[name].aggregate([
            {"$project":{"fields":{"$objectToArray":"$$ROOT"}}}, {"$unwind":"$fields"},
            {"$group":{"_id":"$fields.k","n":{"$sum":1}}}])}
    return {"generated_at_utc": now.isoformat(), "source_scope": "configured local application databases; not a production audit",
        "read_only": True, "sql_session": session, "legacy_timezone_assumption": legacy_zone,
        "catalog": {"tracks": len(tracks), "strictly_eligible_tracks": len(eligible), "effective_licensed_tracks": len(licensed),
            "eligibility_waterfall_order": ["published", "public", "approved", "audio_path", "effective_license"],
            "eligibility_waterfall_remaining": dict(waterfall), "nonexclusive_failed_checks": dict(failures),
            "state_combinations": [{"status": s, "visibility": v, "moderation": m, "count": n} for (s,v,m),n in states.items()],
            "feature_counts": dict(feature_counts), "feature_distributions": {k: distribution(v) for k,v in numeric_values.items()},
            "feature_common_values": {k:[{"value":value,"count":n} for value,n in counts.most_common(3)] for k,counts in feature_value_counts.items()},
            "unique_complete_audio_vectors": len(audio_vectors), "largest_identical_audio_vector_group": max(audio_vectors.values(), default=0),
            "genre_label_distribution": dict(genre_counts.most_common(15)),
            "license_status_counts": dict(Counter(row["status"] for row in licenses)),
            "effective_licensed_track_state_combinations": [{"status":s,"visibility":v,"moderation":m,"count":n}
                for (s,v,m),n in Counter((by_id[key].get("status"),by_id[key].get("visibility"),by_id[key].get("moderation_status")) for key in licensed).items()],
            "license_rows_unresolved_to_catalog": sum(row["track_id"] not in aliases for row in licenses)},
        "accounts": {"by_role_and_status": accounts, "user_role_accounts": sum(row["n"] for row in accounts if row["role"] == "USER"),
            "proven_human_production_users": None},
        "playlists": {"total": len(playlists), **dict(playlist_stats)},
        "history": {"all_rows": len(history), "by_account_role": dict(role_counts), "user_role_rows": len(user_history),
            **dict(hist_counts), "distinct_user_role_listeners": len(per_user), "distinct_canonical_tracks": len(per_track_users),
            "per_listener_rows": distribution(per_user.values()), "per_user_track_rows": distribution(per_pair.values()),
            "per_track_unique_listeners": distribution([len(v) for v in per_track_users.values()]),
            "normalized_first_at_utc": min(starts).isoformat() if starts else None,
            "normalized_last_at_utc": max(starts).isoformat() if starts else None,
            "distinct_activity_days": len({at.date() for _,at in normalized}),
            "active_users_by_window_days": {days: len(ids) for days,ids in active_by_window.items()},
            "source_counts_user_role": dict(source_counts), "largest_source_fraction": source_purity,
            "device_counts_user_role": dict(Counter(row.get("device_type") or "UNSPECIFIED" for row in user_history)),
            "playback_event_counts_all_roles": playback_events},
        "training": {"population": "USER ACTIVE", "extracted_user_history_rows": len(training_history), "user_library_rows": len(library),
            "unresolved_library_rows": sum(row["track_id"] not in aliases for row in library), "daily_buckets_in_memory": len(daily),
            "matrix_users": len(users), "matrix_tracks": len(items), "positive_user_track_pairs": positive_pairs,
            "matrix_cells": int(matrix.size), "matrix_density": positive_pairs / matrix.size if matrix.size else 0,
            "tracks_shared_by_multiple_users": int(np.sum(np.count_nonzero(matrix,axis=0)>=2)) if matrix.size else 0,
            "trained_item_coverage_of_catalog": len(items) / len(tracks) if tracks else 0,
            "evaluation_cutoff_utc": eval_cutoff.isoformat(), "evaluation_train_users": len(eval_users),
            "evaluation_train_tracks": len(eval_items), "evaluation_train_positive_pairs": int(np.count_nonzero(eval_matrix)),
            "existing_sanity_gate_passes": len(eval_users) >= 5 and len(eval_items) >= 5 and np.count_nonzero(eval_matrix) >= 30},
        "trend": {"active_users_recent_24h": len(engaged_users["recent"]), "active_users_previous_24h": len(engaged_users["previous"]),
            "tracks_with_at_least_3_engaged_users_recent_24h": sum(len(v["recent"]) >= 3 for v in trend_users.values()),
            "note": "Audit and Java v1.1 merge aliases before distinct listener counting and use USER ACTIVE."},
        "recommendation_storage": {"state_kind_counts": dict(state_counts), "log_counts": dict(log_counts),
            "profile_model_counts": dict(model_counts), "legacy_sql_tables_remaining": sorted(tables & set(TABLES)),
            "legacy_mongo_collections_remaining": sorted(collection_names & set(COLLECTIONS))},
        "existing_sql_indexes": index_columns, "mongo_field_presence_counts": field_presence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, default=worker.ROOT / ".env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sql, mongo, client = worker.connections(args.env)
    try:
        parsed = urlparse(os.environ["DATABASE_SOURCE_URL"].removeprefix("jdbc:"))
        if parsed.hostname not in {"localhost", "127.0.0.1", "::1"} or client.address[0] not in {"localhost", "127.0.0.1", "::1"}:
            raise RuntimeError("This audit is scoped to the reviewed local application databases")
        result = audit(sql, mongo, datetime.now(timezone.utc))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(json.dumps({"audit_output": str(args.output), "catalog_tracks": result["catalog"]["tracks"],
            "eligible_tracks": result["catalog"]["strictly_eligible_tracks"], "user_history_rows": result["history"]["user_role_rows"],
            "matrix_users": result["training"]["matrix_users"], "matrix_tracks": result["training"]["matrix_tracks"]}))
    finally:
        sql.rollback()
        sql.close()
        client.close()


if __name__ == "__main__":
    main()
