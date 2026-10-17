# Mood tag preview, review, apply, and rollback

This tool reads the audio profiles from `Model-Detect_CamXuc/DetectEmotions/music_mapping.py` and reads the `tracks` collection. The model's text classifier labels a user's text; its profile table supplies the audio-feature targets used here. The tool does not run a second emotion model or define a second distance formula. It scores every track against the model's base profiles with the same `MoodTrackScorer` used by mood search. During tag derivation, it disables the existing soft tag preference so a track's current tags cannot help assign its own new tag.

## Preview only

From the backend repository, create a read-only preview:

```powershell
.\scripts\mood-tags.ps1 -Mode preview
```

The default preview ranks all current tracks with a valid, non-empty string `spotify_id` against the seven model profiles and writes a timestamped JSON file in `docs/`. It does not assign tags by itself. Documents without a valid Spotify ID are excluded and listed in `ignoredDocumentSamples`, so they cannot accidentally receive a mood tag. To include candidate statuses, set both distance controls explicitly:

```powershell
.\scripts\mood-tags.ps1 -Mode preview -MaxDistance 0.35 -MinMargin 0.05
```

The distance cutoff uses the backend scorer's 0-to-1 distance scale. The margin is the distance gap between the best and second-best profile. These controls are optional; when omitted, the output is a ranking for human review, not an automatic classification. `MinFeatureCoverage` defaults to `1.0`; lower it only when a reviewer has accepted incomplete track metrics.

Each track includes its source metrics, scores for all model profiles, closest profile, distance margin, feature coverage, existing `mood_tags`, and an initially `null` `approvedMoodTags`. Save a separate reviewed copy of the preview, then review every track and fill that field with either one canonical label from the model profile list (`enjoyment`, `sadness`, `anger`, `fear`, `surprise`, `disgust`, or `other`) or `[]` when the evidence is insufficient or ambiguous. Keep the metrics and score fields intact. A proposal is not automatically written to MongoDB.

## Apply the reviewed file

Applying requires an explicit reviewed file and a new manifest path. The command validates all current track IDs, the unchanged model profile hash, unchanged audio metrics and existing tags, and the approved label values before writing. It writes only `mood_tags`, and uses compare-and-set filters so it will stop if a track changes concurrently.

From Windows CMD, run this on one line (replace the filenames with your newly reviewed file and a manifest name that does not exist yet):

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mood-tags.ps1 -Mode apply -ReviewedFile .\docs\mood-tags-reviewed-NEW.json -ManifestFile .\docs\mood-tags-rollback-NEW.json
```

The PowerShell backtick line-continuation character does not work in CMD. The previous local `docs/mood-tags-rollback-20261016.json` already has status `APPLIED`; do not reuse that manifest filename or apply its reviewed file again to the same database.

The 154-document count in `docs/mood-tag-audit.md` is an earlier local audit snapshot. Apply always requires the preview and approval to cover every current track with a valid Spotify ID, and verifies that the excluded-document count and samples have not changed. It will not silently assume that the catalog still contains 154 tracks.

## Roll back

Use the manifest created by apply. Rollback restores or unsets only the previous `mood_tags` field and refuses to proceed if its values have changed since application.

```powershell
.\scripts\mood-tags.ps1 -Mode rollback -ManifestFile .\docs\mood-tags-rollback-YYYYMMDD-HHMMSS.json
```

## Effect on mood search

During `empathy` searches, a reviewed track mood tag matching the model's dominant text-emotion label gets the same small soft preference as a matching seed tag. The distance formula and the `0.35` default cutoff are unchanged. Mood search also excludes documents without a valid Spotify ID, which prevents test or non-playable records from appearing as recommendations. `mood_booster` searches keep using their transformed numeric audio profile without a preference for the user's original negative emotion label.

The command disables track seeding, scheduling, startup maintenance, and JPA schema updates while it runs. Preview performs database reads only. The apply mode is intentionally separate and must be invoked explicitly after reviewing the JSON.
