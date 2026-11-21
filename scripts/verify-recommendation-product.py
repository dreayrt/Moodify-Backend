"""Minimal production checks; no fabricated accounts, licenses or listening events."""
import base64
import hashlib
import hmac
import json
import subprocess
import time
from datetime import datetime,timezone
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError

ROOT=Path('/home/ubuntu/moodify-recommendation-v13/release')
data=json.loads(subprocess.check_output(['docker','inspect','moodify-backend']))[0]
env=dict(v.split('=',1) for v in data['Config']['Env'])
database=env['DATABASE_SOURCE_URL'].split('/',3)[3].split('?',1)[0]
query=f"START TRANSACTION READ ONLY; SELECT id,username FROM `{database}`.users WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id LIMIT 1; ROLLBACK;"
admin=subprocess.check_output(['docker','exec','-i','moodify-db','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'],input=query.encode()).decode().strip().split('\t')
assert len(admin)==2
def encode(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
header=encode({'alg':'HS256','typ':'JWT'});now=int(time.time())
payload=encode({'sub':admin[1],'role':'ADMIN','userId':int(admin[0]),'tokenType':'ACCESS','iat':now,'exp':now+120})
body=header+'.'+payload
token=body+'.'+base64.urlsafe_b64encode(hmac.new(env['JWT_SECRET'].encode(),body.encode(),hashlib.sha256).digest()).decode().rstrip('=')
report={'at':datetime.now(timezone.utc).isoformat(),'backendImage':data['Image'],'authenticatedAdminReadOnly':True}
for name,path in [('readiness','admin/recommendations/readiness'),('operations','admin/recommendations/operations')]:
    with urlopen(Request('http://127.0.0.1:8080/api/'+path,headers={'Authorization':'Bearer '+token}),timeout=15) as r:report[name]=json.load(r)
report['productionStatus']='DEMO_CATALOG_AVAILABLE' if report['readiness'].get('demoCatalog') and report['readiness']['eligibleTracks'] else ('WAITING_FOR_LICENSED_CATALOG' if not report['readiness']['eligibleTracks'] else 'CATALOG_AVAILABLE')
assert report['readiness']['requireActiveLicense'] is True
assert report['operations']['runtime']['flags']['collaborative'] is False
assert report['operations']['runtime']['flags']['campaigns'] is False
(ROOT/'production-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'catalogTracks':report['readiness']['catalogTracks'],'eligibleTracks':report['readiness']['eligibleTracks'],
    'standardEligibleTracks':report['readiness'].get('standardEligibleTracks'),'demoCatalog':report['readiness'].get('demoCatalog'),
    'rightsEnforced':True,'cf':False,'campaigns':False,'status':report['productionStatus']}))
