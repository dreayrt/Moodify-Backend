# Production karaoke library — 4 November 2026

Production backend uses MongoDB database `music_streaming`, collection
`karaoke_assets`. The Compass screenshot used `music_streaming_1`; a copy in that
database does not change the backend's configured database.

## Causes and repair

The production collection contained 53 records marked `ready`, while the active
VM root `/srv/moodify/karaoke` contained 25 versioned manifests. The catalog
queries ready Mongo records, so it advertised 28 records outside the active set.
Those 28 records are now `archived`, with reason
`outside-active-karaoke-batch-20261103`. Their metadata and files were retained.
The 25 active IDs and asset versions match
`E:/python-audio-separator/artifacts/karaoke/dumps/karaoke_assets-new-25-20261103.json`.
The instrumental, vocal and pitch reference hashes were verified against the VM
files before activation.

The deployed JAR did not contain `KaraokeLyrics`. It returned lyrics only from
`tracks`, even when newly uploaded lyrics existed in `karaoke_assets.lyrics`.
The existing local resolver and its integration in `KaraokeController` and
`KaraokeRoomService` were compiled against the running JAR's dependencies and
deployed. Java parameter metadata is preserved (`javac -parameters --release 21`).
The resolver provides plain text and LRC for the existing frontend and rejects
timelines that disagree with the vocal hash, text or valid timestamp bounds.

Only the backend container was recreated. Its image is tagged
`moodify-backend:karaoke-lyrics-20261104` and `moodify-backend:latest`. The host JAR
at `/opt/moodify/backend/moodify-0.0.1-SNAPSHOT.jar` was also updated.

## Verification

- `KaraokeLyricsTest`: 6 passed, no failures/errors.
- Authenticated HTTPS catalog: exactly 25 IDs and versions from the selected file.
- All 25 track detail endpoints responded successfully.
- All 50 instrumental/vocal URLs returned HTTP 206 for a byte range request.
- Room 9, Luu Nien: 238 plain text characters, 282 LRC characters, 4 timeline lines.
- 23 tracks return plain and synchronized lyrics. Day Dream and Tam Su Tuoi 30
  have no lyrics in their uploaded manifests; the resolver cannot create missing
  lyrics. Generated lyrics remain subject to their `needs_review` status and may
  be partial. These checks establish data delivery, not transcript accuracy.

The first patched build omitted Java parameter names and failed the authenticated
catalog check. It was replaced with the corrected build before final verification.

## Recovery

The VM backup directory is
`/home/kenresfx/karaoke-production-backups/20261103T171534Z/`.
It contains the original running JAR, original host JAR, all 53 pre-change Mongo
records in Extended JSON, and the previous container IDs.

The original backend image is retained as
`moodify-backend:pre-karaoke-lyrics-20261103T171534Z`.
Restoring this image would also restore the old lyrics behavior. Restoring the
archived records to `ready` would advertise unavailable old assets again.
