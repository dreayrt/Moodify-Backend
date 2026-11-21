"""Explicitly authorized demo batch; immutable snapshots, parameterized SQL, Mongo CAS and read-back."""
import argparse
from datetime import datetime,date,timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid
from decimal import Decimal
sys.path.insert(0,os.getcwd())
from bson import json_util
import worker

def frozen(path,value):
    with path.open('x',encoding='utf-8') as f:f.write(json_util.dumps(value,ensure_ascii=False,indent=2));f.flush();os.fsync(f.fileno())

def run(args):
    manifest=json.loads((args.root/'manifest.json').read_text(encoding='utf-8'))
    source_path=args.root/'source.private.json'
    assert hashlib.sha256(source_path.read_bytes()).hexdigest()==manifest['source_sha256']
    source=json_util.loads(source_path.read_text(encoding='utf-8'),json_options=json_util.JSONOptions(tz_aware=True))
    assert manifest['simulated'] and manifest['not_real_rights'] and manifest['batch_id']=='demo-rights-20261106'
    originals={str(t['_id']):t for t in source['tracks']}
    assert len(originals)==len(manifest['rows'])==426
    for row in manifest['rows']:
        assert row['track_id'] in originals and row['license_type']=='DEMO_SIMULATED' and row['simulated']
        assert hashlib.sha256((args.documents/(row['track_id']+'.pdf')).read_bytes()).hexdigest()==row['pdf_sha256']
    if not args.apply:print(json.dumps({'read_only':True,'proposed':len(originals),'simulated':True}));return
    state=args.root/'journal';state.mkdir(exist_ok=True)
    sql,mongo,client=worker.connections(Path('/dev/null'))
    log=(state/'operations.jsonl').open('a',encoding='utf-8')
    def event(stage,**values):
        log.write(json.dumps({'at':datetime.now(timezone.utc).isoformat(),'stage':stage,**values})+'\n');log.flush();os.fsync(log.fileno())
    published=0
    try:
        with sql.cursor() as c:
            c.execute("SELECT GET_LOCK('moodify_demo_rights_publish',0) acquired")
            assert c.fetchone()['acquired']==1,'Another publisher holds the lock'
        for row in manifest['rows']:
            key=row['track_id'];original=originals[key]
            before=mongo.tracks.find_one({'_id':original['_id']})
            assert before,'Track missing'
            assert before.get('status')=='published' and before.get('visibility')=='public'
            with sql.cursor() as c:
                c.execute('SELECT * FROM song_licenses WHERE track_id=%s AND distribution_contract_code=%s AND license_type=%s',
                    (key,row['contract_code'],'DEMO_SIMULATED'))
                previous=c.fetchall()
            sql.rollback()
            if previous:
                assert len(previous)==1 and previous[0]['status']=='ACTIVE' and previous[0]['document_songlicenses_url']==row['document_url']
                assert (before.get('recommendation_demo') or {}).get('batch_id')==manifest['batch_id'],'Partial batch requires journal reconciliation'
                assert (before.get('recommendation_demo') or {}).get('license_id')==previous[0]['id']
                published+=1;event('RESUME_VERIFIED',track_id=key,license_id=previous[0]['id']);continue
            # A change to publication/audio/moderation since the authorized source snapshot stops this job.
            for field in ['status','visibility','local_path','audio_hash','moderation_status','recommendation_demo','updated_at']:
                assert before.get(field)==original.get(field),f'Concurrent metadata change for {key}: {field}'
            attempt=uuid.uuid4().hex
            with sql.cursor() as c:
                c.execute('SELECT * FROM song_licenses WHERE track_id=%s ORDER BY id',(key,))
                old_licenses=c.fetchall()
            for old in old_licenses:
                for field,value in old.items():
                    if isinstance(value,date) and not isinstance(value,datetime):old[field]=value.isoformat()
                    elif isinstance(value,Decimal):old[field]=str(value)
            sql.rollback()
            known_ids={l['id'] for l in source['licenses'] if l['track_id']==key}
            assert {l['id'] for l in old_licenses}==known_ids,'License records changed since source snapshot'
            frozen(state/(key+'-'+attempt+'-before.json'),{'track':before,'licenses':old_licenses,'proposed':row})
            try:
                sql.begin()
                with sql.cursor() as c:
                    c.execute('SELECT IS_USED_LOCK(\'moodify_demo_rights_publish\')=CONNECTION_ID() owned')
                    assert c.fetchone()['owned']==1,'Publisher lock lost'
                    c.execute('INSERT INTO song_licenses(track_id,distribution_contract_code,license_type,copyright_owner,status,issue_date,expiry_date,document_songlicenses_url,acquisition_cost_vnd) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,0)',
                        (key,row['contract_code'],'DEMO_SIMULATED',row['copyright_owner'],'ACTIVE',row['issue_date'],row['expiry_date'],row['document_url']))
                    license_id=c.lastrowid
                now=datetime.now(timezone.utc)
                demo={'simulated':True,'batch_id':manifest['batch_id'],'license_id':license_id,'document_sha256':row['pdf_sha256'],
                    'at':now,'moderation_source':'USER_AUTHORIZED_DEMO_SIMULATION'}
                event('SQL_PREPARED',track_id=key,license_id=license_id)
                cas={'_id':before['_id'],**{f:before.get(f) for f in ['status','visibility','local_path','audio_hash','moderation_status','recommendation_demo','updated_at']}}
                changed=mongo.tracks.update_one(cas,{'$set':{'moderation_status':'approved','recommendation_demo':demo,'updated_at':now}})
                assert changed.matched_count==1,'Mongo compare-and-set failed'
                after=mongo.tracks.find_one({'_id':before['_id']})
                ignore={'moderation_status','recommendation_demo','updated_at'}
                assert {k:v for k,v in before.items() if k not in ignore}=={k:v for k,v in after.items() if k not in ignore},'Unrelated metadata changed'
                assert after['moderation_status']=='approved' and after['recommendation_demo']['license_id']==license_id
                sql.commit()
                with sql.cursor() as c:
                    c.execute('SELECT id,track_id,license_type,status,document_songlicenses_url FROM song_licenses WHERE id=%s',(license_id,));saved=c.fetchone()
                sql.rollback()
                assert saved['track_id']==key and saved['license_type']=='DEMO_SIMULATED' and saved['status']=='ACTIVE' and saved['document_songlicenses_url']==row['document_url']
                frozen(state/(key+'-'+attempt+'-verified.json'),{'verified':True,'license':saved,'preserved_audio_lyrics_metadata':True,'demo':demo})
                event('PUBLISHED',track_id=key,license_id=license_id);published+=1
            except Exception:
                sql.rollback();event('NEEDS_RECONCILIATION',track_id=key);raise
        mixes=list(mongo.recommendation_state.find({'kind':'MIX'}))
        mix_snapshot=state/('mixes-'+uuid.uuid4().hex+'-before.json');frozen(mix_snapshot,mixes)
        for mix in mixes:
            mongo.recommendation_state.update_one({'_id':mix['_id'],'kind':'MIX','active_version_id':mix.get('active_version_id'),
                'refresh_due_at':mix.get('refresh_due_at')},{'$set':{'refresh_due_at':datetime(1970,1,1,tzinfo=timezone.utc),'rebuild_nonce':manifest['batch_id']}})
        summary={'published':published,'simulated':True,'batch_id':manifest['batch_id'],'audio_lyrics_preserved':True,'new_mysql_tables':0}
        frozen(state/('summary-'+uuid.uuid4().hex+'.json'),summary);print(json.dumps(summary))
    finally:
        sql.rollback();sql.close();client.close();log.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--documents',type=Path,required=True);p.add_argument('--apply',action='store_true');run(p.parse_args())
