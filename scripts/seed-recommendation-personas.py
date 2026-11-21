"""Explicitly authorized, labelled production test cohort, never real-user evidence.

Run inside the worker image with ephemeral publisher credentials. No schema changes,
no real-account mutations, no qualified streams, no model training. API side effects
and backfilled synthetic histories have separate provenance and immutable journals.
"""
import argparse
from datetime import datetime,timedelta,timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
import uuid
from urllib.request import Request,urlopen
sys.path.insert(0,os.getcwd())
import worker
from bson import json_util

BATCH='rec-seed-20261107-v1'
DOMAIN='@recommendation-seed.invalid'
PROFILES=[('new','Người mới'),('calm','Thích nhạc nhẹ'),('energy','Thích nhạc sôi động')]

def frozen(path,data):
    with path.open('x',encoding='utf-8') as f:
        f.write(json_util.dumps(data,ensure_ascii=False,indent=2));f.flush();os.fsync(f.fileno())

def request(path,body=None,auth=None,method=None):
    headers={'Content-Type':'application/json'}
    if auth:headers.update(Authorization='Bearer '+auth['accessToken'],**{'X-Device-Uuid':auth['deviceUuid']})
    with urlopen(Request('http://moodify-backend:8080/api/'+path,data=None if body is None else json.dumps(body).encode(),
        headers=headers,method=method),timeout=30) as r:
        raw=r.read();return json.loads(raw) if raw else None

