-- Apply after catalog integrity v1. The new column is nullable for existing licenses.
SET @agreement_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'song_licenses' AND column_name = 'agreement_details'
);
SET @agreement_column_ddl = IF(
  @agreement_column_exists = 0,
  'ALTER TABLE song_licenses ADD COLUMN agreement_details JSON NULL',
  'SELECT 1'
);
PREPARE add_agreement_column FROM @agreement_column_ddl;
EXECUTE add_agreement_column;
DEALLOCATE PREPARE add_agreement_column;
INSERT IGNORE INTO catalog_schema_versions (version) VALUES (2);
