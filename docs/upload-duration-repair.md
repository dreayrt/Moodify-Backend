# Uploaded track duration

The upload path previously saved `local_path` but never set `duration_ms` or
`duration_formatted`. The search API returns `duration_ms`, so these uploads
displayed as `0:00`. Essentia analysis is optional and does not supply this
required playback metadata.

New uploads are measured by `AudioMetadataService` before storing catalog files
or records. It probes the selected audio stream and falls back to container
duration, saves integer milliseconds and `m:ss`, and rejects unreadable audio
instead of publishing a zero duration. The metadata probe has a 15-second timeout.
It does not depend on the Essentia service.

Both backend Dockerfiles include FFmpeg/ffprobe. Rebuild the runtime image with
the backend change. For a JVM started directly on Windows/Linux, install FFmpeg
and set `FFPROBE_PATH` to the ffprobe executable if it is not on `PATH`.
The probe options are documented in the [ffprobe manual](https://ffmpeg.org/ffprobe.html).

## Existing uploads

Run the repair on the machine/container that can access both MongoDB and the
backend's real `uploads/audio` files. It requires Python 3.10+, `pymongo` and
ffprobe. Set `MONGODB_URI` in the environment for the intended database.
Use the actual database name and uploads mount, not the examples blindly.

Preview one track without changing data:

```bash
python scripts/backfill_upload_durations.py \
  --database music_streaming --uploads-root /app/uploads \
  --track-id '<Mongo track _id>'
```

After reviewing the measured durations, apply the same selection:

```bash
python scripts/backfill_upload_durations.py \
  --database music_streaming --uploads-root /app/uploads \
  --track-id '<Mongo track _id>' \
  --apply --backup-file upload-duration-before.jsonl
```

Omit `--track-id` to process all uploaded tracks with missing/nonpositive
duration. Tracks with a valid duration are preserved. Only `duration_ms` and
`duration_formatted` are updated. Each original state is flushed to the new
backup file before writing; conditional updates skip concurrently changed
records. Missing/corrupt files are reported without updating their records.
The command is safe to rerun and refuses to overwrite an existing backup file.

Verify the search API and Content Lead catalog both report the measured duration
for the repaired track. This code change alone does not repair a production
database until the backfill is run against its uploaded files.

## Checks

```bash
mvn -Dtest=AudioMetadataServiceTest,ContentLeadCatalogServiceTest,ContentLeadCatalogApiTest,ContentLeadCatalogContractResolutionTest test
python -m unittest discover -s scripts/tests -p test_upload_durations.py -v
```

Set both `FFPROBE_PATH` and `FFMPEG_PATH` when running Maven to additionally
generate and probe real WAV, MP3, FLAC and M4A fixtures. Without these variables,
only that integration test is skipped; parsing, failure handling and upload
persistence tests still run.
