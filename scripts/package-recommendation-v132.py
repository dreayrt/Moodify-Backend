"""Freeze scoped web/class sources on the captured current production baseline.

Preserves the deployed karaoke types and subscription/password helpers absent
from the dirty local tree. Does not build, execute tests, or contact databases.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
from zipfile import ZipFile

def replace_once(text,old,new):
    assert text.count(old)==1, 'Baseline source differs; reconcile before packaging'
    return text.replace(old,new)

def run(workspace,output):
    artifacts=output/'artifacts'
    with tarfile.open(artifacts/'web-live-baseline.tar.gz') as baseline:
        def before(name):return baseline.extractfile(name).read().decode('utf-8-sig').replace('\r\n','\n')
        paths=['components/dashboard/player-context.tsx','components/dashboard/recommendation-sections.tsx',
            'lib/recommendation-client.ts','app/dashboard/admin/recommendations/page.tsx']
        files={p:(workspace/'Moodify'/p).read_text(encoding='utf-8-sig') for p in paths}
        home_name='components/dashboard/lumen-hero.tsx'
        try:home=before(home_name)
        except KeyError:
            home=(output/'lumen-hero-production-before.tsx').read_text(encoding='utf-8-sig')
        files[home_name]=wire_home(home)
        api=before('lib/api-client.ts')
        if 'artistIds?: string[]' not in api:
            api=replace_once(api,'  artistSpotifyId: string;','  artistSpotifyId: string;\n  artistIds?: string[];')
        for message in ['Failed to add track to library','Failed to remove track from library']:
            needle="    throw new Error('"+message+"');\n  }\n"
            api=replace_once(api,needle,needle+"  if (typeof window !== 'undefined') window.dispatchEvent(new Event('moodify:recommendation-changed'));\n")
        files['lib/api-client.ts']=api
        auth=before('lib/auth/auth-client.ts');current=(workspace/'Moodify/lib/auth/auth-client.ts').read_text(encoding='utf-8-sig')
        start=current.index('const playbackDeliveries =');end=current.index('let refreshInFlight:',start)
        a=auth.index('export async function recordListeningSessionTelemetry(');b=auth.index('let refreshInFlight:',a)
        auth=auth[:a]+current[start:end]+auth[b:]
        auth=replace_once(auth,'type RequestOptions = {','type RequestOptions = {\n  timeoutMs?: number;')
        auth=replace_once(auth,'{ body, keepalive, method, token }: RequestOptions = {}','{ body, keepalive, method, token, timeoutMs }: RequestOptions = {}')
        auth=replace_once(auth,'const response = await fetch(fullUrl, {\n    keepalive,','const response = await fetch(fullUrl, {\n    keepalive,\n    signal: timeoutMs ? AbortSignal.timeout(timeoutMs) : undefined,')
        files['lib/auth/auth-client.ts']=auth
        assert 'function changePassword(' in auth and 'function subscribePackage(' in api
        # Preserve karaoke result additions from the latest independent release.
        for feature in ['paired_voiced_seconds','scoring_version','pitch_accuracy_when_detected']:
            if feature in before('lib/api-client.ts'):assert feature in api
    with tarfile.open(artifacts/'web-overlay.tar.gz','w:gz') as archive:
        for name,value in files.items():
            data=value.encode('utf-8');info=tarfile.TarInfo(name);info.size=len(data);archive.addfile(info,io.BytesIO(data))
    (output/'web-source-manifest.json').write_text(json.dumps({n:hashlib.sha256(v.encode()).hexdigest() for n,v in files.items()},indent=2))
    script=workspace/'Moodify-Backend/scripts/package-recommendation-release.py'
    spec=importlib.util.spec_from_file_location('pack',script);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    with ZipFile(workspace/'Moodify-Backend/target/moodify-0.0.1-SNAPSHOT.jar') as jar,ZipFile(artifacts/'backend-class-overlay.zip','w') as overlay:
        for entry in jar.infolist():
            if module.allowed(entry.filename):overlay.writestr(entry,jar.read(entry.filename))
    print('Froze scoped recommendation overlay; preserved latest karaoke and unrelated helpers; no tests executed')

def wire_home(home):
    if '<RecommendationSections' in home:return home
    if 'import RecommendationSections from "./recommendation-sections";' not in home:
        home=replace_once(home,'"use client";','"use client";\n\nimport RecommendationSections from "./recommendation-sections";')
    start=home.index('      <div className="anim-fade-up">\n        <SectionHeader\n          eyebrow="MADE FOR YOU"')
    end=home.index('      {/* ========================================================================= */}',start)
    return home[:start]+'      <div id="for-you" className="anim-fade-up scroll-mt-24"><RecommendationSections /></div>\n\n'+home[end:]

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);p.add_argument('output',type=Path);a=p.parse_args();run(a.workspace,a.output)
