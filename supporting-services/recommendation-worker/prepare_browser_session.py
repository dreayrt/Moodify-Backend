"""Use a second fixture account so FREE single-device limits are tested honestly."""
import json
import uuid
from urllib.request import Request,urlopen
from integration_smoke import smoke_connections,DISPOSABLE_DATABASE
import worker

file=worker.ROOT/'target/recommendation-browser-fixture.json';fixture=json.loads(file.read_text())
assert fixture['fixtureOnly'] and DISPOSABLE_DATABASE.fullmatch(fixture['database'])
sql,_,client=smoke_connections();sql.select_db(fixture['database'])
with sql.cursor() as c:
    c.execute('UPDATE users dst JOIN users src ON src.id=1 SET dst.password=src.password WHERE dst.id=2')
sql.commit();sql.close();client.close()
device=str(uuid.uuid4())
body=json.dumps({'identifier':'rec_test_2','password':'RecTest-v13-Only!','deviceUuid':device,'deviceName':'Browser acceptance fixture','platform':'WEB'}).encode()
with urlopen(Request('http://127.0.0.1:62423/api/auth/login',data=body,headers={'Content-Type':'application/json'}),timeout=10) as r:auth=json.load(r)
fixture.update(webToken=auth['accessToken'],webRefreshToken=auth['refreshToken'],webDeviceUuid=device,webUserId=2)
file.write_text(json.dumps(fixture),encoding='utf-8')
print('Browser acceptance uses separate FREE fixture account 2; Android account 1 retains its device')
