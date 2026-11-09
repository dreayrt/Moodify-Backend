"""Production monitoring only: passive bounded metrics, local alerts, no synthetic traffic.

Uses a read-only machine key and worker Mongo role; no admin JWT, raw text or user
identifiers in outputs. SQL/Mongo failure scenarios belong to the approved test phase.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.request import Request, urlopen
from pymongo import MongoClient
from monitor_recommendations import render

def atomic(path,value):
    temporary=path.with_suffix('.tmp');temporary.write_text(value,encoding='utf-8');temporary.replace(path)

def monitor(base,heartbeat_path,state,once=False):
    state.mkdir(parents=True,exist_ok=True)
    secret=os.environ['RECOMMENDATIONS_MONITOR_SECRET']
    previous=None;previous_alerts=set();failures=0
    while True:
        now=datetime.now(timezone.utc);alerts=set();metrics={};client=None
        try:
            request=Request(base.rstrip('/')+'/api/internal/recommendations/monitor',headers={'X-Recommendation-Monitor-Key':secret})
            with urlopen(request,timeout=10) as response:data=json.load(response)
            heartbeat=json.loads(heartbeat_path.read_text()) if heartbeat_path.exists() else None
            body=render(data['readiness'],data['operations'],heartbeat,now)
            metrics['moodify_recommendation_demo_catalog']=int(data['readiness'].get('demoCatalog',False))
            metrics['moodify_recommendation_standard_eligible']=data['readiness'].get('standardEligibleTracks',0)
            queue=data['operations']['outcomeQueue']
            metrics['moodify_recommendation_outcome_queue_depth']=queue['queued']
            metrics['moodify_recommendation_outcome_circuit_open']=int(queue['circuitOpen'])
            if queue['circuitOpen']:alerts.add('OUTCOME_CIRCUIT_OPEN')
            if not data['readiness']['eligibleTracks']:alerts.add('NO_ELIGIBLE_CATALOG')
            if not heartbeat or heartbeat.get('status')!='ready' or (now-datetime.fromisoformat(heartbeat['at'])).total_seconds()>1800:
                alerts.add('WORKER_STALE')
            runtime=data['operations']['runtime'];counts=runtime['counters']
            if previous and previous['startedAt']==runtime['startedAt']:
                delta={k:max(0,v-previous['counters'].get(k,0)) for k,v in counts.items()}
                for op,code in [('recommendation.home','HOME'),('emotion','EMOTION'),('queue','QUEUE'),('queue.validate','QUEUE_VALIDATE'),('queue.refresh','TOKEN_REFRESH'),('playback','PLAYBACK')]:
                    requests=delta.get(op+'.requests',0)
                    if requests>=5 and delta.get(op+'.server_errors',0)/requests>.05:alerts.add(code+'_ERROR_RATE')
                    if op.startswith('queue') and delta.get(op+'.rejected',0)>=3:alerts.add(code+'_REJECTED')
                generated=delta.get('recommendation.generated',0)
                if generated>=5 and delta.get('recommendation.empty',0)/generated>.5:alerts.add('EMPTY_RESULT_RATE')
                if sum(delta.get('outcome.'+k,0) for k in ['failed','dropped_circuit','dropped_queue']):alerts.add('OUTCOME_LOSS')
                for op,bad,code in [('recommendation.home',['le5000','gt5000'],'HOME_LATENCY'),('emotion',['gt5000'],'EMOTION_LATENCY')]:
                    count=delta.get(op+'.requests',0)
                    if count>=20 and sum(delta.get(op+'.latency_ms.'+b,0) for b in bad)/count>.05:alerts.add(code)
            previous=runtime
            client=MongoClient(os.environ['MONGODB_URI'],tz_aware=True,serverSelectionTimeoutMS=5000,timeoutMS=8000)
            db=client[os.environ.get('MONGODB_DATABASE','music_streaming')]
            profiles=list(db.recommendation_state.find({'kind':'PROFILE'},{'_id':0,'refreshed_at':1,'as_of':1,'model_version':1}).limit(100001))
            metrics['moodify_recommendation_profiles_total']=len(profiles)
            stale=0;cf=0
            for profile in profiles:
                at=profile.get('refreshed_at') or profile.get('as_of')
                stale+=int(not at or (now-at).total_seconds()>1800)
                cf+=int(str(profile.get('model_version','')).startswith('implicit-als-'))
            metrics['moodify_recommendation_profiles_stale']=stale
            metrics['moodify_recommendation_profiles_collaborative']=cf
            metrics['moodify_recommendation_profiles_baseline']=len(profiles)-cf
            if stale:alerts.add('PROFILE_STALE')
            if len(profiles)>100000:alerts.add('PROFILE_MONITOR_CAPACITY')
            failures=0
        except Exception as error:
            failures+=1;body='moodify_recommendation_scrape_success 0\n'
            alerts.update(previous_alerts)
            if failures>=3:alerts.add('MONITOR_UNAVAILABLE')
        finally:
            if client is not None:client.close()
        body+=''.join(f'{name} {value}\n' for name,value in sorted(metrics.items()))
        status={'at':now.isoformat(),'alerts':sorted(alerts),'consecutive_failures':failures,'scope':'PASSIVE_PRODUCTION_MONITOR','metrics':metrics}
        atomic(state/'recommendations.prom',body);atomic(state/'status.json',json.dumps(status))
        if alerts!=previous_alerts:
            transition={**status,'resolved':sorted(previous_alerts-alerts)}
            with (state/'alerts.jsonl').open('a',encoding='utf-8') as log:log.write(json.dumps(transition)+'\n')
            print(json.dumps(transition),flush=True);previous_alerts=alerts
        if once:return
        time.sleep(60)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--heartbeat',type=Path,required=True)
    p.add_argument('--state',type=Path,default=Path('/monitor'));p.add_argument('--once',action='store_true');a=p.parse_args()
    monitor(a.base,a.heartbeat,a.state,a.once)
