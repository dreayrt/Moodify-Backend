-- Deprecated: song license expiry is handled by Spring's
-- SongLicenseLifecycleScheduler because it must update both MySQL and MongoDB.
-- This cleanup intentionally removes the old MySQL-only scheduler so only one
-- scheduled owner remains for the license-expiry workflow.

USE moodify;

DROP EVENT IF EXISTS evt_daily_song_license_expiry;
DROP PROCEDURE IF EXISTS sp_expire_outdated_song_licenses;

-- Verify the Spring cron is active in the backend process and this old DB event
-- is gone:
-- SHOW EVENTS FROM moodify;
