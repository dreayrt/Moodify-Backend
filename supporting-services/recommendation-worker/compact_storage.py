"""Review/migrate only the recommendation objects created by the earlier implementation.

Default is read-only. --apply backs up and imports state; --remove-legacy also
deletes the eight legacy SQL tables/four Mongo collections after verification.
Stop application writers before an explicit migration/cleanup. Never imports or
deletes business tables, tracks, users, licenses, listening history or playlists.
"""
from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import uuid

from bson import json_util
import worker

TABLES = ("recommendation_items", "recommendation_requests", "recommendation_events",
          "recommendation_feedback", "user_track_daily", "content_metrics_windows",
          "editorial_campaigns", "recommendation_model_versions")
COLLECTIONS = ("user_recommendation_profiles", "playlist_features", "personalized_playlists",
               "personalized_playlist_versions")


def snapshot(sql, mongo):
    result = {"sql": {}, "mongo": {}}
    with sql.cursor() as cursor:
        cursor.execute("SHOW TABLES")
        existing = {next(iter(row.values())) for row in cursor.fetchall()}
        for name in TABLES:
            if name not in existing:
                continue
            cursor.execute(f"SHOW CREATE TABLE `{name}`")
            ddl = next(value for key, value in cursor.fetchone().items() if key.lower() == "create table")
            cursor.execute(f"SELECT * FROM `{name}` LIMIT 1000001")
            rows = cursor.fetchall()
            if len(rows) > 1_000_000:
                raise RuntimeError("Legacy backup too large; use an offline database export")
            result["sql"][name] = {"ddl": ddl, "rows": rows}
    existing_collections = set(mongo.list_collection_names())
    for name in COLLECTIONS:
        if name in existing_collections:
            docs = list(mongo[name].find({}).limit(1_000_001))
            if len(docs) > 1_000_000:
                raise RuntimeError("Legacy backup too large; use mongodump")
            result["mongo"][name] = docs
    return result


