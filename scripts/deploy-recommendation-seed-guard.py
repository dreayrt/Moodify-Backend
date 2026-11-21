"""Install narrowly scoped synthetic-cohort exclusions before creating any seed data."""
import hashlib
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT=Path('/home/ubuntu/moodify-recommendation-seed-20261107')
RELEASE=Path('/home/ubuntu/moodify-recommendation-v132-20261107/release')
TAG='recommendation-v132-seedguard-20261107'
PREFIX='BOOT-INF/classes/com/laphuth/moodify/'
BASES={'repositories/RecommendationStore','services/RevenuePlaybackService','services/RecommendationSeedAccounts'}
def run(args,**kw):return subprocess.run(args,check=True,text=True,**kw)
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def allowed(name):return name.startswith(PREFIX) and name.endswith('.class') and name[len(PREFIX):].removesuffix('.class').split('$')[0] in BASES

def main():
    ROOT.mkdir(mode=0o700,exist_ok=True)
    before=inspect('moodify-backend');worker=inspect('moodify-recommendation-worker')
    assert before['Image']=='sha256:3f431636a783837248f767a42ae5fe2627cf20c3bd79b6d212d72eafbc98c90e','Production changed; reconcile first'
    frozen=ROOT/'containers-before.private.json'
    with frozen.open('x') as f:frozen.chmod(0o600);json.dump([before,worker],f)
    run(['docker','cp','moodify-backend:/app/app.jar',str(ROOT/'before.jar')])
    with ZipFile(ROOT/'before.jar') as baseline,ZipFile(ROOT/'guard-classes.zip') as overlay,ZipFile(ROOT/'guarded.jar','w') as target:
        assert all(allowed(n) for n in overlay.namelist())
        names=set(overlay.namelist())
        for entry in baseline.infolist():target.writestr(entry,overlay.read(entry.filename) if entry.filename in names else baseline.read(entry.filename))
        for entry in overlay.infolist():
            if entry.filename not in baseline.namelist():target.writestr(entry,overlay.read(entry.filename))
    build=ROOT/'backend-build';build.mkdir(exist_ok=True)
    import shutil
    shutil.copy2(ROOT/'guarded.jar',build/'app.jar')
    (build/'Dockerfile').write_text('FROM '+before['Image']+'\nCOPY app.jar /app/app.jar\n')
    run(['docker','build','-t','moodify-backend:'+TAG,str(build)])
    wb=ROOT/'worker-build';wb.mkdir(exist_ok=True)
    shutil.copy2(ROOT/'worker.py',wb/'worker.py')
    (wb/'Dockerfile').write_text('FROM '+worker['Image']+'\nCOPY --chown=10001:10001 worker.py /app/supporting-services/recommendation-worker/worker.py\n')
    run(['docker','build','-t','moodify-recommendation-worker:'+TAG,str(wb)])
    assert inspect('moodify-backend')['Image']==before['Image'],'Concurrent backend deployment'
    cfg=json.loads((RELEASE/'product.override.json').read_text())
    cfg['services']['moodify-backend']['image']='moodify-backend:'+TAG
    override=ROOT/'guard.override.private.json'
    with override.open('x') as f:override.chmod(0o600);json.dump(cfg,f)
    run(['docker','compose','-p','moodify','--project-directory','/opt/moodify','-f','/opt/moodify/docker-compose.yml','-f',str(override),'up','-d','--no-deps','--no-build','moodify-backend'])
    envfile=ROOT/'worker.private.env'
    with envfile.open('x') as f:envfile.chmod(0o600);f.write('\n'.join(worker['Config']['Env'])+'\n')
    run(['docker','stop','--time','15','moodify-recommendation-worker'])
    run(['docker','rename','moodify-recommendation-worker','moodify-recommendation-worker-before-seedguard-20261107'])
    command=['docker','run','-d','--name','moodify-recommendation-worker','--restart','unless-stopped','--network','moodify_moodify-net',
        '--memory','512m','--cpus','0.5','--env-file',str(envfile),'--log-opt','max-size=10m','--log-opt','max-file=3']
    for mount in worker['Mounts']:
        source=mount.get('Name') or mount['Source'];command+=['-v',source+':'+mount['Destination']+('' if mount['RW'] else ':ro')]
    run(command+['moodify-recommendation-worker:'+TAG,*worker['Config']['Cmd']])
    report={'backend_image':inspect('moodify-backend')['Image'],'worker_image':inspect('moodify-recommendation-worker')['Image'],
        'jar_sha256':hashlib.sha256((ROOT/'guarded.jar').read_bytes()).hexdigest(),'schema_changes':False,
        'synthetic_excluded_from_global_trends':True,'synthetic_excluded_from_training':True,'seed_data_created':False}
    (ROOT/'guard-release.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))

if __name__=='__main__':main()