def main(a):
    a.state.mkdir(parents=True,exist_ok=True)
    private=a.state/'credentials.private.json'
    if private.exists():credentials=json.loads(private.read_text())
    else:
        credentials={key:{'username':'rec_seed_20261107_'+key,'email':'rec_seed_20261107_'+key+DOMAIN,
            'password':secrets.token_urlsafe(24),'deviceUuid':str(uuid.uuid5(uuid.NAMESPACE_URL,BATCH+':'+key))} for key,_ in PROFILES}
        frozen(private,credentials);private.chmod(0o600)
    sql,mongo,client=worker.connections(Path('/dev/null'))
    log=(a.state/'operations.jsonl').open('a',encoding='utf-8')
    def event(stage,**data):
        log.write(json.dumps({'at':datetime.now(timezone.utc).isoformat(),'stage':stage,**data},ensure_ascii=False,default=str)+'\n');log.flush();os.fsync(log.fileno())
    try:
        with sql.cursor() as c:
            c.execute('SELECT GET_LOCK(%s,0) owned',(BATCH,));assert c.fetchone()['owned']==1
        existing=mongo.recommendation_state.find_one({'_id':'test-cohort:'+BATCH})
        assert not existing,'Batch already exists; use the manifest and resume verifier, do not reseed'
        for key,label in PROFILES:
            cred=credentials[key]
            with sql.cursor() as c:
                c.execute('SELECT id,username,email FROM users WHERE username=%s OR email=%s',(cred['username'],cred['email']));rows=c.fetchall()
            sql.rollback();assert not rows,'Existing account requires journal reconciliation'
        with sql.cursor() as c:
            c.execute('SELECT id,track_id,status,issue_date,expiry_date,license_type FROM song_licenses');licenses=c.fetchall()
        sql.rollback()
        tracks=list(mongo.tracks.find({}))
        ids=worker.eligible_track_ids(tracks,licenses,datetime.now(timezone.utc),True)
        pool=[t for t in tracks if str(t['_id']) in ids and (t.get('duration_ms') or 0)>90000 and (t.get('artist_ids') or t.get('artist_spotify_id'))]
        def score(t):
            f=t.get('audio_features') or {}
            return float(f.get('energy') or 0)+float(f.get('danceability') or 0)-float(f.get('acousticness') or 0)
        def select(reverse,excluded):
            result=[];artists={}
            for t in sorted(pool,key=lambda t:(score(t),str(t['_id'])),reverse=reverse):
                primary=t.get('artist_spotify_id') or t.get('artist_ids',[None])[0]
                if str(t['_id']) in excluded or artists.get(primary,0)>=2:continue
                result.append(t);artists[primary]=artists.get(primary,0)+1
                if len(result)==6:return result
            raise RuntimeError('Not enough distinct eligible recordings for this bounded cohort')
        calm=select(False,set());energetic=select(True,{str(t['_id']) for t in calm})
        cohort={'batch_id':BATCH,'synthetic':True,'kind':'TEST_COHORT','purpose':'USER_AUTHORIZED_FUNCTIONAL_VERIFICATION',
            'excluded_from_revenue_trends_training':True,'created_at':datetime.now(timezone.utc),'accounts':[],
            'histories':[],'library_actions':[],'follow_actions':[],'api_checks':[],'catalog_changed':False}
        frozen(a.state/'before.json',{'synthetic_accounts_before':[],'chosen_track_ids':[str(t['_id']) for t in calm+energetic]})
        for index,(key,label) in enumerate(PROFILES):
            cred=credentials[key]
            event('ACCOUNT_PREPARED',profile=key,username=cred['username'])
            request('auth/register',{'fullName':'[SEED] '+label,'phone':'0000001'+str(index+1).zfill(3),'email':cred['email'],
                'username':cred['username'],'password':cred['password'],'confirmPassword':cred['password'],'role':'USER'})
            auth=request('auth/login',{'identifier':cred['username'],'password':cred['password'],'deviceUuid':cred['deviceUuid'],
                'deviceName':'SEED recommendation verification','platform':'WEB'})
            cred.update(auth);cred['deviceUuid']=credentials[key]['deviceUuid']
            with sql.cursor() as c:
                c.execute('SELECT id,username,email,role,status FROM users WHERE username=%s',(cred['username'],));user=c.fetchone()
            sql.rollback();assert user['role']=='USER' and user['status']=='ACTIVE' and user['email']==cred['email'] and user['username'].startswith('rec_seed_')
            cred['userId']=user['id'];cohort['accounts'].append({**user,'profile':key,'synthetic':True,'label':label})
            event('ACCOUNT_CREATED',user_id=user['id'],profile=key)
        private.write_text(json.dumps(credentials,ensure_ascii=False,indent=2));private.chmod(0o600)
        mongo.recommendation_state.insert_one({'_id':'test-cohort:'+BATCH,**cohort,'user_ids':[x['id'] for x in cohort['accounts']],'status':'PREPARING'})
        # Cold start is observed before any synthetic preference or listening rows.
        for key,_ in PROFILES:
            home=request('recommendations/home',auth=credentials[key])
            assert home['coldStart'] is True
            frozen(a.state/(key+'-home-before.json'),home)
            cohort['api_checks'].append({'case':key+'_cold_start_before','passed':True})
        now=datetime.now(timezone.utc)
        for key,chosen in [('calm',calm),('energy',energetic)]:
            cred=credentials[key];user_id=cred['userId']
            with sql.cursor() as c:
                c.execute('SELECT COUNT(*) n FROM listening_history WHERE user_id=%s',(user_id,));assert c.fetchone()['n']==0
            sql.rollback();sql.begin()
            staged=[]
            try:
                for j in range(12):
                    track=chosen[j%6];track_id=str(track['_id']);skip=j>=10
                    duration=int(track['duration_ms']) if j==0 else min([130000,90000,50000,160000,70000,45000,110000,75000,55000,85000,4000,7000][j],int(track['duration_ms'])-5000)
                    ago=[7*86400,6*86400,5*86400,4*86400,3*86400,2*86400,86400,16*3600,60*60,40*60,30*60,15*60][j]
                    start=now-timedelta(seconds=ago);pause_ms=15000 if j==0 else 0
                    end=start+timedelta(milliseconds=duration+pause_ms)
                    final='SKIP_NEXT' if skip else 'COMPLETE' if duration==int(track['duration_ms']) else 'PLAYBACK_STOPPED'
                    session=str(uuid.uuid5(uuid.NAMESPACE_URL,BATCH+':'+key+':'+str(j)))
                    with sql.cursor() as c:
                        c.execute('INSERT INTO listening_history(user_id,track_id,started_at,ended_at,listened_duration_ms,last_position_ms,source,source_id,device_type) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                            (user_id,track_id,start.replace(tzinfo=None),end.replace(tzinfo=None),duration,duration,'OTHER',BATCH,'OTHER'));history_id=c.lastrowid
                        c.execute('INSERT INTO revenue_playback_sessions(session_key,listening_history_id,last_seen_at,continuous_ms,playing,closed) VALUES (%s,%s,%s,0,FALSE,TRUE)',(session,history_id,end.replace(tzinfo=None)))
                        events=[('PLAY',0,start)]
                        if j==0:events += [('PAUSE',30000,start+timedelta(seconds=30)),('RESUME',30000,start+timedelta(seconds=45))]
                        if final!='PLAYBACK_STOPPED':events.append((final,duration,end))
                        event_ids=[]
                        for kind,position,at in events:
                            c.execute('INSERT INTO playback_events(listening_history_id,event_type,position_ms,occurred_at) VALUES (%s,%s,%s,%s)',(history_id,kind,position,at.replace(tzinfo=None)))
                            event_ids.append({'id':c.lastrowid,'type':kind,'position_ms':position,'at':at.isoformat()})
                    staged.append({'id':history_id,'user_id':user_id,'profile':key,'track_id':track_id,'track_name':track['name'],
                        'artist':track.get('artist_name'),'started_at':start.isoformat(),'ended_at':end.isoformat(),'listened_duration_ms':duration,
                        'source':'OTHER','source_id':BATCH,'device_type':'OTHER','session_key':session,'final_action':final,'events':event_ids,
                        'provenance':'SYNTHETIC_BACKFILL_NOT_OBSERVED_PLAYBACK'})
                frozen(a.state/(key+'-sql-prepared.json'),staged);event('SQL_PREPARED',profile=key,history_ids=[h['id'] for h in staged])
                sql.commit();cohort['histories']+=staged;event('HISTORY_COMMITTED',profile=key,count=len(staged))
            except Exception:sql.rollback();event('SQL_NEEDS_RECONCILIATION',profile=key);raise
            for track in chosen[:3]:
                target=str(track['_id']);request('users/me/library/tracks/'+target,{},cred,'POST')
                cohort['library_actions'].append({'user_id':user_id,'track_id':target,'track_name':track['name'],'enabled':True})
                event('LIKE_CREATED',user_id=user_id,track_id=target)
            artists=[]
            for track in chosen:
                artist=(track.get('artist_ids') or [track.get('artist_spotify_id')])[0]
                if artist not in artists:artists.append(artist)
                if len(artists)==2:break
            for artist in artists:
                request('recommendations/feedback',{'entityType':'ARTIST','entityId':artist,'feedbackType':'FOLLOW','enabled':True},cred,'PUT')
                cohort['follow_actions'].append({'user_id':user_id,'artist_id':artist,'enabled':True});event('FOLLOW_CREATED',user_id=user_id,artist_id=artist)
        for key,_ in PROFILES:
            home=request('recommendations/home',auth=credentials[key]);frozen(a.state/(key+'-home-after.json'),home)
            assert home['coldStart']==(key=='new')
            items=[i for s in home['sections'] if s['id']=='for_you' for i in s['items']]
            assert len({i['entityId'] for i in items})==len(items)
            cohort['api_checks'].append({'case':key+'_profile_and_unique_home_after','passed':True,'coldStart':home['coldStart'],'tracks':[i['entityId'] for i in items]})
        user_ids=[x['id'] for x in cohort['accounts']]
        with sql.cursor() as c:
            c.execute('SELECT COUNT(*) n FROM qualified_streams WHERE listener_user_id IN (%s,%s,%s)',user_ids);assert c.fetchone()['n']==0
        sql.rollback();cohort['qualified_streams_created']=0;cohort['status']='SEEDED'
        cohort['counts']={'accounts':3,'backfilled_histories':len(cohort['histories']),'timing_sessions':len(cohort['histories']),
            'playback_events':sum(len(h['events']) for h in cohort['histories']),'library_likes':len(cohort['library_actions']),'artist_follows':len(cohort['follow_actions'])}
        mongo.recommendation_state.update_one({'_id':'test-cohort:'+BATCH,'status':'PREPARING'},{'$set':{'status':'SEEDED','counts':cohort['counts'],'updated_at':datetime.now(timezone.utc)}})
        frozen(a.state/'seed-manifest.json',cohort);print(json.dumps({'batch':BATCH,'counts':cohort['counts'],'accounts':cohort['accounts'],'qualified_streams_created':0},ensure_ascii=False))
    finally:sql.rollback();sql.close();client.close();log.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);main(p.parse_args())
