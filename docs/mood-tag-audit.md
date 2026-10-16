# Read-only MongoDB mood-tag audit

The search diagnostics showed broad genre coverage but no `mood_tags` in the reviewed catalog. A track with `genres: ["pop"]` and `genres_raw: ["vpop"]` is not by itself evidence of bad genre data: those values normalize to the same broad genre. Genre and mood labels represent different information.

## Local catalog audit on 2026-10-16

A read-only scan of the configured local `tracks` collection returned:

- 154 tracks total; all 154 have `genres`, and 147 have `genres_raw`.
- 0 tracks have `mood_tags`.
- No malformed array fields and no `genres`/`genres_raw` pairs without a normalized overlap.
- Common stored aliases include `pop`, `v-pop`, `vpop`, and `vietnamese`; the raw field also contains `vpop`, `pop`, `hiphop`, and `indie`.

This does not confirm that the stored genre mapping is wrong. The overlapping values support the current canonicalization, while repeated aliases and generic tags such as `other` show that the normalized field is noisy. The confirmed catalog gap is that no mood labels have been populated. This audit itself did not update MongoDB. A follow-up inspection found the model's `BASE_EMOTION_PROFILES` table, which defines music-feature targets for its seven emotion labels. It can supply candidate profiles, but it is not a set of verified per-track ground-truth labels; each proposed track label must therefore remain reviewable before it is written.

The backend configuration now defaults the mood-search cutoff to `0.35`. Neither the current process environment nor the backend `.env` defines `MOOD_RECOMMENDATION_MAX_DISTANCE`, so this default is not overridden in the current local configuration.

Run `scripts/audit-mood-tags.mongosh.js` in an authenticated `mongosh` session with the intended database selected. It reads all documents in `tracks` and reports:

- counts for `genres`, `genres_raw`, `mood_tags`, and audio features;
- raw tag frequency distributions;
- records where normalized `genres` and `genres_raw` have no overlapping value;
- up to 20 sample records missing `mood_tags`.

The script is read-only. Treat non-overlap as an audit lead, not automatic proof of bad data; the two fields may come from different sources or taxonomies. Review sample records and identify the crawler/ingest mapping before changing any values. Preserve original genre fields and add emotional labels only to `mood_tags` after defining and validating the tagging source. If a future correction is approved, export a backup first and record changed document IDs and old values so the migration can be reversed precisely.

The reusable review-and-apply process is documented in [`mood-tag-tool.md`](mood-tag-tool.md). It compares each track's stored audio features to the model's base profiles using the backend's shared `MoodTrackScorer`, produces a full-catalog review file, and keeps the Mongo write as a separate explicit step with a rollback manifest. The original 154-track count is an audit snapshot; the tool checks the live collection instead of assuming it remains unchanged.

## Preview snapshot on 2026-10-16

The read-only preview at `docs/mood-tags-preview-20261016-230007.json` found 154 documents in the collection: 147 with a valid non-empty `spotify_id`, and 7 without one. The excluded samples include test entries and video-style records, so the tool does not guess which Spotify track they represent. Of the 147 valid tracks, 105 have all six scoring features and 42 have none; the latter receive `INSUFFICIENT_FEATURE_COVERAGE`. No assignment thresholds were supplied, so the preview contains profile rankings only and has no auto-approved tags. No MongoDB fields were changed.
