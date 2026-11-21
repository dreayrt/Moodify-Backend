"""Update only demo document references/fictional party and matching provenance.

Runs inside the worker image with ephemeral publisher credentials, never as worker.
Original PDFs and both before/after snapshots remain immutable.
"""
import argparse
from datetime import datetime, date, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid
sys.path.insert(0, os.getcwd())
from bson import json_util
import worker

def freeze(path, value):
    def clean(x):
        if isinstance(x, Decimal):return str(x)
        if isinstance(x, date) and not isinstance(x, datetime):return x.isoformat()
        if isinstance(x,dict):return {k:clean(v) for k,v in x.items()}
        if isinstance(x,list):return [clean(v) for v in x]
        return x
    with path.open('x',encoding='utf-8') as f:
        f.write(json_util.dumps(clean(value),ensure_ascii=False,indent=2));f.flush();os.fsync(f.fileno())

def main(a):
    manifest=json.loads((a.root/'manifest.json').read_text())
    assert manifest['document_version']=='natural-contract-v2' and manifest['simulated'] and manifest['not_real_rights']
    source_path=a.root/'source.private.json'
    assert hashlib.sha256(source_path.read_bytes()).hexdigest()==manifest['source_sha256']
    source=json_util.loads(source_path.read_text(),json_options=json_util.JSONOptions(tz_aware=True))
    expected={str(t['_id']):t for t in source['tracks']}
    rows=manifest['rows'];assert len(rows)==len({r['track_id'] for r in rows})
    for row in rows:
        assert row['track_id'] in expected and row['license_type']=='DEMO_SIMULATED' and row['pages']==3
        assert row['document_url']=='/uploads/licenses/demo-20261106-v2/'+row['track_id']+'.pdf'
        assert hashlib.sha256((a.documents/(row['track_id']+'.pdf')).read_bytes()).hexdigest()==row['pdf_sha256']
    if not a.apply:print(json.dumps({'read_only':True,'proposed':len(rows),'document_revision_only':True}));return
    state=a.root/'journal';state.mkdir(exist_ok=True)
    sql,mongo,client=worker.connections(Path('/dev/null'))
    journal=(state/'operations.jsonl').open('a',encoding='utf-8')
    def event(stage,**kw):
        journal.write(json.dumps({'at':datetime.now(timezone.utc).isoformat(),'stage':stage,**kw})+'\n');journal.flush();os.fsync(journal.fileno())
    def license_by_id(row,lock=False):
        with sql.cursor() as c:
            c.execute('SELECT * FROM song_licenses WHERE id=%s'+(' FOR UPDATE' if lock else ''),(row['license_id'],));return c.fetchone()
    try:
        with sql.cursor() as c:
            c.execute("SELECT GET_LOCK('moodify_demo_contract_revision',0) acquired");assert c.fetchone()['acquired']==1
        sql.rollback()
        completed=0
        for row in rows:
            key=row['track_id'];before=mongo.tracks.find_one({'_id':expected[key]['_id']})
            assert before and before.get('status')=='published' and before.get('visibility')=='public'
            demo=before.get('recommendation_demo') or {}
            assert demo.get('simulated') is True and demo.get('batch_id')==manifest['batch_id'] and demo.get('license_id')==row['license_id']
            saved=license_by_id(row);sql.rollback()
            assert saved and saved['track_id']==key and saved['license_type']=='DEMO_SIMULATED' and saved['status']=='ACTIVE'
            assert saved['distribution_contract_code']==row['contract_code']
            assert str(saved['issue_date'])==row['issue_date'] and str(saved['expiry_date'])==row['expiry_date'] and saved['acquisition_cost_vnd']==0
            if saved['document_songlicenses_url']==row['document_url']:
                assert saved['copyright_owner']==row['copyright_owner'] and demo.get('document_sha256')==row['pdf_sha256'] and demo.get('document_version')==manifest['document_version']
                event('RESUME_VERIFIED',track_id=key);completed+=1;continue
            assert saved['document_songlicenses_url']==row['previous_document_url'] and saved['copyright_owner']==row['previous_copyright_owner']
            assert demo.get('document_sha256')==row['previous_pdf_sha256']
            for field in ['name','artist_name','album_name','duration_ms','featured_artists','spotify_id','catalog_source_spotify_id','recommendation_demo','updated_at']:
                assert before.get(field)==expected[key].get(field),(key,field,'Concurrent change')
            attempt=uuid.uuid4().hex
            freeze(state/(key+'-'+attempt+'-before.json'),{'track':before,'license':saved,'proposed':row})
            now=datetime.now(timezone.utc);new_demo={**demo,'document_sha256':row['pdf_sha256'],'document_version':manifest['document_version'],'document_revised_at':now}
            changed=False;committed=False
            try:
                sql.begin();locked=license_by_id(row,True);assert locked==saved,'Concurrent SQL change'
                with sql.cursor() as c:
                    c.execute("SELECT IS_USED_LOCK('moodify_demo_contract_revision')=CONNECTION_ID() owned");assert c.fetchone()['owned']==1
                    c.execute('UPDATE song_licenses SET document_songlicenses_url=%s,copyright_owner=%s WHERE id=%s AND license_type=%s AND document_songlicenses_url=%s AND copyright_owner=%s',
                        (row['document_url'],row['copyright_owner'],row['license_id'],'DEMO_SIMULATED',row['previous_document_url'],row['previous_copyright_owner']))
                    assert c.rowcount==1
                event('SQL_PREPARED',track_id=key,license_id=row['license_id'])
                result=mongo.tracks.update_one({'_id':before['_id'],'updated_at':before.get('updated_at'),'recommendation_demo':demo},
                    {'$set':{'recommendation_demo':new_demo,'updated_at':now}})
                assert result.matched_count==1,'Mongo CAS failed';changed=True
                after=mongo.tracks.find_one({'_id':before['_id']})
                ignore={'recommendation_demo','updated_at'}
                assert {k:v for k,v in before.items() if k not in ignore}=={k:v for k,v in after.items() if k not in ignore},'Unrelated metadata changed'
                sql.commit();committed=True
                verified=license_by_id(row);sql.rollback()
                allowed={'document_songlicenses_url','copyright_owner','updated_at'}
                assert {k:v for k,v in saved.items() if k not in allowed}=={k:v for k,v in verified.items() if k not in allowed}
                assert verified['document_songlicenses_url']==row['document_url'] and verified['copyright_owner']==row['copyright_owner']
                freeze(state/(key+'-'+attempt+'-verified.json'),{'license':verified,'demo':new_demo,'preserved_audio_lyrics_terms':True})
                event('PUBLISHED',track_id=key,license_id=row['license_id']);completed+=1
            except Exception:
                sql.rollback()
                current=license_by_id(row);sql.rollback()
                if changed and not committed and current['document_songlicenses_url']==row['previous_document_url']:
                    reverted=mongo.tracks.update_one({'_id':before['_id'],'recommendation_demo.document_sha256':row['pdf_sha256'],'updated_at':now},
                        {'$set':{'recommendation_demo':demo,'updated_at':before.get('updated_at')}})
                    event('MONGO_COMPENSATED' if reverted.matched_count else 'NEEDS_RECONCILIATION',track_id=key)
                else:event('NEEDS_RECONCILIATION',track_id=key)
                raise
        summary={'published':completed,'document_version':manifest['document_version'],'simulated':True,'preserved_audio_lyrics_terms':True}
        freeze(state/('summary-'+uuid.uuid4().hex+'.json'),summary);print(json.dumps(summary))
    finally:sql.rollback();sql.close();client.close();journal.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--documents',type=Path,required=True);p.add_argument('--apply',action='store_true');main(p.parse_args())
