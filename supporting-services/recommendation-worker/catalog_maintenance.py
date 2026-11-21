"""Read-only readiness and resumable, explicitly applied catalog feature maintenance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import time
from contextlib import closing
from urllib.request import Request, urlopen
from urllib.parse import urljoin, urlparse
import uuid
from bson import json_util
import worker

FIELDS = ('valence', 'energy', 'danceability', 'acousticness', 'bpm')

def valid_features(features):
    return all(isinstance(features.get(k), (float, int)) and not isinstance(features.get(k), bool)
        and math.isfinite(features[k]) and (0 < features[k] <= 300 if k == 'bpm' else 0 <= features[k] <= 1) for k in FIELDS)


def verified_analysis(result, analyzer_version):
    return isinstance(result,dict) and valid_features(result) and not result.get('note') and (
        result.get('version')==analyzer_version and result.get('assessment')=='estimated_not_listener_verified')


def source_path(path):
    if not isinstance(path, str) or not path.strip(): raise ValueError('missing_audio')
    path = path.strip().replace('\\', '/')
    if '..' in path.split('/') or '\0' in path or '://' in path: raise ValueError('unsafe_audio_path')
    return path.lstrip('/')

def journal(path, value):
    # Immutable per-attempt journal, fsynced before any database mutation.
    with path.open('x', encoding='utf-8') as out:
        out.write(json_util.dumps(value, ensure_ascii=False, indent=2));out.flush();os.fsync(out.fileno())

def audit(sql, mongo, output):
    now=datetime.now(timezone.utc)
    sql.rollback()
    try:
        with sql.cursor() as cursor:
            cursor.execute('START TRANSACTION READ ONLY')
            cursor.execute('SELECT id,track_id,status,issue_date,expiry_date,license_type FROM song_licenses')
            licenses=cursor.fetchall()
        tracks=list(mongo.tracks.find({}, {'name':1,'artist_name':1,'artist_ids':1,'artist_spotify_id':1,'spotify_id':1,
            'status':1,'visibility':1,'moderation_status':1,'local_path':1,'audio_features':1,'recommendation_demo':1}))
        eligible=worker.eligible_track_ids(tracks, licenses, now)
        demo_eligible=worker.eligible_track_ids(tracks, licenses, now,True)
        licensed=worker.effective_licensed_tracks(licenses,worker.canonical_tracks(tracks),now.astimezone(worker.ZoneInfo('Asia/Ho_Chi_Minh')).date())
        rows=[]
        for t in tracks:
            issues=[]
            for field, expected in [('status','published'),('visibility','public'),('moderation_status','approved')]:
                if str(t.get(field,'')).lower()!=expected: issues.append('VERIFY_'+field.upper())
            try:source_path(t.get('local_path'))
            except ValueError:issues.append('VERIFY_AUDIO')
            if str(t['_id']) not in licensed:issues.append('VERIFY_EFFECTIVE_LICENSE')
            rows.append({'track_id':str(t['_id']),'name':t.get('name'),'artist':t.get('artist_name'),
                'rights_eligible':str(t['_id']) in eligible,'demo_eligible':str(t['_id']) in demo_eligible,
                'complete_mood_features':valid_features(t.get('audio_features') or {}),
                'verified_artist_link':bool(t.get('artist_ids') or t.get('artist_spotify_id')),'issues':issues})
        report={'at':now.isoformat(),'read_only':True,'policy_version':'strict-rights-v1.3','total':len(rows),
            'eligible':len(eligible),'demo_eligible':len(demo_eligible),
            'release_status':'READY_FOR_PILOT' if eligible else 'WAITING_FOR_LICENSED_CATALOG','tracks':rows}
        output.mkdir(parents=True,exist_ok=True)
        (output/'catalog-readiness.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k!='tracks'}))
    finally:sql.rollback()

def features(mongo, args):
    args.output.mkdir(parents=True,exist_ok=True)
    with closing(sqlite3.connect(args.output/'features.sqlite')) as queue:
        return run_feature_batch(mongo, args, queue)


def run_feature_batch(mongo, args, queue):
    queue.execute("""CREATE TABLE IF NOT EXISTS jobs (
        id TEXT PRIMARY KEY, audio_hash TEXT, state TEXT, attempts INTEGER DEFAULT 0,
        error_type TEXT, next_attempt_at INTEGER NOT NULL DEFAULT 0, analyzer_version TEXT)""")
    columns={column[1] for column in queue.execute('PRAGMA table_info(jobs)')}
    if 'next_attempt_at' not in columns:
        queue.execute('ALTER TABLE jobs ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0')
    if 'analyzer_version' not in columns:
        queue.execute('ALTER TABLE jobs ADD COLUMN analyzer_version TEXT')
    candidates=list(mongo.tracks.find({'status':'published','visibility':'public'}, {
        'audio_hash':1,'audio_features':1,'audio_features_schema_provenance.recommendation_analysis':1,
        'local_path':1,'moderation_status':1,
    }))
    by_id={}
    missing_hash=stale_features=0
    for t in candidates:
        key=str(t['_id'])
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',key):
            raise ValueError('unsafe_track_identifier')
        audio_hash=t.get('audio_hash')
        provenance=(t.get('audio_features_schema_provenance') or {}).get('recommendation_analysis') or {}
        if valid_features(t.get('audio_features') or {}):
            if provenance.get('audio_hash') and provenance['audio_hash']!=audio_hash:
                stale_features+=1
            continue
        if not isinstance(audio_hash,str) or not audio_hash:
            missing_hash+=1
            continue
        by_id[key]=t
        queue.execute("""INSERT INTO jobs(id,audio_hash,state,analyzer_version) VALUES (?,?,'pending',?)
            ON CONFLICT(id) DO UPDATE SET audio_hash=excluded.audio_hash, state='pending',
                attempts=0, error_type=NULL, next_attempt_at=0, analyzer_version=excluded.analyzer_version
            WHERE jobs.audio_hash IS NOT excluded.audio_hash
                OR jobs.analyzer_version IS NOT excluded.analyzer_version
                OR jobs.state IN ('published','already_complete')""",
            (key,audio_hash,args.analyzer_version))
    queue.commit()
    due=queue.execute("""SELECT id,audio_hash,attempts,state FROM jobs
        WHERE state IN ('pending','retry_wait','analyzed') AND next_attempt_at<=?
        ORDER BY next_attempt_at,id""",(int(time.time()),)).fetchall()
    due=[job for job in due if job[0] in by_id and (args.apply or job[3]!='analyzed')]
    # An existing approved candidate takes precedence over an unreviewed one.
    due.sort(key=lambda job: by_id[job[0]].get('moderation_status')!='approved')
    for key, expected_hash, attempts, _ in due[:getattr(args,'max_jobs',20)]:
        t=by_id[key]
        directory=args.output/key;directory.mkdir(exist_ok=True)
        attempt=uuid.uuid4().hex
        try:
            cache_key=hashlib.sha256(f'{expected_hash}:{args.analyzer_version}'.encode()).hexdigest()
            response_path=directory/f'analyzer-response-{cache_key}.json'
            result=None
            cached=False
            for path in (response_path,directory/'analyzer-response.json'):
                try:
                    saved=json.loads(path.read_text(encoding='utf-8'))
                except (FileNotFoundError,ValueError):
                    continue
                if (isinstance(saved,dict) and saved.get('audio_hash')==expected_hash
                    and saved.get('analyzer_version')==args.analyzer_version
                    and verified_analysis(saved.get('features'),args.analyzer_version)):
                    result=saved['features']
                    cached=path==response_path
                    break
            if result is None:
                if not re.fullmatch(r'[0-9a-f]{64}',expected_hash):
                    raise ValueError('invalid_audio_sha256')
                path=source_path(t.get('local_path'))
                url=urljoin(args.audio_base.rstrip('/')+'/',path)
                origin=lambda value: (urlparse(value).scheme,urlparse(value).netloc)
                if origin(url)!=origin(args.audio_base):raise ValueError('audio_origin_changed')
                with urlopen(url,timeout=40) as r:
                    if origin(r.geturl())!=origin(args.audio_base):raise ValueError('audio_origin_changed')
                    data=r.read(64*1024*1024+1)
                if len(data)>64*1024*1024:raise ValueError('audio_too_large')
                if hashlib.sha256(data).hexdigest()!=expected_hash:raise ValueError('audio_hash_mismatch')
                boundary='moodify-'+uuid.uuid4().hex
                body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="audio.mp3"\r\nContent-Type: audio/mpeg\r\n\r\n'.encode()+data+f'\r\n--{boundary}--\r\n'.encode())
                req=Request(args.analyzer.rstrip('/')+'/analyze',data=body,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
                with urlopen(req,timeout=130) as r:result=json.loads(r.read(64*1024+1))
            if not verified_analysis(result,args.analyzer_version):
                raise ValueError('unversioned_or_fallback_analysis_requires_review')
            if not cached:
                temporary=directory/(attempt+'-response.tmp')
                journal(temporary,{'features':result,'audio_hash':expected_hash,'analyzer_version':args.analyzer_version})
                temporary.replace(response_path)
            updates={k:result[k] for k in FIELDS}
            if isinstance(result.get('keySignature'),str):updates['key_signature']=result['keySignature']
            provenance={'analyzer_version':args.analyzer_version,'audio_hash':expected_hash,'at':datetime.now(timezone.utc),
                'method':'self_hosted_audio_analysis','assessment':'estimated_not_listener_verified'}
            if args.apply:
                before=mongo.tracks.find_one({'_id':t['_id']})
                if not before or before.get('audio_hash')!=expected_hash or before.get('local_path')!=t.get('local_path'):
                    raise ValueError('audio_changed')
                if before.get('status')!='published' or before.get('visibility')!='public':
                    raise ValueError('track_no_longer_public')
                if valid_features(before.get('audio_features') or {}):
                    queue.execute("UPDATE jobs SET state='already_complete' WHERE id=?",(key,));queue.commit();continue
                merged=dict(before.get('audio_features') or {});merged.update(updates)
                journal(directory/(attempt+'-before.json'),{'before':before,'proposed_features':merged,'provenance':provenance})
                changed=mongo.tracks.update_one({'_id':before['_id'],'audio_hash':expected_hash,'audio_features':before.get('audio_features'),
                    'status':'published','visibility':'public','local_path':before.get('local_path')},
                    {'$set':{'audio_features':merged,'audio_features_schema_provenance.recommendation_analysis':provenance}})
                after=mongo.tracks.find_one({'_id':before['_id']})
                if changed.modified_count!=1 or after.get('audio_features')!=merged:raise ValueError('compare_and_set_conflict')
                ignored={'audio_features','audio_features_schema_provenance'}
                if {k:v for k,v in before.items() if k not in ignored}!={k:v for k,v in after.items() if k not in ignored}:raise ValueError('unrelated_fields_changed')
                journal(directory/(attempt+'-verification.json'),{'verified':True,'audio_hash':expected_hash,'features':merged})
            queue.execute('UPDATE jobs SET state=?,attempts=0,error_type=NULL,next_attempt_at=0 WHERE id=?',('published' if args.apply else 'analyzed',key))
        except Exception as error:
            retry_at=int(time.time())+min(86400,300*2**min(attempts,9))
            reason=str(error) if type(error) is ValueError else type(error).__name__
            queue.execute("UPDATE jobs SET state='retry_wait',attempts=attempts+1,error_type=?,next_attempt_at=? WHERE id=?",
                (reason,retry_at,key))
        queue.commit()
        print(json.dumps({'id':key,'state':queue.execute('SELECT state FROM jobs WHERE id=?',(key,)).fetchone()[0]}),flush=True)
    states={}
    for key,state in queue.execute('SELECT id,state FROM jobs'):
        if key in by_id:
            states[state]=states.get(state,0)+1
    status={'at':datetime.now(timezone.utc).isoformat(),'public':len(candidates),'missing_features':len(by_id)+missing_hash,
        'missing_hash':missing_hash,'stale_features_review':stale_features,'due':len(due),
        'mode':'apply' if args.apply else 'analysis','states':states}
    temporary=args.output/'status.tmp'
    temporary.write_text(json.dumps(status),encoding='utf-8')
    temporary.replace(args.output/'status.json')
    print(json.dumps(status),flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['audit','features']);p.add_argument('--env',type=Path)
    p.add_argument('--mongo-uri');p.add_argument('--database',default='music_streaming');
    p.add_argument('--output',type=Path,required=True);p.add_argument('--audio-base',default='https://moodify.click/')
    p.add_argument('--analyzer',default='http://127.0.0.1:18002');p.add_argument('--analyzer-version');p.add_argument('--apply',action='store_true')
    p.add_argument('--max-jobs',type=int,default=20)
    args=p.parse_args()
    if args.mode=='features' and not args.analyzer_version:p.error('--analyzer-version is required for feature provenance')
    if not 1<=args.max_jobs<=100:p.error('--max-jobs must be between 1 and 100')
    if args.mode=='features' and args.mongo_uri:
        from pymongo import MongoClient
        client=MongoClient(args.mongo_uri,serverSelectionTimeoutMS=8000,timeoutMS=20000)
        try:features(client[args.database],args)
        finally:client.close()
    else:
        if not args.env:p.error('--env is required for SQL/Mongo readiness audit')
        sql,mongo,client=worker.connections(args.env)
        try:audit(sql,mongo,args.output) if args.mode=='audit' else features(mongo,args)
        finally:sql.close();client.close()

if __name__=='__main__':main()
