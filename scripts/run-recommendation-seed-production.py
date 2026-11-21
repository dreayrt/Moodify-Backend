"""Run the approved bounded cohort after checking the deployed seed exclusions."""
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path('/home/ubuntu/moodify-recommendation-seed-20261107')
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
guard=json.loads((ROOT/'guard-release.json').read_text())
backend=inspect('moodify-backend');worker=inspect('moodify-recommendation-worker')
assert backend['Image']==guard['backend_image'] and worker['Image']==guard['worker_image'],'Seed exclusions are not the active deployment'
settings=dict(v.split('=',1) for v in backend['Config']['Env'])
assert settings.get('RECOMMENDATIONS_COLLABORATIVE_ENABLED','false')=='false'
private=ROOT/'publisher.private.env';assert not private.exists()
try:
    with private.open('x') as f:
        private.chmod(0o600);f.write(''.join(k+'='+settings[k]+'\n' for k in ['DATABASE_SOURCE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MONGODB_URI','MONGODB_DATABASE']))
        if sys.argv[1:]==['verify']:
            f.write('SEED_QUEUE_TOKEN_SECRET='+(settings.get('RECOMMENDATIONS_TOKEN_SECRET') or settings['JWT_SECRET'])+'\n')
    command=['docker','run','--rm','-i','--user','0','--network','moodify_moodify-net','--env-file',str(private),
        '-v',str(ROOT)+':/seed',worker['Image'],'python','/dev/stdin','--state','/seed/cohort']
    script={'verify':'verify-recommendation-seed-cohort.py','inventory':'inventory-recommendation-seed-cohort.py'}.get(sys.argv[1] if len(sys.argv)>1 else '', 'seed-recommendation-personas.py')
    command[command.index('-v')+1]=str(ROOT)+':/seed'
    source=(ROOT/script).read_text()
    if script.startswith('verify'):
        source=source.replace('from seed_recommendation_personas import BATCH,request,frozen',"sys.path.insert(0,'/seed')\nfrom seed_recommendation_personas import BATCH,request,frozen")
        # Python module names cannot contain hyphens; use an exact local copy.
        (ROOT/'seed_recommendation_personas.py').write_text((ROOT/'seed-recommendation-personas.py').read_text())
    subprocess.run(command,input=source,text=True,check=True)
finally:private.unlink(missing_ok=True)