def encode_dates(value):
    # BSON supports datetime but not the SQL DATE type; retain its type explicitly in backups.
    if isinstance(value, datetime):
        return value
    if isinstance(value, date):
        return {"$sqlDate": value.isoformat()}
    if isinstance(value, dict):
        return {key: encode_dates(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [encode_dates(item) for item in value]
    return value


def fingerprint(source):
    # Stable across database scan order; preserves BSON types in the backup.
    normalized = {"sql": {}, "mongo": {}}
    for name, table in source["sql"].items():
        normalized["sql"][name] = {"ddl": table["ddl"],
            "rows": sorted(json_util.dumps(encode_dates(row), sort_keys=True) for row in table["rows"])}
    for name, documents in source["mongo"].items():
        normalized["mongo"][name] = sorted(json_util.dumps(d, sort_keys=True) for d in documents)
    return hashlib.sha256(json.dumps(normalized, sort_keys=True).encode()).hexdigest()


def parse(value):
    return json.loads(value) if isinstance(value, str) else value


def documents(source, now):
    state, events = [], []
    old = source["mongo"]
    for raw in old.get("user_recommendation_profiles", []):
        d = dict(raw)
        d.update(_id="profile:" + str(d["user_id"]), kind="PROFILE")
        if d.get("as_of"):
            d["expires_at"] = worker.timestamp(d["as_of"]) + timedelta(days=3)
        state.append(d)
    active_versions = {d.get("active_version_id") for d in old.get("personalized_playlists", [])}
    for raw in old.get("personalized_playlists", []):
        state.append({**raw, "kind": "MIX"})
    for raw in old.get("personalized_playlist_versions", []):
        d = {**raw, "kind": "MIX_VERSION"}
        if d["_id"] not in active_versions:
            d["expires_at"] = now + timedelta(days=7)
        state.append(d)
    def rows(name):
        return source["sql"].get(name, {}).get("rows", [])
    for row in rows("recommendation_feedback"):
        seed = f'{row["user_id"]}:{row["entity_type"]}:{row["entity_id"]}:{row["feedback_type"]}'
        key = str(uuid.UUID(bytes=hashlib.md5(seed.encode()).digest(), version=3))
        state.append({**row, "_id": "feedback:" + key, "kind": "FEEDBACK"})
    for row in rows("editorial_campaigns"):
        targets = parse(row["content_targets_json"])
        state.append({"_id": "campaign:" + row["campaign_id"], "kind": "CAMPAIGN",
            **{key: row[key] for key in ("campaign_id", "name", "status", "revision", "timezone", "priority", "updated_at")},
            "starts_at_utc": worker.timestamp(row["starts_at_utc"]), "ends_at_utc": worker.timestamp(row["ends_at_utc"]),
            "genres": parse(row["audience_json"]),
            "targets": [{"entity_type": t.get("entityType", t.get("entity_type")),
                         "entity_id": t.get("entityId", t.get("entity_id"))} for t in targets]})
    items = {}
    for row in rows("recommendation_items"):
        items.setdefault(row["request_id"], []).append({key: value for key, value in row.items()
                                                       if key not in {"request_id", "sources_json"}})
    for row in rows("recommendation_requests"):
        created = worker.timestamp(row["created_at"])
        if created + timedelta(days=7) <= now:
            continue
        events.append({"_id": "decision:" + row["request_id"], "kind": "DECISION", "request_id": row["request_id"],
            "user_id": row["user_id"], "model_version": row["model_version"], "policy_version": row["policy_version"],
            "items": items.get(row["request_id"], []), "occurred_at": created, "expires_at": created + timedelta(days=7)})
    for row in rows("recommendation_events"):
        received = worker.timestamp(row["received_at"])
        if received + timedelta(days=30) <= now:
            continue
        events.append({**{key: value for key, value in row.items() if key != "payload_json"},
            "_id": "event:" + row["event_id"], "kind": "EVENT", "payload": parse(row["payload_json"]),
            "expires_at": received + timedelta(days=30)})
    return state, events


def migrate(sql, mongo, apply=False, remove_legacy=False, backup_root=None):
    if remove_legacy and not apply:
        raise ValueError("--remove-legacy requires --apply and a verified backup")
    sql.rollback()  # Capture a fresh view; this utility owns its SQL connection.
    source = snapshot(sql, mongo)
    result = {"mode": "apply" if apply else "read_only",
              "legacy_sql_rows": {name: len(table["rows"]) for name, table in source["sql"].items()},
              "legacy_mongo_documents": {name: len(docs) for name, docs in source["mongo"].items()}}
    if not apply:
        return result
    now = datetime.now(timezone.utc)
    backup = (backup_root or worker.ROOT / "storage/recommendation-backups") / now.strftime("%Y%m%dT%H%M%S%fZ")
    backup.mkdir(parents=True, exist_ok=False)
    payload = json_util.dumps(encode_dates(source), indent=2).encode("utf-8")
    path = backup / "snapshot.json"
    path.write_bytes(payload)
    checksum = hashlib.sha256(payload).hexdigest()
    (backup / "sha256.txt").write_text(checksum + "  snapshot.json\n", encoding="utf-8")
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise RuntimeError("Backup verification failed; no source will be deleted")
    # Read the verified disk backup, not an unverified in-memory copy.
    state, events = documents(json_util.loads(path.read_text(encoding="utf-8")), now)
    worker.initialize_storage(mongo)
    for name, docs in (("recommendation_state", state), ("recommendation_events", events)):
        for document in docs:
            mongo[name].update_one({"_id": document["_id"]}, {"$setOnInsert": document}, upsert=True)
            found = mongo[name].find_one({"_id": document["_id"]})
            if found.get("kind") != document["kind"] or found.get("user_id") != document.get("user_id"):
                raise RuntimeError("Destination ownership/type conflict; sources were not deleted")
    result.update(backup=str(backup), imported_state=len(state), imported_logs=len(events), removed_legacy=False)
    if remove_legacy:
        sql.rollback()
        if fingerprint(snapshot(sql, mongo)) != fingerprint(source):
            raise RuntimeError("Legacy data changed after backup; stop writers and retry. Nothing deleted.")
        # Identifiers are an explicit constant allowlist; business tables are never targets.
        with sql.cursor() as cursor:
            names = [name for name in TABLES if name in source["sql"]]
            if names:
                cursor.execute("DROP TABLE " + ",".join(f"`{name}`" for name in names))
        for name in COLLECTIONS:
            if name in source["mongo"]:
                mongo.drop_collection(name)
        result["removed_legacy"] = True
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, default=worker.ROOT / ".env")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--remove-legacy", action="store_true")
    args = parser.parse_args()
    sql, mongo, client = worker.connections(args.env)
    try:
        print(json.dumps(migrate(sql, mongo, args.apply, args.remove_legacy), default=str))
    finally:
        sql.close()
        client.close()


if __name__ == "__main__":
    main()
