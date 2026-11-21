"""Explicit song license rights-evidence migration; never run automatically at application startup.

Requires a verified backup. Connections use environment variables, never
command-line passwords. Idempotent.
"""
import argparse,os,re
import pymysql

def migrate(connection):
    with connection.cursor() as c:
        c.execute("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='song_licenses'")
        columns={r[0] for r in c.fetchall()}
        additions={'rights_evidence_status':"VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'",'document_sha256':'VARCHAR(64) NULL',
                   'document_inspection_status':'VARCHAR(32) NULL','declared_acquisition_cost_vnd':'DECIMAL(18,0) NULL','rights_verified_by':'BIGINT NULL','rights_verified_at':'DATETIME NULL','rights_review_note':'VARCHAR(1000) NULL'}
        for column,definition in additions.items():
            if column not in columns:c.execute(f'ALTER TABLE song_licenses ADD COLUMN `{column}` {definition}')
    connection.commit()

def constraints(connection):
    with connection.cursor() as c:
        c.execute('SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()')
        existing={r[0] for r in c.fetchall()}
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
    connection.commit()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--constraints-only',action='store_true');a=parser.parse_args()
    if not os.environ.get('CATALOG_BACKUP_VERIFIED'):raise SystemExit('Verified backup required')
    db=pymysql.connect(host=os.environ['MYSQL_HOST'],port=int(os.environ.get('MYSQL_PORT','3306')),user=os.environ['MYSQL_USER'],password=os.environ['MYSQL_PASSWORD'],database=os.environ.get('MYSQL_DATABASE','moodify'),charset='utf8mb4')
    try:
        if a.constraints_only:constraints(db)
        else:migrate(db)
    finally:db.close()
