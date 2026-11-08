#!/usr/bin/env python3
"""Backfill missing audio_features for one imported MongoDB batch.

Run this on the machine that has both the batch's audio files and access to
MongoDB. Audio analysis is delegated to Moodify's Essentia /analyze service.
The default mode only checks paths and counts; Mongo writes require --apply.
"""

from __future__ import annotations

import argparse
import json
import math
import mimetypes
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

from bson import json_util
from pymongo import MongoClient

try:
    from dotenv import load_dotenv
except ImportError:  # Environment variables may be supplied by the VM/container instead.
    load_dotenv = None


FEATURE_FIELDS = (
    "bpm",
    "key_signature",
    "energy",
    "danceability",
    "valence",
    "acousticness",
)
UNIT_FIELDS = ("energy", "danceability", "valence", "acousticness")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--batch-id",
        default="moodify-catalog-20261103-v1",
        help="Exact catalog_migration_batch value to process.",
    )
    parser.add_argument(
        "--audio-root",
        default="/opt/music-data-collector",
        help="Root directory joined with each MongoDB local_path.",
    )
    parser.add_argument(
        "--analyzer-url",
        default="http://127.0.0.1:8002/analyze",
        help="Essentia analysis endpoint, including /analyze.",
    )
    parser.add_argument(
        "--mongo-uri",
        default=os.environ.get("MONGODB_URI"),
        help="Mongo URI; defaults to MONGODB_URI. Never printed by this script.",
    )
    parser.add_argument(
        "--database",
        default=os.environ.get("MONGODB_DATABASE", "music_streaming"),
        help="Mongo database; defaults to MONGODB_DATABASE or music_streaming.",
    )
    parser.add_argument("--collection", default="tracks")
    parser.add_argument("--expected-count", type=int, default=399)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--pause-seconds", type=float, default=0.1)
    parser.add_argument("--report", help="Summary JSON path; defaults to a timestamped file.")
    parser.add_argument("--backup-file", help="Backup manifest path for --apply; defaults to a timestamped file.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--apply",
        action="store_true",
        help="Analyze files and write successful results. A backup manifest is created first.",
    )
    mode.add_argument(
        "--apply-results-from",
        help="Apply a completed remote backfill manifest to this database, preserving before-state checks.",
    )
    mode.add_argument("--rollback-from", help="Restore audio_features from a backfill backup manifest.")
    return parser.parse_args()


