-- Removes the catalog integrity identity tables and every foreign key that
-- points at them (fk_integrity_* on song_licenses, listening_history,
-- users, artist_profiles, ... and the FKs between the catalog tables).
--
-- Order on an existing installation:
--   1. Back up the database.
--   2. Deploy the backend build that no longer uses these tables.
--   3. Run this script right after the deploy (older builds still write to them).
-- Idempotent: running it again is a no-op.

DROP PROCEDURE IF EXISTS tmp_drop_catalog_integrity_fks;

DELIMITER $$
CREATE PROCEDURE tmp_drop_catalog_integrity_fks()
BEGIN
  REPEAT
    SET @catalog_fk_ddl = (
      SELECT CONCAT('ALTER TABLE `', TABLE_NAME, '` DROP FOREIGN KEY `', CONSTRAINT_NAME, '`')
      FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA = DATABASE()
        AND REFERENCED_TABLE_NAME IN (
          'catalog_track_registry', 'catalog_track_aliases', 'catalog_track_artists',
          'catalog_artist_registry', 'catalog_artist_aliases',
          'catalog_operation_commits', 'catalog_schema_versions')
      LIMIT 1);
    IF @catalog_fk_ddl IS NOT NULL THEN
      PREPARE catalog_fk_stmt FROM @catalog_fk_ddl;
      EXECUTE catalog_fk_stmt;
      DEALLOCATE PREPARE catalog_fk_stmt;
    END IF;
  UNTIL @catalog_fk_ddl IS NULL END REPEAT;
END$$
DELIMITER ;

CALL tmp_drop_catalog_integrity_fks();
DROP PROCEDURE IF EXISTS tmp_drop_catalog_integrity_fks;

DROP TABLE IF EXISTS catalog_track_artists;
DROP TABLE IF EXISTS catalog_track_aliases;
DROP TABLE IF EXISTS catalog_artist_aliases;
DROP TABLE IF EXISTS catalog_track_registry;
DROP TABLE IF EXISTS catalog_artist_registry;
DROP TABLE IF EXISTS catalog_operation_commits;
DROP TABLE IF EXISTS catalog_schema_versions;
