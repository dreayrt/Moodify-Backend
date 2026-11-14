#!/usr/bin/env python3
"""Measure missing durations of uploaded tracks. Defaults to a read-only preview."""
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def uploaded_audio_path(local_path: str, uploads_root: Path) -> Path:
    normalized = local_path.replace("\\", "/").lstrip("/")
    if not normalized.startswith("uploads/audio/"):
        raise ValueError("Not a local uploaded audio file")
    root = uploads_root.resolve()
    candidate = (root / normalized.removeprefix("uploads/")).resolve()
    if not candidate.is_relative_to(root / "audio"):
        raise ValueError("Audio path escapes the upload directory")
    if not candidate.is_file():
        raise ValueError("Uploaded audio file is missing on this machine")
    return candidate


def parse_duration(metadata: dict) -> int:
    streams = metadata.get("streams") or []
    if not streams or streams[0].get("codec_type") != "audio":
        raise ValueError("File contains no audio stream")
    for value in (streams[0].get("duration"), (metadata.get("format") or {}).get("duration")):
        try:
            duration = float(value) * 1000 if not isinstance(value, bool) else math.nan
        except (ValueError, TypeError):
            continue
        if math.isfinite(duration) and 1 <= duration <= 2_147_483_647:
            return math.floor(duration + 0.5)
    raise ValueError("File contains no valid playback duration")


def measure_duration(path: Path, ffprobe: str) -> int:
    result = subprocess.run([
        ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0",
        "-show_entries", "stream=codec_type,duration:format=duration", "-of", "json", str(path),
    ], check=True, capture_output=True, text=True, timeout=15)
    return parse_duration(json.loads(result.stdout))


def original_state(track: dict) -> dict:
    """Compare-and-set prevents replacing metadata changed since the scan."""
    query = {"_id": track["_id"], "local_path": track["local_path"]}
    for name in ("duration_ms", "duration_formatted"):
        query[name] = {"$eq": track[name], "$exists": True} if name in track else {"$exists": False}
    return query


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--uploads-root", type=Path, required=True,
                        help="The backend's uploads directory, e.g. /app/uploads")
    parser.add_argument("--ffprobe", default=os.environ.get("FFPROBE_PATH", "ffprobe"))
    parser.add_argument("--track-id", action="append", help="Limit to exact Mongo track ID(s)")
    parser.add_argument("--apply", action="store_true", help="Write measured durations after backing up old values")
    parser.add_argument("--backup-file", type=Path, help="New JSONL backup file (required with --apply)")
    args = parser.parse_args()
    if args.apply and not args.backup_file:
        parser.error("--apply requires --backup-file")
    uri = os.environ.get("MONGODB_URI")
    if not uri:
        parser.error("Set MONGODB_URI for the target database; the URI is never printed")
    from bson import ObjectId, json_util
    from pymongo import MongoClient

    query = {"local_path": {"$regex": "^/?uploads/audio/"},
             "$or": [{"duration_ms": None}, {"duration_ms": {"$lte": 0}}]}
    if args.track_id:
        ids = [item for value in args.track_id
               for item in ([value, ObjectId(value)] if ObjectId.is_valid(value) else [value])]
        query["_id"] = {"$in": ids}
    counts = {"found": 0, "measured": 0, "updated": 0, "conflicts": 0, "failed": 0}
    backup = args.backup_file.open("x", encoding="utf-8") if args.apply else None
    try:
        with MongoClient(uri, serverSelectionTimeoutMS=5000) as client:
            collection = client[args.database]["tracks"]
            for track in collection.find(query, {"name": 1, "local_path": 1, "duration_ms": 1, "duration_formatted": 1}):
                counts["found"] += 1
                try:
                    duration = measure_duration(uploaded_audio_path(track["local_path"], args.uploads_root), args.ffprobe)
                    fields = {"duration_ms": duration, "duration_formatted": f"{duration // 60000}:{duration // 1000 % 60:02d}"}
                    counts["measured"] += 1
                    print(json.dumps({"id": str(track["_id"]), "name": track.get("name"), **fields}))
                    if args.apply:
                        record = {"database": args.database, "collection": "tracks", "before": track,
                                  "after": fields, "at": datetime.now(timezone.utc)}
                        backup.write(json_util.dumps(record, ensure_ascii=False) + "\n")
                        backup.flush()
                        os.fsync(backup.fileno())
                        result = collection.update_one(original_state(track), {"$set": fields})
                        counts["updated" if result.modified_count else "conflicts"] += 1
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    counts["failed"] += 1
                    # Do not log subprocess output or Mongo credentials.
                    print(json.dumps({"id": str(track["_id"]), "error": type(error).__name__}))
    finally:
        if backup:
            backup.close()
    print(json.dumps({"apply": args.apply, **counts}))
    return 1 if counts["failed"] or counts["conflicts"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
