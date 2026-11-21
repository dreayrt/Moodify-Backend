"""Explicit schema migration; never run automatically at application startup.

Requires a verified backup and a reviewed identity manifest, including tombstones.
Connections use environment variables, never command-line passwords. Idempotent.
"""
import argparse,json,os,re
from pathlib import Path
import pymysql

def migrate(connection,source,manifest):
    with connection.cursor() as c:
        for statement in re.sub(r'--[^\n]*','',source).split(';'):
            if statement.strip():c.execute(statement)
        c.execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='catalog_track_registry' AND column_name='revision'")
        if not c.fetchone()[0]:c.execute('ALTER TABLE catalog_track_registry ADD COLUMN revision BIGINT NOT NULL DEFAULT 0')
        c.execute("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='song_licenses'")
        columns={r[0] for r in c.fetchall()}
        additions={'rights_evidence_status':"VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'",'document_sha256':'VARCHAR(64) NULL',
                   'document_inspection_status':'VARCHAR(32) NULL','declared_acquisition_cost_vnd':'DECIMAL(18,0) NULL','rights_verified_by':'BIGINT NULL','rights_verified_at':'DATETIME NULL','rights_review_note':'VARCHAR(1000) NULL'}
        for column,definition in additions.items():
            if column not in columns:c.execute(f'ALTER TABLE song_licenses ADD COLUMN `{column}` {definition}')
        for t in manifest['tracks']:
            c.execute("INSERT INTO catalog_track_registry(track_id,state,updated_at) VALUES (%s,%s,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE state=VALUES(state)",(t['id'],t['state']))
            for alias in t['aliases']:
                c.execute('INSERT IGNORE INTO catalog_track_aliases(alias_id,track_id) VALUES (%s,%s)',(alias,t['id']))
                c.execute('SELECT track_id FROM catalog_track_aliases WHERE alias_id=%s',(alias,))
                if c.fetchone()[0]!=t['id']:raise ValueError('track_alias_collision')
        for a in manifest['artists']:
            c.execute('INSERT IGNORE INTO catalog_artist_registry(artist_id) VALUES (%s)',(a['id'],))
            for alias in a['aliases']:
                c.execute('INSERT IGNORE INTO catalog_artist_aliases(alias_id,artist_id) VALUES (%s,%s)',(alias,a['id']))
                c.execute('SELECT artist_id FROM catalog_artist_aliases WHERE alias_id=%s',(alias,))
                if c.fetchone()[0]!=a['id']:raise ValueError('artist_alias_collision')
        for track,artist in manifest['credits']:
            c.execute('INSERT IGNORE INTO catalog_track_artists(track_id,artist_id) VALUES (%s,%s)',(track,artist))
    connection.commit()

