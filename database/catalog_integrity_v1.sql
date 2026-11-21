-- Run with scripts/migrate-catalog-integrity.py. Back up both databases first.
-- Registries are durable identity keys: retirement never destroys listening history.
CREATE TABLE IF NOT EXISTS catalog_schema_versions (
  version INT PRIMARY KEY,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_operation_commits (
  operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  committed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_track_registry (
  track_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  state ENUM('LIVE','RETIRED') NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_track_aliases (
  alias_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  track_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_catalog_alias_track FOREIGN KEY (track_id) REFERENCES catalog_track_registry(track_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_artist_registry (
  artist_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_artist_aliases (
  alias_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  artist_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_catalog_alias_artist FOREIGN KEY (artist_id) REFERENCES catalog_artist_registry(artist_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS catalog_track_artists (
  track_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  artist_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY(track_id,artist_id),
  CONSTRAINT fk_catalog_credit_track FOREIGN KEY(track_id) REFERENCES catalog_track_registry(track_id),
  CONSTRAINT fk_catalog_credit_artist FOREIGN KEY(artist_id) REFERENCES catalog_artist_registry(artist_id)
) ENGINE=InnoDB;
