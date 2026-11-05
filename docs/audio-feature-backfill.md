# Backfill audio features for an imported audio batch

The November 3 track dump contains 399 documents and 399 distinct `local_path` values, but only 8 documents have the six numeric/string fields consumed by the mood scorer. The Mongo upsert script copies document data; it does not analyze MP3 files. Normal backend uploads call `AudioAnalysisService`, which posts to the Essentia `/analyze` endpoint. On November 3, the VM was reachable, port 8002 was listening, and a sample MP3 returned all six expected values.

The batch backfill utility is [`scripts/backfill_audio_features.py`](../scripts/backfill_audio_features.py). It selects by exact `catalog_migration_batch` and Mongo `_id`, so records without a Spotify ID are still included. It skips documents that already have all six usable features, resolves each relative `local_path` under the configured audio root, and only stores a response when all six features validate. Existing non-null feature values are preserved. The default mode only checks counts and file paths; `--apply` is required to write. An audio-feature backup manifest is written before updates and can be used with `--rollback-from`.

## Run on the VM that has the MP3 files

The November 3 files use paths below `/opt/music-data-collector`, for example `data/audio/moodify-import-20261103/...mp3`. Run the script on that VM (or another host with the files mounted, the analyzer reachable, and MongoDB access). The local Windows checkout does not contain this audio batch.

On the VM, the system Python did not have `pymongo` installed. Install the Ubuntu package if needed, then set the URI to the VM's local Mongo database. Confirm this is the database containing the batch before running apply:

```bash
sudo apt-get update
sudo apt-get install -y python3-pymongo
export MONGODB_URI='mongodb://127.0.0.1:27017/music_streaming'
```

The script loads a backend `.env` when present; otherwise it uses the environment variables above. Start the Essentia service on `127.0.0.1:8002` first. Then run a no-write check:

```bash
python3 scripts/backfill_audio_features.py \
  --batch-id moodify-catalog-20261103-v1 \
  --audio-root /opt/music-data-collector \
  --database music_streaming
```

The check should report 399 documents, 8 already complete, 391 needing analysis, and no path/file problems. It does not call the analyzer and does not write to MongoDB.

When the analyzer is running and the check is clean, run:

```bash
python3 scripts/backfill_audio_features.py \
  --batch-id moodify-catalog-20261103-v1 \
  --audio-root /opt/music-data-collector \
  --database music_streaming \
  --apply
```

The script stops before writing if the analyzer port is unreachable or the batch count differs from 399. Successful rows get only the `audio_features` field updated. It leaves any track with an incomplete analyzer response unchanged and records failures in the report.

To restore the prior `audio_features` values, use the generated backup manifest:

```bash
python3 scripts/backfill_audio_features.py \
  --batch-id moodify-catalog-20261103-v1 \
  --database music_streaming \
  --rollback-from audio-feature-backfill-backup-YYYYMMDD-HHMMSS.json
```

After the feature backfill succeeds, copy the generated backup manifest from the VM to the Windows checkout:

```powershell
gcloud compute scp moodify-server:/tmp/audio-feature-backfill-backup-YYYYMMDD-HHMMSS.json E:\moodify-backend\temp\ --zone=asia-southeast1-b
```

Then apply just those feature results to the local Compass database. This checks the before-values and batch IDs, and creates a local rollback manifest:

```powershell
$env:MONGODB_URI = 'mongodb://127.0.0.1:27017/music_streaming_1'
python .\scripts\backfill_audio_features.py `
  --batch-id moodify-catalog-20261103-v1 `
  --database music_streaming_1 `
  --apply-results-from .\temp\audio-feature-backfill-backup-YYYYMMDD-HHMMSS.json
```

After the sync succeeds, regenerate the mood-tag preview. The `mood-tags` tool intentionally still excludes documents without valid `spotify_id` values.
