"""Overlay only reviewed recommendation classes onto the running production dependency baseline."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ALLOWED={
 'api/EmotionApi','api/RecommendationApi','api/RecommendationMonitorApi','config/GlobalExceptionHandler','config/SecurityConfig',
 'dto/emotion/MoodRecommendationResponse','dto/track/TrackResponse','entities/Track',
 'repositories/SongLicenseRepository','repositories/TrackRepository',
 'services/EmotionService','services/PersonalizedMixService','services/MoodTrackScorer',
 'services/PreferenceEntityResolver','services/PublicTrackPolicy','services/RevenuePlaybackService',
}
PREFIX='BOOT-INF/classes/com/laphuth/moodify/'
HARDENING={
 'api/RecommendationApi','api/RecommendationMonitorApi','config/SecurityConfig',
 'services/RecommendationArtistIdentities','services/RecommendationCatalog','services/RecommendationPreferenceService',
 'services/RecommendationService','services/RecommendationRuntime','services/RecommendationMonitorStatus',
 'services/RevenuePlaybackService','security/RecommendationTokens','security/RecommendationOperationsFilter',
}

def allowed(name,scope='full'):
    if not name.startswith(PREFIX) or not name.endswith('.class'):return False
    key=name[len(PREFIX):].removesuffix('.class').split('$')[0]
    if scope=='hardening-v132':return key in HARDENING
    return key in ALLOWED or key.startswith(('dto/recommendation/RecommendationTypes','services/Recommendation','repositories/Recommendation','security/Recommendation'))

def run(before,candidate,output,overlay=False,baseline_sha=None,scope='full'):
    if overlay:
        assert baseline_sha and hashlib.sha256(before.read_bytes()).hexdigest()==baseline_sha,'Production baseline changed'
    replaced=[]
    with ZipFile(before) as base,ZipFile(candidate) as new,ZipFile(output,'w') as out:
        base_names=set(base.namelist());new_names=set(new.namelist())
        libs={n for n in base_names|new_names if n.startswith('BOOT-INF/lib/')}
        if not overlay:assert all(n in base_names and n in new_names and base.read(n)==new.read(n) for n in libs),'Dependency baseline changed'
        else:assert all(allowed(n,scope) for n in new_names),'Non-recommendation overlay entry'
        for entry in base.infolist():
            selected=allowed(entry.filename,scope) and entry.filename in new_names
            data=new.read(entry.filename) if selected else base.read(entry.filename)
            if selected and data!=base.read(entry.filename):replaced.append(entry.filename)
            out.writestr(entry,data)
        for name in sorted(new_names-base_names):
            if allowed(name,scope):out.writestr(new.getinfo(name),new.read(name));replaced.append(name)
    manifest={'baseline_sha256':hashlib.sha256(before.read_bytes()).hexdigest(),
        'compiled_candidate_sha256':hashlib.sha256(candidate.read_bytes()).hexdigest(),
        'release_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'replaced_entries':replaced,
        'other_entries_preserved':True,'dependencies_identical':True,'scope':scope}
    output.with_suffix('.manifest.json').write_text(json.dumps(manifest,indent=2))
    print('Packaged scoped recommendation release:',len(replaced),'class entries; production dependencies preserved')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('before',type=Path);p.add_argument('candidate',type=Path);p.add_argument('output',type=Path);p.add_argument('--overlay',action='store_true');p.add_argument('--baseline-sha');p.add_argument('--scope',choices=['full','hardening-v132'],default='full');a=p.parse_args();run(a.before,a.candidate,a.output,a.overlay,a.baseline_sha,a.scope)
