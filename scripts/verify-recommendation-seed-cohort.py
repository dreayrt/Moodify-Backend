"""One bounded functional pass using only the explicitly labelled seed accounts."""
import argparse
import base64
from datetime import datetime,timezone
import hashlib,hmac,json,os,sys,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
sys.path.insert(0,os.getcwd())
import worker
from seed_recommendation_personas import BATCH,request,frozen

def main(a):
    credentials=json.loads((a.state/'credentials.private.json').read_text())
    manifest=json_util_load(a.state/'seed-manifest.json')
    ids={v['userId'] for v in credentials.values()};assert len(ids)==3 and all(v['username'].startswith('rec_seed_') and v['email'].endswith('@recommendation-seed.invalid') for v in credentials.values())
    checks=[];actions=[]
    log=(a.state/'verification-operations.jsonl').open('a',encoding='utf-8')
    def event(data):log.write(json.dumps({'at':datetime.now(timezone.utc).isoformat(),**data},ensure_ascii=False)+'\n');log.flush();os.fsync(log.fileno())
    def check(name,condition,**detail):
        value={'case':name,'passed':bool(condition),**detail};checks.append(value);event(value)
        assert condition,name
    def tracks(home):return [i for s in home['sections'] if s['id']=='for_you' for i in s['items']]
    def status(path,body,auth):
        try:return 200,request(path,body,auth,'POST')
        except HTTPError as e:
            data=json.loads(e.read());return e.code,data
    def feedback(auth,kind,target,action,enabled):
        value={'user_id':auth['userId'],'entity_type':kind,'entity_id':target,'feedback_type':action,'enabled':enabled}
        event({'stage':'FEEDBACK_PREPARED',**value})
        request('recommendations/feedback',{'entityType':kind,'entityId':target,'feedbackType':action,'enabled':enabled},auth,'PUT')
        actions.append(value);event({'stage':'FEEDBACK_APPLIED',**value})
    def expired(token,seconds):
        raw=json.loads(base64.urlsafe_b64decode(token.split('.')[0]+'=='));raw['expiresAt']=int(time.time())-seconds
        data=base64.urlsafe_b64encode(json.dumps(raw,separators=(',',':')).encode()).decode().rstrip('=')
        signature=base64.urlsafe_b64encode(hmac.new(os.environ['SEED_QUEUE_TOKEN_SECRET'].encode(),data.encode(),hashlib.sha256).digest()).decode().rstrip('=')
        return data+'.'+signature
    sql=mongo=client=None
    try:
        homes={key:request('recommendations/home',auth=auth) for key,auth in credentials.items()}
        check('cold_start_and_warm_profiles',homes['new']['coldStart'] and not homes['calm']['coldStart'] and not homes['energy']['coldStart'])
        calm_ids=[i['entityId'] for i in tracks(homes['calm'])];energy_ids=[i['entityId'] for i in tracks(homes['energy'])]
        check('different_preferences_change_home_order',bool(calm_ids and energy_ids) and calm_ids!=energy_ids,calm=calm_ids,energy=energy_ids)
        for key,home in homes.items():
            check(key+'_unique_tracks',all(len({i['entityId'] for i in s['items']})==len(s['items']) for s in home['sections']))
        auth=credentials['calm'];item=tracks(homes['calm'])[0]
        queue=request('playback/queues',{k:item[k] for k in ['entityType','entityId','versionId','attributionToken']},auth)
        first=queue['tracks'][0];track=first['track'];token=first['queueToken']
        check('queue_current_policy_validation',request('playback/queues/validate',{'trackId':track['id'],'queueToken':token},auth)['valid'])
        with urlopen(Request('http://moodify-backend:8080/api/tracks/'+track['id']+'/stream',headers={'Range':'bytes=0-1023'}),timeout=30) as response:
            sample=response.read(1024);check('real_audio_bytes',response.status in [200,206] and len(sample)==1024,http=response.status,content_type=response.headers.get('Content-Type'))
        code,_=status('playback/queues/validate',{'trackId':track['id'],'queueToken':token},credentials['energy'])
        check('queue_wrong_owner_rejected',code==403,http=code)
        old=expired(token,10)
        code,body=status('playback/queues/validate',{'trackId':track['id'],'queueToken':old},auth)
        check('expired_queue_cannot_play',code==403 and body.get('code')=='RECOMMENDATION_TOKEN_EXPIRED',http=code)
        renewed=request('playback/queues/renew',{'queueToken':old},auth)
        next_track=next(t for t in renewed['tracks'] if t['track']['id']==track['id'])
        check('expired_queue_renews_and_revalidates',request('playback/queues/validate',{'trackId':track['id'],'queueToken':next_track['queueToken']},auth)['valid'])
        def claims(value):return json.loads(base64.urlsafe_b64decode(value.split('.')[0]+'=='))
        before=claims(token);after=claims(next_track['queueToken'])
        check('renewal_preserves_attribution',all(before[k]==after[k] for k in ['userId','itemId','requestId','entityType','entityId','versionId','trackId']))
        code,_=status('playback/queues/renew',{'queueToken':expired(token,86500)},auth)
        check('renewal_grace_bounded',code==403,http=code)
        artist=(track.get('artistIds') or [track.get('artistSpotifyId')])[0]
        followed=request('me/followed-artists',auth=auth)
        if artist not in followed:feedback(auth,'ARTIST',artist,'FOLLOW',True)
        feedback(auth,'ARTIST',artist,'HIDE',True)
        code,_=status('playback/queues/validate',{'trackId':track['id'],'queueToken':token},auth)
        check('block_beats_follow_in_existing_queue',code==404,http=code)
        blocked=request('recommendations/home',auth=auth)
        check('blocked_artist_absent_from_home',all(artist not in ((i.get('track') or {}).get('artistIds') or []) and artist!=(i.get('track') or {}).get('artistSpotifyId') for i in tracks(blocked)))
        code,body=status('playback/queues/renew',{'queueToken':old},auth)
        check('renewal_does_not_bypass_block',code==404 or code==200 and not body['tracks'],http=code)
        feedback(auth,'ARTIST',artist,'HIDE',False)
        check('artist_unblock_restores_permission',request('playback/queues/validate',{'trackId':track['id'],'queueToken':token},auth)['valid'])
        target=tracks(homes['energy'])[0]['entityId'];energy=credentials['energy']
        feedback(energy,'TRACK',target,'NOT_INTERESTED',True)
        check('not_interested_removes_track',target not in [i['entityId'] for i in tracks(request('recommendations/home',auth=energy))])
        another=tracks(request('recommendations/home',auth=energy))[0]['entityId']
        feedback(energy,'TRACK',another,'HIDE',True)
        check('hide_removes_track',another not in [i['entityId'] for i in tracks(request('recommendations/home',auth=energy))])
        feedback(energy,'TRACK',another,'HIDE',False)
        mix=next(i for s in homes['energy']['sections'] for i in s['items'] if i['entityType']=='PERSONALIZED_PLAYLIST')
        detail=request('me/personalized-playlists/'+mix['entityId'],auth=energy)
        check('mix_detail_filters_negative_feedback',detail['tracks'] and target not in [t['id'] for t in detail['tracks']])
        copy=request('me/personalized-playlists/'+mix['entityId']+'/copies',{'versionId':detail['versionId'],'requestId':str(uuid.uuid4())},energy)
        actions.append({'user_id':energy['userId'],'action':'COPY_MIX','playlist_id':copy['id'],'source_mix_id':mix['entityId']})
        check('copy_mix_returns_private_playlist',bool(copy['id']))
        sql,mongo,client=worker.connections(Path('/dev/null'))
        source_tracks,aliases,history,library=worker.load_sources(sql,mongo,datetime.now(timezone.utc))
        check('synthetic_not_in_training_or_evaluation_sources',not any(r['user_id'] in ids for r in history+library),real_history_rows=len(history),real_library_rows=len(library))
        with sql.cursor() as c:
            c.execute('SELECT COUNT(*) n FROM qualified_streams WHERE listener_user_id IN (%s,%s,%s)',tuple(ids));financial=c.fetchone()['n']
        sql.rollback();check('no_qualified_streams_or_revenue',financial==0,qualified_streams=financial)
        frozen(a.state/'functional-actions.json',actions)
    finally:
        result={'scope':'BOUNDED_SEED_COHORT_FUNCTIONAL_ONLY','synthetic':True,'checks':checks,'passed':sum(c['passed'] for c in checks),
            'failed':sum(not c['passed'] for c in checks),'actions':actions,'not_run':['load','fault_injection','rollback','full_regression','android_device']}
        frozen(a.state/'functional-results.json',result)
        if sql is not None:sql.rollback();sql.close()
        if client is not None:client.close()
        log.close();print(json.dumps({'passed':result['passed'],'failed':result['failed'],'scope':result['scope']}))

def json_util_load(path):
    from bson import json_util
    return json_util.loads(path.read_text())

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);main(p.parse_args())
