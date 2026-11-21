"""Provision a least-privilege worker on the authorized VPS, without application schema changes."""
import json
from pathlib import Path
import re
import secrets
import subprocess
from urllib.parse import urlparse,quote

ROOT=Path('/home/ubuntu/moodify-recommendation-v13/release')
def run(args,**kwargs):return subprocess.run(args,check=True,text=True,**kwargs)
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]

env=dict(v.split('=',1) for v in inspect('moodify-backend')['Config']['Env'])
database=urlparse(env['DATABASE_SOURCE_URL'].removeprefix('jdbc:')).path.lstrip('/')
mongo_db=env.get('MONGODB_DATABASE','music_streaming')
assert re.fullmatch('[a-zA-Z0-9_]+',database) and re.fullmatch('[a-zA-Z0-9_]+',mongo_db)
private=ROOT/'worker.private.env'
if not private.exists():
    password=secrets.token_hex(24)
    private.write_text(f'DATABASE_SOURCE_URL=jdbc:mysql://moodify-db:3306/{database}\nDATABASE_USERNAME=rec_product_v13\nDATABASE_PASSWORD={password}\nMONGODB_URI=mongodb://rec_product_v13:{password}@moodify-mongo:27017/{mongo_db}?authSource={mongo_db}\nMONGODB_DATABASE={mongo_db}\n')
    private.chmod(0o600)
else:password=dict(line.split('=',1) for line in private.read_text().splitlines())['DATABASE_PASSWORD']
worker_env=dict(line.split('=',1) for line in private.read_text().splitlines())
worker_env['RECOMMENDATIONS_DEMO_CATALOG_ENABLED']='true' if (ROOT/'demo-mode.enabled').exists() else 'false'
private.write_text(''.join(k+'='+v+'\n' for k,v in worker_env.items()));private.chmod(0o600)
sql=f"CREATE USER IF NOT EXISTS 'rec_product_v13'@'%' IDENTIFIED BY '{password}';\nALTER USER 'rec_product_v13'@'%' IDENTIFIED BY '{password}';\n"
for table in ['users','listening_history','revenue_playback_sessions','playback_events','user_library_tracks','song_licenses']:
    sql+=f"GRANT SELECT ON `{database}`.`{table}` TO 'rec_product_v13'@'%';\n"
run(['docker','exec','-i','moodify-db','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],input=sql,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
role={'role':'recommendation_worker_v13','privileges':[
    {'resource':{'db':mongo_db,'collection':'tracks'},'actions':['find']},
    *[{'resource':{'db':mongo_db,'collection':c},'actions':['find','insert','update','remove','createIndex','listIndexes']} for c in ['recommendation_state','recommendation_events']]],'roles':[]}
js=f"const d=db.getSiblingDB({json.dumps(mongo_db)});const r={json.dumps(role)};if(!d.getRole(r.role))d.createRole(r);if(!d.getUser('rec_product_v13'))d.createUser({{user:'rec_product_v13',pwd:{json.dumps(password)},roles:[r.role]}});"
run(['docker','exec','-i','moodify-mongo','mongosh','--quiet','--file','/dev/stdin'],input=js,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
source=ROOT/'worker-build'
dockerfile=source/'Dockerfile'
dockerfile.write_text(dockerfile.read_text().replace('WORKDIR /app\n','WORKDIR /app/supporting-services/recommendation-worker\n'))
run(['docker','build','-t','moodify-recommendation-worker:1.3',str(source)])
existing=run(['docker','ps','-aq','--filter','name=^moodify-recommendation-worker$'],capture_output=True).stdout.strip()
if existing:run(['docker','rm','-f','moodify-recommendation-worker'])
run(['docker','run','-d','--name','moodify-recommendation-worker','--restart','unless-stopped','--network','moodify_moodify-net',
    '--memory','512m','--cpus','0.5','--log-opt','max-size=10m','--log-opt','max-file=3',
    '--env-file',str(private),'-v','moodify-recommendation-worker-state:/state',
    'moodify-recommendation-worker:1.3','python','worker_service.py','--env','/dev/null','--state','/state'])
print('Worker deployed with dedicated SELECT-only SQL credentials and collection-scoped Mongo role')