def numeric(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def complete_features(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    bpm = numeric(value.get("bpm"))
    key = value.get("key_signature")
    if bpm is None or bpm <= 0 or not isinstance(key, str) or not key.strip():
        return False
    return all(
        (score := numeric(value.get(field))) is not None and 0.0 <= score <= 1.0
        for field in UNIT_FIELDS
    )


def resolve_audio_path(root: Path, local_path: Any) -> Path:
    if not isinstance(local_path, str) or not local_path.strip():
        raise ValueError("local_path is missing")
    relative = PurePosixPath(local_path.replace("\\", "/"))
    if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
        raise ValueError("local_path must be a safe path relative to --audio-root")
    candidate = (root / Path(*relative.parts)).resolve(strict=True)
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise ValueError("local_path resolves outside --audio-root") from exc
    if not candidate.is_file():
        raise ValueError("local_path does not point to a file")
    return candidate


def ensure_analyzer_reachable(analyzer_url: str) -> None:
    parsed = urllib.parse.urlsplit(analyzer_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("--analyzer-url must be an HTTP(S) URL")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((parsed.hostname, port), timeout=5):
            return
    except OSError as exc:
        raise RuntimeError(
            f"Audio analyzer is unreachable at {parsed.hostname}:{port}; no MongoDB writes were made"
        ) from exc


def features_from_response(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("analyzer response must be a JSON object")
    if payload.get("success") is False:
        raise ValueError(str(payload.get("error") or "analyzer reported failure"))
    source = payload.get("audio_features", payload)
    if not isinstance(source, dict):
        raise ValueError("analyzer response has no audio feature object")

    key_signature = source.get("keySignature", source.get("key_signature", source.get("key")))
    result: dict[str, Any] = {}
    for field in ("bpm", *UNIT_FIELDS):
        value = numeric(source.get(field))
        if value is None:
            raise ValueError(f"analyzer response is missing a valid {field}")
        result[field] = value
    if result["bpm"] <= 0:
        raise ValueError("analyzer returned a non-positive bpm")
    if any(not 0.0 <= result[field] <= 1.0 for field in UNIT_FIELDS):
        raise ValueError("analyzer returned a unit feature outside [0, 1]")
    if not isinstance(key_signature, str) or not key_signature.strip():
        raise ValueError("analyzer response is missing a valid key signature")
    result["key_signature"] = key_signature.strip()
    return {field: result[field] for field in FEATURE_FIELDS}


def analyze_file(path: Path, analyzer_url: str, timeout: int) -> dict[str, Any]:
    boundary = uuid.uuid4().hex
    filename = path.name.replace('"', "_").replace("\r", "").replace("\n", "")
    content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8") + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
    request = urllib.request.Request(
        analyzer_url,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read(1000).decode("utf-8", errors="replace")
        raise RuntimeError(f"analyzer returned HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"could not call analyzer: {exc.reason}") from exc
    return features_from_response(payload)


def write_json_atomic(path: Path, value: Any, *, exclusive: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if exclusive and path.exists():
        raise FileExistsError(f"Refusing to overwrite existing file: {path}")
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    os.replace(temporary, path)


def current_audio_state(document: dict[str, Any]) -> tuple[bool, Any]:
    return "audio_features" in document, document.get("audio_features")


def mongo_state_filter(document_id: Any, batch_id: str, present: bool, value: Any) -> dict[str, Any]:
    query: dict[str, Any] = {"_id": document_id, "catalog_migration_batch": batch_id}
    query["audio_features"] = value if present else {"$exists": False}
    return query


def rollback(collection: Any, args: argparse.Namespace) -> int:
    backup_path = Path(args.rollback_from).resolve(strict=True)
    backup = json.loads(backup_path.read_text(encoding="utf-8"))
    if backup.get("batchId") != args.batch_id:
        raise ValueError("Backup manifest batchId does not match --batch-id")
    if backup.get("database") != args.database or backup.get("collection") != args.collection:
        raise ValueError("Backup manifest database/collection does not match the selected target")
    entries = backup.get("entries")
    if not isinstance(entries, list):
        raise ValueError("Backup manifest has no entries array")

    restored = already_restored = conflicts = not_applied = 0
    for entry in entries:
        if not entry.get("afterAudioFeatures"):
            not_applied += 1
            continue
        document_id = json_util.loads(entry["documentId"])
        current = collection.find_one(
            {"_id": document_id, "catalog_migration_batch": args.batch_id},
            {"audio_features": 1},
        )
        if current is None:
            conflicts += 1
            continue
        current_present, current_features = current_audio_state(current)
        before_present = bool(entry.get("beforePresent"))
        before_features = entry.get("beforeAudioFeatures")
        after_features = entry["afterAudioFeatures"]
        if current_present == before_present and current_features == before_features:
            already_restored += 1
            continue
        if not current_present or current_features != after_features:
            conflicts += 1
            continue
        update = (
            {"$set": {"audio_features": before_features}}
            if before_present
            else {"$unset": {"audio_features": ""}}
        )
        result = collection.update_one(
            mongo_state_filter(document_id, args.batch_id, current_present, current_features),
            update,
        )
        if result.matched_count == 1:
            restored += 1
        else:
            conflicts += 1

    backup["rollback"] = {
        "at": datetime.now(timezone.utc).isoformat(),
        "restored": restored,
        "alreadyRestored": already_restored,
        "notApplied": not_applied,
        "conflicts": conflicts,
    }
    backup["status"] = "ROLLED_BACK" if conflicts == 0 else "ROLLBACK_PARTIAL"
    write_json_atomic(backup_path, backup)
    print(f"Rollback: restored={restored}; already restored={already_restored}; not applied={not_applied}; conflicts={conflicts}")
    print(f"Manifest: {backup_path}")
    return 1 if conflicts else 0


def apply_remote_results(collection: Any, args: argparse.Namespace) -> int:
    source_path = Path(args.apply_results_from).resolve(strict=True)
    source = json.loads(source_path.read_text(encoding="utf-8"))
    if source.get("batchId") != args.batch_id:
        raise ValueError("Source manifest batchId does not match --batch-id")
    if source.get("collection") != args.collection:
        raise ValueError("Source manifest collection does not match --collection")
    if source.get("status") not in ("APPLIED", "APPLIED_WITH_ERRORS", "APPLYING"):
        raise ValueError("Source manifest is not an applied or resumable backfill")
    source_entries = source.get("entries")
    if not isinstance(source_entries, list):
        raise ValueError("Source manifest has no entries array")

    batch_query = {"catalog_migration_batch": args.batch_id}
    target_count = collection.count_documents(batch_query)
    if target_count != args.expected_count:
        raise ValueError(
            f"Refusing sync: expected {args.expected_count} target documents, found {target_count}"
        )

    plans: list[dict[str, Any]] = []
    already_synced = not_applied = conflicts = 0
    for entry in source_entries:
        after_features = entry.get("afterAudioFeatures")
        if entry.get("status") not in ("APPLIED", "PLANNED") or not isinstance(after_features, dict):
            not_applied += 1
            continue
        if not complete_features(after_features):
            raise ValueError(f"Source manifest has incomplete features for {entry.get('name')}")
        document_id = json_util.loads(entry["documentId"])
        document = collection.find_one(
            {"_id": document_id, "catalog_migration_batch": args.batch_id},
            {"audio_features": 1},
        )
        if document is None:
            conflicts += 1
            continue
        current_present, current_features = current_audio_state(document)
        before_present = bool(entry.get("beforePresent"))
        before_features = entry.get("beforeAudioFeatures")
        if current_present and current_features == after_features:
            already_synced += 1
            continue
        if current_present != before_present or current_features != before_features:
            conflicts += 1
            continue
        plans.append({
            "documentId": document_id,
            "documentIdJson": json_util.dumps(document_id),
            "name": entry.get("name"),
            "spotifyId": entry.get("spotifyId"),
            "beforePresent": current_present,
            "beforeAudioFeatures": current_features,
            "afterAudioFeatures": after_features,
        })
    if conflicts:
        raise RuntimeError(f"Refusing all sync writes: {conflicts} target documents differ from the source before-state")
    if not plans:
        print(f"No sync writes needed; already synced={already_synced}; source entries not applied={not_applied}")
        return 0

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    backup_path = Path(args.backup_file or f"audio-feature-local-sync-backup-{stamp}.json")
    backup = {
        "schemaVersion": 1,
        "status": "APPLYING",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "batchId": args.batch_id,
        "database": args.database,
        "collection": args.collection,
        "sourceManifest": str(source_path),
        "entries": [
            {
                "documentId": plan["documentIdJson"],
                "spotifyId": plan["spotifyId"],
                "name": plan["name"],
                "beforePresent": plan["beforePresent"],
                "beforeAudioFeatures": plan["beforeAudioFeatures"],
                "afterAudioFeatures": plan["afterAudioFeatures"],
                "status": "PENDING",
            }
            for plan in plans
        ],
    }
    write_json_atomic(backup_path, backup, exclusive=True)
    entries_by_id = {plan["documentIdJson"]: entry for plan, entry in zip(plans, backup["entries"])}
    updated = 0
    for plan in plans:
        document_id = plan["documentId"]
        entry = entries_by_id[plan["documentIdJson"]]
        entry["status"] = "PLANNED"
        write_json_atomic(backup_path, backup)
        result = collection.update_one(
            mongo_state_filter(
                document_id,
                args.batch_id,
                plan["beforePresent"],
                plan["beforeAudioFeatures"],
            ),
            {"$set": {"audio_features": plan["afterAudioFeatures"]}},
        )
        if result.matched_count != 1:
            entry["status"] = "CONFLICT"
            write_json_atomic(backup_path, backup)
            conflicts += 1
            continue
        entry["status"] = "APPLIED"
        write_json_atomic(backup_path, backup)
        updated += 1

    backup["status"] = "APPLIED" if conflicts == 0 else "APPLIED_WITH_ERRORS"
    backup["finishedAt"] = datetime.now(timezone.utc).isoformat()
    write_json_atomic(backup_path, backup)
    print(
        f"Synced {updated} documents; already synced={already_synced}; "
        f"not applied at source={not_applied}; conflicts={conflicts}; backup={backup_path}"
    )
    return 1 if conflicts else 0


def main() -> int:
    if load_dotenv is not None:
        project_env = Path(__file__).resolve().parents[1] / ".env"
        if project_env.exists():
            load_dotenv(project_env, override=False)
    args = parse_args()
    if not args.mongo_uri:
        print("Set MONGODB_URI or pass --mongo-uri.", file=sys.stderr)
        return 2
    if args.expected_count < 0 or args.timeout_seconds <= 0 or args.pause_seconds < 0:
        print("Invalid expected count, timeout, or pause value.", file=sys.stderr)
        return 2

    client = MongoClient(args.mongo_uri, serverSelectionTimeoutMS=5000)
    try:
        collection = client[args.database][args.collection]
        if args.rollback_from:
            return rollback(collection, args)
        if args.apply_results_from:
            return apply_remote_results(collection, args)

        try:
            root = Path(args.audio_root).resolve(strict=True)
            if not root.is_dir():
                raise ValueError("--audio-root must be a directory")
        except (OSError, ValueError) as exc:
            print(f"Audio root error: {exc}", file=sys.stderr)
            return 2

        query = {"catalog_migration_batch": args.batch_id}
        documents = list(collection.find(
            query,
            {"_id": 1, "name": 1, "spotify_id": 1, "local_path": 1, "audio_features": 1},
        ))
        if len(documents) != args.expected_count:
            print(
                f"Refusing batch: expected {args.expected_count} documents, found {len(documents)}. "
                "No MongoDB writes were made.",
                file=sys.stderr,
            )
            return 2

        pending = [document for document in documents if not complete_features(document.get("audio_features"))]
        ready_paths: dict[Any, Path] = {}
        path_errors: dict[Any, str] = {}
        for document in pending:
            try:
                ready_paths[document["_id"]] = resolve_audio_path(root, document.get("local_path"))
            except (OSError, ValueError) as exc:
                path_errors[document["_id"]] = str(exc)

        print(f"Batch: {args.batch_id}")
        print(f"Documents: {len(documents)}; already complete: {len(documents) - len(pending)}; need analysis: {len(pending)}")
        print(f"Audio files found: {len(ready_paths)}; path/file problems: {len(path_errors)}")
        print(f"Mode: {'APPLY' if args.apply else 'CHECK ONLY'}; target: {args.database}.{args.collection}")
        if not pending:
            print("All documents in this batch already have complete audio_features; nothing to do.")
            return 0
        if not args.apply:
            print("Check only: no audio analysis requests or MongoDB writes were made. Re-run with --apply to backfill.")
            return 0 if not path_errors else 1

        ensure_analyzer_reachable(args.analyzer_url)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        report_path = Path(args.report or f"audio-feature-backfill-report-{stamp}.json")
        backup_path = Path(args.backup_file or f"audio-feature-backfill-backup-{stamp}.json")
        if backup_path.resolve(strict=False) == report_path.resolve(strict=False):
            raise ValueError("--report and --backup-file must be different paths")
        if report_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing report: {report_path}")
        if backup_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing backup: {backup_path}")
        backup = {
            "schemaVersion": 1,
            "status": "APPLYING",
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "batchId": args.batch_id,
            "database": args.database,
            "collection": args.collection,
            "entries": [
                {
                    "documentId": json_util.dumps(document["_id"]),
                    "spotifyId": document.get("spotify_id"),
                    "name": document.get("name"),
                    "beforePresent": "audio_features" in document,
                    "beforeAudioFeatures": document.get("audio_features"),
                    "afterAudioFeatures": None,
                    "status": "PENDING" if document["_id"] in ready_paths else "PATH_ERROR",
                    "error": path_errors.get(document["_id"]),
                }
                for document in pending
            ],
        }
        write_json_atomic(backup_path, backup, exclusive=True)
        entry_by_id = {
            str(document["_id"]): entry
            for document, entry in zip(pending, backup["entries"])
        }
        print(f"Backup manifest created before writes: {backup_path}")
        successes = 0
        failures = list(
            {"documentId": str(document_id), "error": error}
            for document_id, error in path_errors.items()
        )
        for index, document in enumerate(pending, start=1):
            document_id = document["_id"]
            path = ready_paths.get(document_id)
            if path is None:
                continue
            try:
                features = analyze_file(path, args.analyzer_url, args.timeout_seconds)
                before_features = document.get("audio_features")
                merged_features = dict(before_features) if isinstance(before_features, dict) else {}
                for field, value in features.items():
                    if field not in merged_features or merged_features[field] is None:
                        merged_features[field] = value
                if not complete_features(merged_features):
                    raise ValueError("Existing feature values prevent a complete valid feature record")
                entry = entry_by_id[str(document_id)]
                entry["afterAudioFeatures"] = merged_features
                entry["status"] = "PLANNED"
                write_json_atomic(backup_path, backup)
                guard: dict[str, Any] = {
                    "_id": document_id,
                    "catalog_migration_batch": args.batch_id,
                }
                if "audio_features" in document:
                    guard["audio_features"] = document["audio_features"]
                else:
                    guard["audio_features"] = {"$exists": False}
                result = collection.update_one(guard, {"$set": {"audio_features": merged_features}})
                if result.matched_count != 1:
                    raise RuntimeError("document changed during analysis; update skipped")
                successes += 1
                entry["status"] = "APPLIED"
                write_json_atomic(backup_path, backup)
                print(f"[{index}/{len(pending)}] Updated: {document.get('name', document_id)}")
            except Exception as exc:  # Keep processing the rest of the batch; report each failure.
                failures.append({"documentId": str(document_id), "name": document.get("name"), "error": str(exc)})
                entry = entry_by_id[str(document_id)]
                if entry.get("status") == "PENDING":
                    entry["status"] = "FAILED"
                    entry["error"] = str(exc)
                write_json_atomic(backup_path, backup)
                print(f"[{index}/{len(pending)}] Failed: {document.get('name', document_id)}: {exc}", file=sys.stderr)
            if args.pause_seconds:
                time.sleep(args.pause_seconds)

        backup["status"] = "APPLIED" if not failures else "APPLIED_WITH_ERRORS"
        backup["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write_json_atomic(backup_path, backup)
        report = {
            "batchId": args.batch_id,
            "database": args.database,
            "collection": args.collection,
            "documents": len(documents),
            "alreadyComplete": len(documents) - len(pending),
            "attempted": len(pending) - len(path_errors),
            "updated": successes,
            "failed": failures,
            "backupManifest": str(backup_path),
        }
        write_json_atomic(report_path, report, exclusive=True)
        print(f"Updated {successes} documents; failures: {len(failures)}; report: {report_path}")
        return 1 if failures else 0
    except Exception as exc:
        print(f"Backfill stopped: {exc}", file=sys.stderr)
        return 1
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
