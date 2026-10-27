# Content Lead revenue: deployment and accounting rules

## Apply to an existing database

1. Back up the existing MySQL database.
2. Apply `database/add_content_lead_revenue.sql` once, before starting the updated backend.
3. Deploy the updated OCR service, backend and frontend. The OCR endpoint accepts PDF, PNG and JPG. Its PDF limit is 20 pages.
4. Confirm that new song licenses have `acquisition_cost_vnd` populated only when the document explicitly states an unambiguous VND acquisition cost. Existing licenses remain `NULL` until separately verified and backfilled.

For local Docker development, `compose.yaml` exposes MySQL at `127.0.0.1:3307` and initializes the `moodify` database. Keep `DATABASE_SOURCE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` in the backend `.env` aligned with those settings. For a new local database, run `database/Moodify.sql` first, then the revenue migration; `Moodify.sql` drops and recreates the `moodify` database and must not be run against existing data.

If the earlier revenue migration has **already** created both revenue tables, do not run it again. Stop playback writes, back up MySQL, run `database/normalize_redundant_references.sql` once, then deploy the backend version that reads session identity and progress from `listening_history`. See `docs/SCHEMA_REDUNDANCY_REVIEW.md` for the preflight conditions. Do not run the new backend against the old table layout or the old backend against the normalized layout.

Do **not** execute `database/Moodify.sql` or the development seed script against existing data; those scripts recreate data. This change does not infer historical payable streams from old `listening_history` rows, because those rows do not prove continuous playback.

## Policy V1_60S_75_25

- One new playback session per genuine replay, including natural repeat, reselecting the song, or rewinding a substantially played song to its start. A session earns at most one qualified stream after 60 seconds of continuous position progress and elapsed server time. Pauses, other seeks, stalls, refreshes and position jumps break the continuous interval.
- The ledger records 1,000 VND gross, 750 VND Content Lead, and 250 VND Moodify per approved stream. The Content Lead's lifetime 750 VND share is compared with the song's acquisition cost for recoupment.
- There is no permanent per-listener replay cap. Concurrent playback or at least four streams of the same song in ten minutes is held as `PENDING_REVIEW` for an admin, excluded from revenue until approved. Rejected streams never enter the revenue totals.
- Only published and approved tracks with an active, dated license qualify. Own listens, non-USER roles and anonymous plays do not earn revenue.
- The dashboard reports recognized revenue, not completed bank payments. No payout automation is included.

## Verification

- Sign in as a normal user, play an approved licensed song for 60 seconds, and check that one `qualified_streams` row is created. Repeat naturally to confirm a new session key and separate row.
- Pause or seek before 60 seconds and confirm no row appears for that session. Opening a track without starting audio, listening for five seconds, or refreshing repeatedly must not produce an approved row.
- Sign in as Content Lead and compare the dashboard with `qualified_streams` totals. Confirm recoupment uses the lifetime 750 VND share and shows unknown when the license cost is `NULL`.
- Review suspicious rows with `GET /api/admin/revenue/pending` and `POST /api/admin/revenue/{id}/review` (`decision` is `APPROVED` or `REJECTED`, with a reason for rejection).

Client audio progress can be forged by a determined attacker. The backend independently checks elapsed time, position progress, identity, session duplication and overlap; stronger proof would require authenticated media delivery receipts or server-managed streaming sessions. Review remains part of the financial control.
