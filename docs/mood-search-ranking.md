# Mood search ranking and evaluation

## Request flow

`POST /api/emotions/recommend` sends the user's text to the configured emotion service (`EMOTION_SERVICE_BASE_URL`), then ranks eligible tracks in MongoDB against the returned music profile. A failed or malformed emotion-service response is surfaced as an HTTP error; the backend does not substitute a synthetic neutral result.

The catalog query excludes rejected, private, unlisted, archived, disabled, and draft tracks. It scans the complete eligible catalog and streams candidates so memory use stays bounded. Candidates are ranked by mood distance ascending, popularity descending for ties, then Spotify ID for stable ordering.

## Ranking version `audio-profile-v3-explainable`

The score is a ranking heuristic, not a probability. For each feature, `dᵢ` is the normalized distance from the model's target. The weighted RMS distance is `sqrt(sum(weightᵢ * dᵢ² for available features) / sum(weightᵢ for available features))`. Missing catalog features add `0.20 * (1 - featureCoverage)`. A match with at least one normalized seed tag in the track's `genres`, `genres_raw`, or `mood_tags` receives a small `0.05` distance reduction.

| Feature | Weight | Normalized distance |
| --- | ---: | --- |
| Valence | 0.26 | `abs(track - target)` |
| Energy | 0.26 | `abs(track - target)` |
| Danceability | 0.16 | `abs(track - target)` |
| Acousticness | 0.16 | `abs(track - target)` |
| Tempo | 0.11 | `min(abs(track BPM - target BPM) / 120, 1)` |
| Mode | 0.05 | `abs(track mode - target mode)`, minor = 0 and major = 1 |

The model's min/max valence and energy ranges are returned as fit diagnostics; merely being somewhere inside a range no longer reduces the score. This prevents a track near the edge of the range from getting the same free bonus as one near the target. The final distance is clamped to `[0, 1]`.

Genre normalization folds case, spaces, punctuation, and Vietnamese diacritics; `vpop` and `vietnamesepop` map to `pop`, and `rhythmandblues` maps to `rnb`. Seed tags remain a small soft preference because the catalog may only have broad genres and may not yet have mood tags.

## Response diagnostics

The recommendation response includes:

- `candidateCount`: eligible catalog tracks scored.
- `totalMatched`: tracks at or below `maxMatchDistance`.
- `matchStatus`: `MATCHED`, `LIMITED`, or `NO_CANDIDATES`.
- `rankingVersion` and `maxMatchDistance`.
- `catalogDiagnostics`: counts of tracks with usable audio features, complete expected feature coverage, genre tags, mood tags, and average feature coverage across all eligible candidates.
- Each returned track's `moodMatchDistance`, `moodFeatureCoverage`, and `moodMatchedTags`.
- Each returned track's `moodFeatureDistances`, keyed by feature, with weight, target, actual value, normalized distance, weighted squared error, and whether valence/energy fall inside the model's suggested range.

The default cutoff is now `0.35`, configurable through `MOOD_RECOMMENDATION_MAX_DISTANCE`. A higher cutoff admits more tracks with a wider feature gap, so it increases recall and can reduce precision. `MATCHED` returns tracks inside the cutoff. If none qualify, `LIMITED` returns at most three closest tracks and labels them as references rather than passing them off as strong matches. `NO_CANDIDATES` means no eligible catalog tracks were found.

With the sadness profile and `Smooth Criminal` example reviewed during this task, the track's score without the former in-range bonus is approximately `0.2258` and it has no matching sadness/acoustic/piano tag. At `0.35` it is eligible for `MATCHED` based on audio features alone. This illustrates why the returned score and catalog tag coverage should be reviewed; the cutoff is not a probability or a human relevance label.

## Evaluation before final tuning

Use `mood-search-evaluation-template.csv` to record a fixed sample. Include multiple phrasings for each supported emotion, the model's predicted label/confidence, the top tracks and their distances, and a human relevance rating. Compare ranking versions using at least:

- Precision at 5: share of the first five tracks rated relevant.
- Mean reciprocal rank: how early the first relevant track appears.
- Match rate: share of queries with at least one track under the cutoff.
- Catalog feature coverage and mood-tag coverage from `catalogDiagnostics`.

Do not tune on a single example. Adjust weights or cutoff only when the reviewed sample shows a consistent gain in relevance without an unacceptable drop in match rate. A missing or low `tracksWithMoodTags` count points to catalog enrichment; low model-label agreement points to emotion-model evaluation; high feature distances with good labels point to ranking/profile calibration.

No MongoDB documents or emotion-model weights are changed by the ranker. No labeled evaluation set was present, so the `0.35` cutoff is a recall-oriented starting point and should be checked against the fixed review sample.

## Error behavior

An unreachable emotion service returns HTTP 503. An upstream error or malformed model response returns HTTP 502. The frontend displays those errors and does not retry through a different search endpoint that could conceal them.
