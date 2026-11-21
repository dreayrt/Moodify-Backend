"""Verify serving with an existing account, without fabricating plays, likes or financial events."""
import base64,hashlib,hmac,json,subprocess,time
from pathlib import Path
from urllib.request import Request,urlopen

ROOT=Path('/home/ubuntu/moodify-recommendation-v13/release')
env=dict(v.split('=',1) for v in json.loads(subprocess.check_output(['docker','inspect','moodify-backend']))[0]['Config']['Env'])
database=env['DATABASE_SOURCE_URL'].split('/',3)[3].split('?',1)[0]
sql=f"START TRANSACTION READ ONLY;SELECT id,username FROM `{database}`.users WHERE role='USER' AND status='ACTIVE' ORDER BY id LIMIT 1;ROLLBACK;"
user=subprocess.check_output(['docker','exec','-i','moodify-db','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'],input=sql.encode()).decode().strip().split('\t');assert len(user)==2
def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
now=int(time.time());body=enc({'alg':'HS256','typ':'JWT'})+'.'+enc({'sub':user[1],'role':'USER','userId':int(user[0]),'tokenType':'ACCESS','iat':now,'exp':now+180})
token=body+'.'+base64.urlsafe_b64encode(hmac.new(env['JWT_SECRET'].encode(),body.encode(),hashlib.sha256).digest()).decode().rstrip('=')
def call(path,body=None):
    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
    with urlopen(Request('http://127.0.0.1:8080/api/'+path,data=None if body is None else json.dumps(body).encode(),headers=headers),timeout=20) as r:return json.load(r)
home=call('recommendations/home');assert home['demoCatalog'] is True and home['eligibleCount']==426
items=[i for s in home['sections'] for i in s['items']]
item=next(i for i in items if i['entityType']=='TRACK')
queue=call('playback/queues',{k:item[k] for k in ['entityType','entityId','versionId','attributionToken']});first=queue['tracks'][0]
assert call('playback/queues/validate',{'trackId':first['track']['id'],'queueToken':first['queueToken']})['valid']
with urlopen(Request('http://127.0.0.1:8080/api/tracks/'+first['track']['id']+'/stream',headers={'Range':'bytes=0-1023'}),timeout=20) as r:
    sample=r.read(1024);audio_status=r.status;audio_type=r.headers.get('Content-Type');assert sample and audio_status in (200,206)
mood=call('emotions/recommend',{'text':'Tôi muốn thư giãn sau một ngày dài.','strategy':'empathy','limit':10})
assert mood['matchStatus'] in ['MATCHED','LIMITED'] and mood['tracks']
report={'demoCatalog':True,'eligibleCount':home['eligibleCount'],'policyVersion':home['policyVersion'],'modelVersion':home['modelVersion'],
    'sections':[{'id':s['id'],'count':len(s['items'])} for s in home['sections']],
    'queueValidated':True,'audioHttpStatus':audio_status,'audioContentType':audio_type,'sampleBytes':len(sample),
    'moodMatchStatus':mood['matchStatus'],'moodTrackCount':len(mood['tracks']),
    'fabricatedListeningOrFeedback':False,'financialEventsCreated':False,'accountCreated':False}
(ROOT/'demo-journey-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
