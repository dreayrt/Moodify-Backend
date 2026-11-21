"""Read actual physical-device telemetry from the disposable fixture, never application DB."""
import argparse
import json
import os
from integration_smoke import smoke_connections, DISPOSABLE_DATABASE
import worker

def run(label):
    fixture=json.loads((worker.ROOT/'target/recommendation-browser-fixture.json').read_text())
    assert fixture['fixtureOnly'] and DISPOSABLE_DATABASE.fullmatch(fixture['database'])
    sql,_,client=smoke_connections();sql.select_db(fixture['database'])
    with sql.cursor() as c:
        c.execute("SELECT id,track_id,started_at,ended_at,listened_duration_ms,last_position_ms,source,source_id,device_type FROM listening_history WHERE user_id=1 AND device_type='ANDROID' ORDER BY id DESC LIMIT 4")
        rows=c.fetchall()
        for row in rows:
            c.execute('SELECT event_type,position_ms,target_position_ms,occurred_at FROM playback_events WHERE listening_history_id=%s ORDER BY id',(row['id'],))
            row['events']=c.fetchall()
    sql.close();client.close()
    output=worker.ROOT.parent/'output/recommendation-check-20261106'/('v13-device-'+label+'.json')
    output.write_text(json.dumps({'fixtureOnly':True,'label':label,'sessions':rows},default=str,indent=2),encoding='utf-8')
    print(json.dumps({'label':label,'sessions':[{'id':r['id'],'listenedMs':r['listened_duration_ms'],'position':r['last_position_ms'],'events':[e['event_type'] for e in r['events']]} for r in rows]}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('label',choices=['playing','paused','resumed','background','locked','network','final']);run(parser.parse_args().label)
