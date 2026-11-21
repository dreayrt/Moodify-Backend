"""Run on VPS after deploying demo-aware code, while the explicit demo flag is still off."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path('/home/ubuntu/moodify-recommendation-v13/release')
def run(args,**kwargs):return subprocess.run(args,check=True,text=True,**kwargs)
assert not (ROOT/'demo-mode.enabled').exists(),'Publish before activation'
run(['python3',str(ROOT/'verify-recommendation-product.py')])
verification=json.loads((ROOT/'production-verification.json').read_text())
assert verification['readiness']['demoCatalog'] is False,'Deploy guarded code first'
shutil.copy2(ROOT/'production-verification.json',ROOT/'verification-before-demo.json')
state=ROOT/'demo-rights';state.mkdir(exist_ok=True)
for source,name in [(ROOT/'demo-rights-input.private.json','source.private.json'),(ROOT/'demo-rights-manifest.json','manifest.json')]:
    target=state/name
    if target.exists():assert target.read_bytes()==source.read_bytes(),'Immutable batch input changed'
    else:shutil.copy2(source,target)
documents=Path('/opt/moodify/uploads/licenses/demo-20261106')
run(['sudo','-n','tar','-xzf',str(ROOT/'demo-documents.tar.gz'),'-C','/opt/moodify/uploads/licenses','--no-same-owner'])
env=dict(v.split('=',1) for v in json.loads(subprocess.check_output(['docker','inspect','moodify-backend']))[0]['Config']['Env'])
private=ROOT/'publisher.private.env'
private.write_text(''.join(k+'='+env[k]+'\n' for k in ['DATABASE_SOURCE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MONGODB_URI','MONGODB_DATABASE']))
private.chmod(0o600)
command=['docker','run','--rm','-i','--user','0','--network','moodify_moodify-net','--memory','512m','--cpus','0.5',
    '--env-file',str(private),'-v',str(ROOT)+':/release','-v',str(documents)+':/documents:ro',
    'moodify-recommendation-worker:1.3','python','/dev/stdin','--root','/release/demo-rights','--documents','/documents']
script=(ROOT/'publish-demo-rights.py').read_text()
try:
    run(command,input=script)
    run(command+['--apply'],input=script)
finally:private.unlink(missing_ok=True)
