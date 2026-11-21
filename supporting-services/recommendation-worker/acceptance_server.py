"""Run a copied build against an already owned disposable acceptance fixture only."""
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
from urllib.request import urlopen
from integration_smoke import smoke_connections, jwt, DISPOSABLE_DATABASE
import worker
import argparse


def run(replace_pid=None):
    fixture_path=worker.ROOT/'target/recommendation-browser-fixture.json'
    fixture=json.loads(fixture_path.read_text())
    assert fixture['fixtureOnly'] and DISPOSABLE_DATABASE.fullmatch(fixture['database'])
    sql, _, client=smoke_connections()
    sql.select_db(fixture['database']);sql.close();client.close()
    env=os.environ.copy()
    secret=secrets.token_urlsafe(48)[:48]
    if replace_pid:
        import psutil
        previous=psutil.Process(replace_pid)
        previous_env=previous.environ()
        assert previous.name().lower()=='java.exe'
        assert previous_env['DATABASE_SOURCE_URL'].startswith(f"jdbc:mysql://127.0.0.1:13316/{fixture['database']}?")
        assert previous_env['SERVER_PORT']=='62423'
        secret=previous_env['JWT_SECRET']
        previous.terminate();previous.wait(timeout=15)
    env.update(DATABASE_SOURCE_URL=f"jdbc:mysql://127.0.0.1:13316/{fixture['database']}?useSSL=false&serverTimezone=UTC",
        MONGODB_DATABASE=fixture['database'],JWT_SECRET=secret,SERVER_ADDRESS='127.0.0.1',SERVER_PORT='62423',
        EMOTION_SERVICE_BASE_URL='http://127.0.0.1:18000',OCR_SERVICE_BASE_URL='http://127.0.0.1:1',
        SYNC_SCHEDULER_ENABLED='false',APP_SCHEDULING_ENABLED='false',SPRING_JPA_SHOW_SQL='false',
        SPRING_JPA_HIBERNATE_DDL_AUTO='none',APP_CORS_ALLOWED_ORIGINS='http://localhost:38100',
        RECOMMENDATIONS_CAMPAIGNS_ENABLED='false',RECOMMENDATIONS_COLLABORATIVE_ENABLED='false')
    artifact=worker.ROOT/'target/recommendation-acceptance.jar'
    shutil.copy2(worker.ROOT/'target/moodify-0.0.1-SNAPSHOT.jar',artifact)
    log=(worker.ROOT/'target/recommendation-acceptance-server.log').open('w',encoding='utf-8')
    process=subprocess.Popen(['C:/Program Files/Java/jdk-26/bin/java.exe','-jar',str(artifact),
        '--spring.config.import=optional:file:target/no-integration-env.properties',
        '--app.scheduling.enabled=false','--subscription.maintenance.enabled=false'],
        cwd=worker.ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    try:
        for _ in range(90):
            if process.poll() is not None:raise RuntimeError('Acceptance backend startup failed; see isolated log')
            try:
                with urlopen('http://127.0.0.1:62423/api/emotions/predict',timeout=.5):pass
            except Exception as error:
                if getattr(error,'code',0) in {400,401,403,405}:break
            time.sleep(1)
        fixture.update(token=jwt('rec_test_1','USER',1,secret),adminToken=jwt('rec_test_3','ADMIN',3,secret))
        fixture_path.write_text(json.dumps(fixture),encoding='utf-8')
        print('Isolated acceptance backend ready; real self-hosted emotion dependency; campaigns/CF disabled',flush=True)
        time.sleep(2400)
    finally:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait()
        log.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--replace-pid',type=int);run(parser.parse_args().replace_pid)
