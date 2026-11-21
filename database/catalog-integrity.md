# Catalog integrity migration

Track and artist documents live in MongoDB; SQL tables reference them by id without a SQL identity registry. MongoDB validators enforce document shape and publication requirements. The application applies MongoDB track changes after the SQL transaction commits and keeps failed steps retryable in the `catalog_operations` journal. An upload counts as committed once its `song_licenses` row exists.

The former identity tables (`catalog_track_registry`, `catalog_track_aliases`, `catalog_track_artists`, `catalog_artist_registry`, `catalog_artist_aliases`, `catalog_operation_commits`, `catalog_schema_versions`) are no longer used. On an installation that still has them, deploy the current backend and then run `database/remove_catalog_integrity_tables.sql`. It drops every foreign key that references them before dropping the tables.

For an existing installation, take and verify a SQL and MongoDB backup, then run `scripts/migrate-catalog-integrity.py` to add the license rights-evidence columns. The connection uses `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD` and `MYSQL_DATABASE`. Set `CATALOG_BACKUP_VERIFIED` after verifying the backup. Reconcile license evidence before running the same command with `--constraints-only`.

Apply `scripts/catalog_mongo_constraints.py` via its `install(database)` function after repairing documents that fail validation. Deploy the backend image containing `ffprobe` and `pdftotext`, then include the locations in `deploy/catalog-integrity.nginx.conf` in the TLS server. A deployment must replace the old static audio location to enforce the rights check on direct media requests.

Apply `database/catalog_agreement_details_v2.sql` before deploying the contract details form. The nullable JSON column stores optional party names, addresses, representatives, titles and planned signing information. OCR can populate them from explicit field labels and the uploader can correct them before submitting. These fields are separate from the date interval and evidence used to authorize playback.

After validating the Spring scheduler, remove the legacy `evt_daily_song_license_expiry` event and `sp_expire_outdated_song_licenses` routine. Spring reconciles license state at startup and at 00:05 in `Asia/Ho_Chi_Minh`. Requests check the current license and its date interval independently of the scheduled job. The subscription reminder event continues to run separately.

`RECOMMENDATIONS_DEMO_CATALOG_ENABLED=true` permits approved media with explicitly simulated evidence. Leave the flag false on installations that serve only verified rights. A standard license requires reviewer attribution and a review note. PDF text inspection is one input to that review.
