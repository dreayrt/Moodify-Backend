USE moodify;

-- Preserve the entered document reference even when it is not registered as a
-- distribution_contracts row. This migration is safe to run more than once.
SET @has_distribution_contract_code = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'song_licenses'
      AND COLUMN_NAME = 'distribution_contract_code'
);
SET @migration_sql = IF(
    @has_distribution_contract_code = 0,
    'ALTER TABLE song_licenses ADD COLUMN distribution_contract_code VARCHAR(80) NULL AFTER distribution_contract_id',
    'SELECT 1'
);
PREPARE song_license_migration FROM @migration_sql;
EXECUTE song_license_migration;
DEALLOCATE PREPARE song_license_migration;

-- Backfill the display code for rows that already reference a registered contract.
UPDATE song_licenses sl
JOIN distribution_contracts dc ON dc.id = sl.distribution_contract_id
SET sl.distribution_contract_code = dc.contract_code
WHERE sl.distribution_contract_code IS NULL;

-- A license can identify both the rights owner and its distributor.
SET @has_owner_check = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'song_licenses'
      AND CONSTRAINT_NAME = 'chk_song_license_owner'
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @migration_sql = IF(
    @has_owner_check > 0,
    'ALTER TABLE song_licenses DROP CHECK chk_song_license_owner',
    'SELECT 1'
);
PREPARE song_license_migration FROM @migration_sql;
EXECUTE song_license_migration;
DEALLOCATE PREPARE song_license_migration;

ALTER TABLE song_licenses
    ADD CONSTRAINT chk_song_license_owner
    CHECK (distributor_id IS NOT NULL OR copyright_owner IS NOT NULL);
