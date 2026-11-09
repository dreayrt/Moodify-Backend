"""Bounded-label Prometheus textfile exporter for authenticated recommendation operations."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
from urllib.request import Request, urlopen

ISSUES={'NOT_PUBLISHED','NOT_PUBLIC','NOT_APPROVED','NO_AUDIO','NO_LICENSE','LICENSE_NOT_EFFECTIVE','MISSING_MOOD_FEATURES'}

def render(readiness, operations, heartbeat, now):
    values={'moodify_recommendation_scrape_success':1,
        'moodify_recommendation_catalog_total':readiness['catalogTracks'],
        'moodify_recommendation_catalog_eligible':readiness['eligibleTracks'],
        'moodify_recommendation_catalog_features_complete':readiness['catalogTracks']-readiness.get('issues',{}).get('MISSING_MOOD_FEATURES',0)}
    for name,value in readiness.get('issues',{}).items():
        if name in ISSUES:values['moodify_recommendation_catalog_issue_'+name.lower()]=value
    runtime=operations['runtime']
    for flag in ('enabled','collaborative','campaigns'):
        values['moodify_recommendation_'+flag]=int(runtime['flags'][flag])
    values['moodify_recommendation_pilot_enabled']=int(runtime.get('pilotEnabled',False))
    for name,value in runtime['counters'].items():
        # Only developer-owned operation names; no IDs, text, tokens or free-form labels.
        if re.fullmatch(r'(recommendation|emotion|queue|outcome|mix|events|playback)\.[a-z_.0-9]{1,80}',name):
            values['moodify_recommendation_'+name.replace('.','_')]=value
    if heartbeat:
        age=max(0,(now-datetime.fromisoformat(heartbeat['at'])).total_seconds())
        values['moodify_recommendation_worker_heartbeat_age_seconds']=age
        values['moodify_recommendation_worker_ready']=int(heartbeat['status']=='ready' and age<1800)
    else:values['moodify_recommendation_worker_ready']=0
    return ''.join(f'{name} {float(value):g}\n' for name,value in sorted(values.items()))

def collect(base, token, heartbeat):
    def get(path):
        with urlopen(Request(base.rstrip('/')+'/api/admin/recommendations/'+path,headers={'Authorization':'Bearer '+token}),timeout=8) as r:return json.load(r)
    return render(get('readiness'),get('operations'),heartbeat,datetime.now(timezone.utc))

def main():
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--heartbeat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    try:
        heartbeat=json.loads(args.heartbeat.read_text()) if args.heartbeat.exists() else None
        body=collect(args.base,os.environ['RECOMMENDATION_MONITOR_TOKEN'],heartbeat)
    except Exception:
        body='moodify_recommendation_scrape_success 0\n'
    temp=args.output.with_suffix('.tmp');temp.write_text(body);temp.replace(args.output)

if __name__=='__main__':main()
