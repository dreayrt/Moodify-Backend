"""Deploy passive monitor and bounded worker without tests or simulated events."""
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT=Path(os.environ['MOODIFY_RECOMMENDATION_RELEASE_ROOT'])
TAG=os.environ['MOODIFY_RECOMMENDATION_RELEASE_TAG']
def run(args,**kw):return subprocess.run(args,check=True,text=True,**kw)
def secret_file():
    path=ROOT/'monitor.private.env'
    if not path.exists():
        with path.open('x') as f:
            path.chmod(0o600);f.write('RECOMMENDATIONS_MONITOR_SECRET='+secrets.token_hex(32)+'\n')
    run(['docker','volume','create','moodify-recommendation-monitor-state'],stdout=subprocess.DEVNULL)
    return dict(line.split('=',1) for line in path.read_text().splitlines())

def deploy():
    monitor=secret_file();private=ROOT/'worker.private.env'
    settings=dict(line.split('=',1) for line in private.read_text().splitlines())
    settings['RECOMMENDATIONS_DEMO_CATALOG_ENABLED']='true' if (ROOT/'demo-mode.enabled').exists() else 'false'
    settings['RECOMMENDATIONS_TRAINING_ENABLED']='false';settings['RECOMMENDATIONS_BATCH_TIMEOUT_SECONDS']='180'
    private.write_text(''.join(k+'='+v+'\n' for k,v in settings.items()));private.chmod(0o600)
    monitor_env=ROOT/'operations.private.env'
    with monitor_env.open('w') as f:
        monitor_env.chmod(0o600)
        f.write(''.join(k+'='+v+'\n' for k,v in {**settings,**monitor}.items()))
    run(['docker','build','-t','moodify-recommendation-worker:'+TAG,str(ROOT/'worker-build')])
    run(['docker','run','--rm','--user','0','--network','none','-v','moodify-recommendation-monitor-state:/monitor',
        'moodify-recommendation-worker:'+TAG,'python','-c',"import os; os.chown('/monitor',10001,10001); os.chmod('/monitor',0o755)"])
    for name in ['moodify-recommendation-worker','moodify-recommendation-monitor']:
        exists=subprocess.check_output(['docker','ps','-aq','--filter','name=^'+name+'$'],text=True).strip()
        if exists:run(['docker','rm','-f',name],stdout=subprocess.DEVNULL)
    common=['docker','run','-d','--restart','unless-stopped','--network','moodify_moodify-net',
        '--memory','512m','--cpus','0.5','--log-opt','max-size=10m','--log-opt','max-file=3']
    run([*common,'--name','moodify-recommendation-worker','--env-file',str(private),
        '-v','moodify-recommendation-worker-state:/state','moodify-recommendation-worker:'+TAG,
        'python','worker_service.py','--env','/dev/null','--state','/state'])
    run([*common,'--name','moodify-recommendation-monitor','--env-file',str(monitor_env),
        '--health-cmd',"python -c \"import json,time,datetime; s=json.load(open('/monitor/status.json')); assert time.time()-datetime.datetime.fromisoformat(s['at']).timestamp()<180 and s['consecutive_failures']<3\"",
        '--health-interval','60s','--health-start-period','120s',
        '-v','moodify-recommendation-worker-state:/worker-state:ro',
        '-v','moodify-recommendation-monitor-state:/monitor',
        'moodify-recommendation-worker:'+TAG,'python','operations_monitor.py',
        '--base','http://moodify-backend:8080','--heartbeat','/worker-state/heartbeat.json','--state','/monitor'])
    print('Worker and passive monitor deployed; no behavioral tests executed')

if __name__=='__main__':
    import sys
    if sys.argv[1:] == ['prepare']:secret_file()
    else:deploy()
