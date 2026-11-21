"""Operator rollback; never restores databases or a backend without DEMO exclusion."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import subprocess

def main(a):
    root=Path(os.environ['MOODIFY_RECOMMENDATION_RELEASE_ROOT'])
    override=root/'product.override.json';config=json.loads(override.read_text())
    targets=json.loads((root/'rollback-targets.json').read_text())
    assert targets['strict_rights_and_demo_exclusion'] is True
    before=json.loads(subprocess.check_output(['docker','inspect','moodify-backend','moodify-frontend']))
    journal={'at':datetime.now(timezone.utc).isoformat(),'mode':a.mode,'before_images':{v['Name']:v['Image'] for v in before}}
    backend=config['services']['moodify-backend']
    backend['environment'].update(RECOMMENDATIONS_ENABLED='false',RECOMMENDATIONS_COLLABORATIVE_ENABLED='false',RECOMMENDATIONS_CAMPAIGNS_ENABLED='false')
    services=['moodify-backend']
    if a.mode=='prior-images':
        backend['image']=targets['backend_image'];config['services']['moodify-frontend']['image']=targets['frontend_image'];services.append('moodify-frontend')
        assert all(str(v).startswith('sha256:') and len(v)==71 for v in [targets['backend_image'],targets['frontend_image']])
    # The override may contain secrets: preserve owner-only access and never print it.
    path=root/('rollback-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')+'.json')
    with path.open('x') as f:path.chmod(0o600);json.dump(journal,f,indent=2)
    override.write_text(json.dumps(config,indent=2));override.chmod(0o600)
    subprocess.run(['docker','compose','-p','moodify','--project-directory','/opt/moodify','-f','/opt/moodify/docker-compose.yml','-f',str(override),
        'up','-d','--no-deps','--no-build',*services],check=True)
    print('Generation disabled; strict rights and DEMO exclusion retained. No data restored.')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['stop-generation','prior-images'],required=True);main(p.parse_args())
