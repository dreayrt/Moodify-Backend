"""Export the exact labelled cohort inventory, omitting passwords and bearer tokens."""
import argparse
from datetime import datetime,timezone,date
from decimal import Decimal
import json,os,re,sys
from pathlib import Path
sys.path.insert(0,os.getcwd())
import worker
from bson import json_util

SAFE={'id','user_id','owner_user_id','listener_user_id','username','email','full_name','role','status','created_at','updated_at','last_login_at',
    'track_id','artist_id','album_id','playlist_id','session_key','listening_history_id','event_type','position_ms','target_position_ms',
    'started_at','ended_at','listened_duration_ms','last_position_ms','source','source_id','device_type','device_name','platform','last_seen_at','continuous_ms','playing','closed'}
def value(x):
    if isinstance(x,(datetime,date)):return x.isoformat()
    if isinstance(x,Decimal):return str(x)
    return x

def main(a):
    manifest=json_util.loads((a.state/'seed-manifest.json').read_text())
    users=[x['id'] for x in manifest['accounts']];assert len(users)==3
    sql,mongo,client=worker.connections(Path('/dev/null'))
    try:
        with sql.cursor() as c:
            c.execute('START TRANSACTION READ ONLY')
            c.execute('SELECT id,username,email,full_name,role,status,created_at FROM users WHERE id IN (%s,%s,%s)',users)
            accounts=c.fetchall();assert len(accounts)==3 and all(u['username'].startswith('rec_seed_') and u['email'].endswith('@recommendation-seed.invalid') for u in accounts)
            c.execute("SELECT TABLE_NAME,COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('user_id','owner_user_id','listener_user_id') ORDER BY TABLE_NAME,COLUMN_NAME")
            refs=c.fetchall();tables={}
            for ref in refs:
                name=ref['TABLE_NAME'];column=ref['COLUMN_NAME']
                assert re.fullmatch('[a-zA-Z0-9_]+',name) and re.fullmatch('[a-z_]+',column)
                if name in tables:continue
                c.execute(f'SELECT * FROM `{name}` WHERE `{column}` IN (%s,%s,%s) LIMIT 1001',users)
                rows=c.fetchall();assert len(rows)<=1000
                tables[name]={'rows':len(rows),'records':[{k:value(v) for k,v in row.items() if k in SAFE} for row in rows]}
            for table in ['playback_events','revenue_playback_sessions']:
                c.execute(f'SELECT p.* FROM `{table}` p JOIN listening_history h ON h.id=p.listening_history_id WHERE h.user_id IN (%s,%s,%s) ORDER BY h.id LIMIT 1001',users)
                rows=c.fetchall();assert len(rows)<=1000
                tables[table]={'rows':len(rows),'records':[{k:value(v) for k,v in row.items() if k in SAFE} for row in rows]}
        sql.rollback()
        assert tables.get('qualified_streams',{}).get('rows',0)==0
        documents={}
        for collection in ['recommendation_state','recommendation_events']:
            docs=list(mongo[collection].find({'user_id':{'$in':users}},{'_id':1,'kind':1,'user_id':1,'entity_id':1,'entity_type':1,'feedback_type':1,'created_at':1,'occurred_at':1,'updated_at':1}))
            documents[collection]=[{k:value(v) for k,v in d.items()} for d in docs]
        playlists=list(mongo.playlists.find({'owner_user_id':{'$in':users}},{'_id':1,'name':1,'owner_user_id':1,'is_public':1,'generated_source_id':1,'generated_source_version':1,'track_spotify_ids':1}))
        for doc in playlists:doc['_id']=str(doc['_id'])
        documents['playlists']=playlists
        result={'batch_id':manifest['batch_id'],'synthetic':True,'at':datetime.now(timezone.utc).isoformat(),'accounts':[{k:value(v) for k,v in u.items()} for u in accounts],
            'initial_backfill_counts':manifest['counts'],'sql':tables,'mongo':documents,
            'backfilled_history_ids':[h['id'] for h in manifest['histories']],
            'observed_test_history_ids':[r['id'] for r in tables['listening_history']['records'] if r['id'] not in {h['id'] for h in manifest['histories']}],
            'no_passwords_or_tokens_exported':True,'financial_rows':0,'cleanup_scope':{'sql_user_ids':users,'mongo_user_ids':users,'cohort_id':'test-cohort:'+manifest['batch_id']}}
        (a.state/'data-inventory.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'sql_counts':{k:v['rows'] for k,v in tables.items() if v['rows']},'mongo_counts':{k:len(v) for k,v in documents.items()},
            'observed_test_history_ids':result['observed_test_history_ids'],'financial_rows':0},ensure_ascii=False))
    finally:sql.rollback();sql.close();client.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);main(p.parse_args())
