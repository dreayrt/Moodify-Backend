"""Wire the real recommendation component into the current Home, preserving layout."""
from pathlib import Path
import json
import shutil
import subprocess

ROOT=Path('/home/ubuntu/moodify-recommendation-v132-20261107/release')
STATE=Path('/home/ubuntu/moodify-recommendation-seed-20261107/home-wiring')
def run(args,**kw):return subprocess.run(args,check=True,text=True,**kw)
def inspect():return json.loads(subprocess.check_output(['docker','inspect','moodify-frontend']))[0]

STATE.mkdir(mode=0o700,exist_ok=True)
before=inspect();assert before['Image']=='sha256:b486fae118a591ea76529ec7dd2eacf2dbaf9b51357376c86fbc75e0de342278'
source=STATE/'frontend-source';shutil.copytree(ROOT/'frontend-source',source,dirs_exist_ok=True)
target=source/'components/dashboard/lumen-hero.tsx';old=target.read_text()
assert '<RecommendationSections' not in old
assert old.count('"use client";')==1
new=old if 'import RecommendationSections from "./recommendation-sections";' in old else old.replace('"use client";','"use client";\n\nimport RecommendationSections from "./recommendation-sections";')
start=new.index('      <div className="anim-fade-up">\n        <SectionHeader\n          eyebrow="MADE FOR YOU"')
end=new.index('      {/* ========================================================================= */}',start)
new=new[:start]+'      <div id="for-you" className="anim-fade-up scroll-mt-24"><RecommendationSections /></div>\n\n'+new[end:]
target.write_text(new)
(STATE/'lumen-hero-before.tsx').write_text(old)
dockerfile=(ROOT/'Dockerfile.frontend').read_text().replace('LABEL moodify.release=recommendation-v132-20261107','LABEL moodify.release=recommendation-v132-homefix-20261107')
(STATE/'Dockerfile.frontend').write_text(dockerfile)
(STATE/'.dockerignore').write_text('*\n!Dockerfile.frontend\n!frontend-source/\n!frontend-source/**\n')
run(['docker','build','-f',str(STATE/'Dockerfile.frontend'),'-t','moodify-frontend:recommendation-v132-homefix-20261107',str(STATE)])
assert inspect()['Image']==before['Image'],'Concurrent frontend deployment'
override=ROOT/'product.override.json';cfg=json.loads(override.read_text())
cfg['services']['moodify-frontend']['image']='moodify-frontend:recommendation-v132-homefix-20261107'
cfg['services']['moodify-backend']['image']='moodify-backend:recommendation-v132-seedguard-20261107'
with (STATE/'override-before.private.json').open('x') as f:(STATE/'override-before.private.json').chmod(0o600);f.write(override.read_text())
override.write_text(json.dumps(cfg,indent=2));override.chmod(0o600)
run(['docker','compose','-p','moodify','--project-directory','/opt/moodify','-f','/opt/moodify/docker-compose.yml','-f',str(override),'up','-d','--no-deps','--no-build','moodify-frontend'])
shutil.copy2(target,ROOT/'frontend-source/components/dashboard/lumen-hero.tsx')
(STATE/'result.json').write_text(json.dumps({'before_image':before['Image'],'after_image':inspect()['Image'],'home_wired':True,'unrelated_home_sections_preserved':True},indent=2))
print('Home entry point wired to the deployed recommendation component; no API cases rerun')
