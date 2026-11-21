# Catalog integrity migration

The catalog identity tables are SQL foreign key targets for records whose canonical documents live in MongoDB. Retired identities continue to resolve listening history. MongoDB validators enforce document shape and publication requirements. The application coordinates writes across SQL, MongoDB and uploaded files through commit markers and the `catalog_operations` journal.

For an existing installation, take and verify a SQL and MongoDB backup, then prepare a manifest of canonical tracks, aliases, artists and credits. Use `scripts/migrate-catalog-integrity.py --manifest <manifest.json>` to create and populate the identity tables. The connection uses `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD` and `MYSQL_DATABASE`. Set `CATALOG_BACKUP_VERIFIED` after verifying the backup. Reconcile old references and license evidence before running the same command with `--constraints-only`. For a fresh installation, run `database/Moodify.sql` first and use an empty manifest with `tracks`, `artists` and `credits` arrays. The constraint phase records schema version 1.

Apply `scripts/catalog_mongo_constraints.py` via its `install(database)` function after repairing documents that fail validation. Deploy the backend image containing `ffprobe` and `pdftotext`, then include the locations in `deploy/catalog-integrity.nginx.conf` in the TLS server. A deployment must replace the old static audio location to enforce the rights check on direct media requests.

Apply `database/catalog_agreement_details_v2.sql` before deploying the contract details form. The nullable JSON column stores optional party names, addresses, representatives, titles and planned signing information. OCR can populate them from explicit field labels and the uploader can correct them before submitting. These fields are separate from the date interval and evidence used to authorize playback.

After validating the Spring scheduler, remove the legacy `evt_daily_song_license_expiry` event and `sp_expire_outdated_song_licenses` routine. Spring reconciles license state at startup and at 00:05 in `Asia/Ho_Chi_Minh`. Requests check the current license and its date interval independently of the scheduled job. The subscription reminder event continues to run separately.

`RECOMMENDATIONS_DEMO_CATALOG_ENABLED=true` permits approved media with explicitly simulated evidence. Leave the flag false on installations that serve only verified rights. A standard license requires reviewer attribution and a review note. PDF text inspection is one input to that review.