def constraints(connection):
    with connection.cursor() as c:
        c.execute('SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()')
        existing={r[0] for r in c.fetchall()}
        relations=[('song_licenses','track_id'),('listening_history','track_id'),('user_library_tracks','track_id'),
            ('favorite_songs','track_id'),('album_tracks','track_id'),('offline_downloads','track_id'),
            ('download_license_tokens','track_id'),('offline_audit_log','track_id'),('qualified_streams','track_id'),
            ('karaoke_room_queue','track_id'),('karaoke_rooms','current_track_id')]
        relations += [('users','artist_spotify_id'),('favorite_artists','artist_id'),('artist_profiles','artist_id'),('artist_reviews','artist_id')]
        for table,column in relations:
            name='fk_integrity_'+table
            if name in existing:continue
            parent='catalog_artist_aliases' if column in ['artist_id','artist_spotify_id'] else 'catalog_track_aliases'
            c.execute('SELECT COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=%s AND column_name=%s',(table,column))
            kind,nullable,collation=c.fetchone()
            assert re.fullmatch(r'varchar\(\d+\)',kind)
            if collation!='utf8mb4_bin':c.execute(f'ALTER TABLE `{table}` MODIFY `{column}` {kind} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin '+('NULL' if nullable=='YES' else 'NOT NULL'))
            c.execute(f'ALTER TABLE `{table}` ADD CONSTRAINT `{name}` FOREIGN KEY (`{column}`) REFERENCES `{parent}`(alias_id)')
        # A live license must never silently lose its parent on deletion. MySQL
        # also requires RESTRICT actions before checking this nullable pair.
        for name,column,parent in [('fk_song_license_contract','distribution_contract_id','distribution_contracts'),('fk_song_license_distributor','distributor_id','distributors')]:
            c.execute('SELECT DELETE_RULE,UPDATE_RULE FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name=%s',(name,))
            rules=c.fetchone()
            if rules and any(rule not in ('RESTRICT','NO ACTION') for rule in rules):
                c.execute(f'ALTER TABLE song_licenses DROP FOREIGN KEY `{name}`')
                c.execute(f'ALTER TABLE song_licenses ADD CONSTRAINT `{name}` FOREIGN KEY(`{column}`) REFERENCES `{parent}`(id) ON DELETE RESTRICT ON UPDATE RESTRICT')
        checks={
            'chk_license_supported_type':"license_type IN ('DIGITAL_STREAMING','DEMO_SIMULATED')",
            'chk_license_active_reference':"status<>'ACTIVE' OR (distribution_contract_code IS NOT NULL AND CHAR_LENGTH(TRIM(distribution_contract_code))>0)",
            'chk_license_declared_cost':"declared_acquisition_cost_vnd IS NULL OR declared_acquisition_cost_vnd>=0",
            'chk_license_inspected_simulation':"rights_evidence_status<>'VERIFIED' OR COALESCE(document_inspection_status,'MANUAL_REVIEW_REQUIRED')<>'SIMULATION_DETECTED'",
            'chk_license_contract_pair':"(distribution_contract_id IS NULL AND distributor_id IS NULL) OR (distribution_contract_id IS NOT NULL AND distributor_id IS NOT NULL)",
            'chk_license_evidence_status':"rights_evidence_status IN ('UNVERIFIED','VERIFIED','SIMULATED')",
            'chk_license_simulation':"(license_type='DEMO_SIMULATED') = (rights_evidence_status='SIMULATED')",
            'chk_license_active_evidence':"status<>'ACTIVE' OR (rights_evidence_status IN ('VERIFIED','SIMULATED') AND issue_date IS NOT NULL AND document_songlicenses_url IS NOT NULL AND CHAR_LENGTH(TRIM(document_songlicenses_url))>0 AND document_sha256 IS NOT NULL AND document_sha256 REGEXP '^[0-9a-f]{64}$')",
            'chk_license_verified_audit':"rights_evidence_status<>'VERIFIED' OR (rights_verified_by IS NOT NULL AND rights_verified_at IS NOT NULL AND rights_review_note IS NOT NULL AND CHAR_LENGTH(TRIM(rights_review_note))>0)",
            'chk_license_demo_cost':"rights_evidence_status<>'SIMULATED' OR COALESCE(acquisition_cost_vnd,0)=0",
        }
        for name,expression in checks.items():
            if name=='chk_license_active_evidence' and name in existing:
                c.execute("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME=%s",(name,))
                previous=c.fetchone()[0].replace('`','').lower()
                if 'document_sha256 is not null' not in previous:
                    c.execute('ALTER TABLE song_licenses DROP CHECK chk_license_active_evidence');existing.remove(name)
            if name not in existing:c.execute(f'ALTER TABLE song_licenses ADD CONSTRAINT `{name}` CHECK ({expression})')
        if 'fk_license_rights_reviewer' not in existing:c.execute('ALTER TABLE song_licenses ADD CONSTRAINT fk_license_rights_reviewer FOREIGN KEY(rights_verified_by) REFERENCES users(id)')
        c.execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='distribution_contracts' AND index_name='uq_contract_distributor_pair'")
        if not c.fetchone()[0]:c.execute('ALTER TABLE distribution_contracts ADD UNIQUE KEY uq_contract_distributor_pair(id,distributor_id)')
        if 'fk_license_contract_owner_pair' not in existing:c.execute('ALTER TABLE song_licenses ADD CONSTRAINT fk_license_contract_owner_pair FOREIGN KEY(distribution_contract_id,distributor_id) REFERENCES distribution_contracts(id,distributor_id)')
        c.execute('SELECT TRIGGER_NAME FROM information_schema.triggers WHERE trigger_schema=DATABASE()')
        triggers={r[0] for r in c.fetchall()}
        for operation in ['INSERT','UPDATE']:
            name='trg_integrity_license_'+operation.lower()
            if name not in triggers:c.execute(f"""CREATE TRIGGER {name} BEFORE {operation} ON song_licenses FOR EACH ROW
            BEGIN
              IF NEW.status='ACTIVE' AND NEW.distributor_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM distributors d WHERE d.id=NEW.distributor_id AND d.status='ACTIVE'
              ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Distributor is not active'; END IF;
              IF NEW.distribution_contract_id IS NOT NULL AND EXISTS (
                SELECT 1 FROM distribution_contracts dc WHERE dc.id=NEW.distribution_contract_id AND
                  ((dc.effective_from IS NOT NULL AND (NEW.issue_date IS NULL OR NEW.issue_date<dc.effective_from)) OR
                   (dc.effective_to IS NOT NULL AND (NEW.expiry_date IS NULL OR NEW.expiry_date>dc.effective_to)) OR
                   (NEW.status='ACTIVE' AND dc.status<>'ACTIVE'))
              ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='License exceeds distribution contract scope'; END IF;
            END""")
        if 'trg_integrity_contract_update' not in triggers:c.execute("""CREATE TRIGGER trg_integrity_contract_update BEFORE UPDATE ON distribution_contracts FOR EACH ROW
        BEGIN
          IF EXISTS (SELECT 1 FROM song_licenses sl WHERE sl.distribution_contract_id=OLD.id AND sl.status='ACTIVE' AND
            (NEW.status<>'ACTIVE' OR NEW.distributor_id<>sl.distributor_id OR
             (NEW.effective_from IS NOT NULL AND (sl.issue_date IS NULL OR sl.issue_date<NEW.effective_from)) OR
             (NEW.effective_to IS NOT NULL AND (sl.expiry_date IS NULL OR sl.expiry_date>NEW.effective_to))))
          THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Resolve active licenses before changing contract scope'; END IF;
        END""")
        if 'trg_integrity_distributor_update' not in triggers:c.execute("""CREATE TRIGGER trg_integrity_distributor_update BEFORE UPDATE ON distributors FOR EACH ROW
        BEGIN
          IF NEW.status<>'ACTIVE' AND EXISTS (SELECT 1 FROM song_licenses sl WHERE sl.distributor_id=OLD.id AND sl.status='ACTIVE')
          THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Resolve active licenses before deactivating distributor'; END IF;
        END""")
        c.execute('INSERT IGNORE INTO catalog_schema_versions(version) VALUES (1)')
    connection.commit()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--manifest',type=Path,required=True);parser.add_argument('--constraints-only',action='store_true');a=parser.parse_args()
    if not os.environ.get('CATALOG_BACKUP_VERIFIED'):raise SystemExit('Verified backup required')
    db=pymysql.connect(host=os.environ['MYSQL_HOST'],port=int(os.environ.get('MYSQL_PORT','3306')),user=os.environ['MYSQL_USER'],password=os.environ['MYSQL_PASSWORD'],database=os.environ.get('MYSQL_DATABASE','moodify'),charset='utf8mb4')
    try:
        if a.constraints_only:constraints(db)
        else:migrate(db,(Path(__file__).parents[1]/'database/catalog_integrity_v1.sql').read_text(),json.loads(a.manifest.read_text()))
    finally:db.close()
