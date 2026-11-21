"""Run on the authorized VPS. Reuse production baselines and deploy only scoped overlays."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
from datetime import datetime,timezone
from urllib.parse import urlparse,quote

ROOT=Path(os.environ.get('MOODIFY_RECOMMENDATION_RELEASE_ROOT','/home/ubuntu/moodify-recommendation-v13/release'))
TAG=os.environ.get('MOODIFY_RECOMMENDATION_RELEASE_TAG','recommendation-v13-20261106')

def run(args,**kw):return subprocess.run(args,check=True,text=True,**kw)
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def env_of(d):return dict(v.split('=',1) for v in d['Config']['Env'])

def prepare():
    from zipfile import ZipFile
    with ZipFile(ROOT/'product.jar') as artifact:assert 'BOOT-INF/classes/com/laphuth/moodify/services/RecommendationArtists.class' in artifact.namelist()
    before=json.loads((ROOT/'containers-before.private.json').read_text());backend,frontend=before
    build=ROOT/'backend-image';build.mkdir(exist_ok=True)
    shutil.copy2(ROOT/'product.jar',build/'app.jar')
    (build/'Dockerfile').write_text(f"FROM {backend['Image']}\nCOPY app.jar /app/app.jar\nLABEL moodify.release={TAG}\n")
    run(['docker','build','-t','moodify-backend:'+TAG,str(build)])
    source=ROOT/'frontend-source';source.mkdir(exist_ok=True)
    # Source is copied from the deployed host checkout, with credentials/build outputs excluded.
    tar=subprocess.Popen(['tar','-C',os.environ.get('MOODIFY_FRONTEND_SOURCE_BASE','/opt/moodify/frontend'),'--exclude=.git','--exclude=node_modules','--exclude=.next',
        '--exclude=.env*','--exclude=public','--exclude=*.bak*','--exclude=*.pem','-cf','-','.'],stdout=subprocess.PIPE)
    run(['tar','-C',str(source),'-xf','-'],stdin=tar.stdout);tar.stdout.close();assert tar.wait()==0
    run(['tar','-xzf',str(ROOT/'web-overlay.tar.gz'),'-C',str(source)])
    dockerfile=f'''FROM {frontend['Image']} AS builder
USER root
WORKDIR /app
COPY frontend-source/ ./
ENV NEXT_PUBLIC_API_BASE_URL=https://api.moodify.click NEXT_PUBLIC_AUDIO_SERVER_URL=https://moodify.click NEXT_TELEMETRY_DISABLED=1
ENV NEXT_PUBLIC_GOOGLE_CLIENT_ID=711124485487-2qb1s0uqa7e1uja1qio1kjggqgqdka7i.apps.googleusercontent.com NEXT_PUBLIC_FACEBOOK_APP_ID=2981895912150587
RUN npm run build
FROM {frontend['Image']}
USER root
COPY --from=builder --chown=nextjs:nodejs /app/.next /app/.next
LABEL moodify.release={TAG}
USER nextjs
'''
    (ROOT/'Dockerfile.frontend').write_text(dockerfile)
    (ROOT/'.dockerignore').write_text('*\n!Dockerfile.frontend\n!frontend-source/\n!frontend-source/**\n')
    run(['docker','build','-f',str(ROOT/'Dockerfile.frontend'),'-t','moodify-frontend:'+TAG,str(ROOT)])

def deploy():
    current=inspect('moodify-backend');env=env_of(current)
    override=ROOT/'product.override.json'
    configuration={'services':{
        'moodify-backend':{'image':'moodify-backend:'+TAG,'environment':{
            'RECOMMENDATIONS_ENABLED':'true','RECOMMENDATIONS_COLLABORATIVE_ENABLED':'false',
            'RECOMMENDATIONS_DEMO_CATALOG_ENABLED':'true' if (ROOT/'demo-mode.enabled').exists() else 'false',
            'RECOMMENDATIONS_CAMPAIGNS_ENABLED':'false','SPRING_JPA_HIBERNATE_DDL_AUTO':'none'}},
        'moodify-frontend':{'image':'moodify-frontend:'+TAG}}}
    monitor=ROOT/'monitor.private.env'
    if monitor.exists():
        settings=dict(line.split('=',1) for line in monitor.read_text().splitlines() if '=' in line)
        configuration['services']['moodify-backend']['environment']['RECOMMENDATIONS_MONITOR_SECRET']=settings['RECOMMENDATIONS_MONITOR_SECRET']
        configuration['services']['moodify-backend']['volumes']=['recommendation-monitor-state:/var/run/recommendation-monitor:ro']
        configuration['volumes']={'recommendation-monitor-state':{'external':True,'name':'moodify-recommendation-monitor-state'}}
    override.write_text(json.dumps(configuration,indent=2));override.chmod(0o600)
    journal={'release':TAG,'before_images':{n:inspect(n)['Image'] for n in ['moodify-backend','moodify-frontend']},
        'artifact_sha256':hashlib.sha256((ROOT/'product.jar').read_bytes()).hexdigest(),
        'rights_policy':'strict-rights-v1.3','cf':False,'campaigns':False}
    journal['demo_catalog_enabled']=(ROOT/'demo-mode.enabled').exists()
    current_journal=ROOT/'deployment-journal.json'
    if current_journal.exists():shutil.copy2(current_journal,ROOT/('deployment-journal-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')+'.json'))
    current_journal.write_text(json.dumps(journal,indent=2))
    run(['docker','compose','-p','moodify','--project-directory','/opt/moodify','-f','/opt/moodify/docker-compose.yml',
        '-f',str(override),'up','-d','--no-deps','--no-build','moodify-backend'])

def frontend():
    run(['docker','compose','-p','moodify','--project-directory','/opt/moodify','-f','/opt/moodify/docker-compose.yml',
        '-f',str(ROOT/'product.override.json'),'up','-d','--no-deps','--no-build','moodify-frontend'])

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','backend','frontend']);a=p.parse_args()
    {'prepare':prepare,'backend':deploy,'frontend':frontend}[a.action]()
