"""VPS wrapper: immutable files, ephemeral publisher credentials, CAS-only revision."""
import json
from pathlib import Path
import subprocess
ROOT=Path('/home/ubuntu/moodify-recommendation-v13/release')
state=ROOT/'demo-contracts-v2'
docs=Path('/opt/moodify/uploads/licenses/demo-20261106-v2')
def run(args,**kw):return subprocess.run(args,check=True,text=True,**kw)
assert (ROOT/'demo-mode.enabled').exists(),'Expected an already authorized demo deployment'
assert state.is_dir() and (state/'manifest.json').is_file() and (state/'source.private.json').is_file()
if not docs.exists():
    run(['sudo','-n','tar','-xzf',str(ROOT/'demo-contracts-v2-documents.tar.gz'),'-C','/opt/moodify/uploads/licenses','--no-same-owner'])
env=dict(v.split('=',1) for v in json.loads(subprocess.check_output(['docker','inspect','moodify-backend']))[0]['Config']['Env'])
private=ROOT/'contract-revision-publisher.private.env'
assert not private.exists(),'Reconcile previous publisher before retry'
try:
    with private.open('x') as f:
        private.chmod(0o600)
        f.write(''.join(k+'='+env[k]+'\n' for k in ['DATABASE_SOURCE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MONGODB_URI','MONGODB_DATABASE']))
    command=['docker','run','--rm','-i','--user','0','--network','moodify_moodify-net','--memory','512m','--cpus','0.5',
        '--env-file',str(private),'-v',str(state)+':/revision','-v',str(docs)+':/documents:ro',
        'moodify-recommendation-worker:1.3','python','/dev/stdin','--root','/revision','--documents','/documents']
    script=(ROOT/'publish-demo-contract-revision.py').read_text()
    run(command,input=script)
    run(command+['--apply'],input=script)
finally:private.unlink(missing_ok=True)
