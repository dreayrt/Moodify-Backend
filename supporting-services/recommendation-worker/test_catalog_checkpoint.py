from copy import deepcopy
import json
from types import SimpleNamespace
import sqlite3
from catalog_maintenance import features

VECTOR=dict(valence=.5, energy=.6, danceability=.4, acousticness=.3, bpm=110)

class Tracks:
    def __init__(self, newer=False, conflict=False):
        self.doc={'_id':'track','status':'published','visibility':'public','audio_hash':'hash','audio_features':{},'lyrics_plain':'preserve','local_path':'data/audio/test.mp3'}
        self.newer, self.conflict, self.writes = newer, conflict, 0
    def find(self, *args):return [deepcopy(self.doc)]
    def find_one(self, query):
        if self.newer:self.doc['audio_features']={**VECTOR,'energy':.9}
        return deepcopy(self.doc)
    def update_one(self, query, update):
        self.writes+=1
        assert query['audio_features']=={} and query['audio_hash']=='hash'
        if self.conflict:return SimpleNamespace(modified_count=0)
        self.doc['audio_features']=update['$set']['audio_features']
        self.doc['audio_features_schema_provenance']={'recommendation_analysis':update['$set']['audio_features_schema_provenance.recommendation_analysis']}
        return SimpleNamespace(modified_count=1)

def prepare(tmp_path, result=None):
    directory=tmp_path/'track';directory.mkdir()
    (directory/'analyzer-response.json').write_text(json.dumps({'features':result or {**VECTOR,'version':'v2','assessment':'estimated_not_listener_verified'},'audio_hash':'hash','analyzer_version':'v2'}))
    return SimpleNamespace(output=tmp_path,analyzer_version='v2',apply=True)

def state(tmp_path):
    with sqlite3.connect(tmp_path/'features.sqlite') as sql:return sql.execute('SELECT state FROM jobs').fetchone()[0]

def test_restart_reuses_analysis_preserves_fields_and_does_not_publish_twice(tmp_path):
    args=prepare(tmp_path);tracks=Tracks();mongo=SimpleNamespace(tracks=tracks)
    features(mongo,args);features(mongo,args)
    assert tracks.writes==1 and state(tmp_path)=='published'
    assert tracks.doc['lyrics_plain']=='preserve' and tracks.doc['local_path']=='data/audio/test.mp3'
    assert len(list((tmp_path/'track').glob('*-before.json')))==1

def test_completed_newer_vector_is_never_overwritten(tmp_path):
    args=prepare(tmp_path);tracks=Tracks(newer=True)
    features(SimpleNamespace(tracks=tracks),args)
    assert tracks.writes==0 and state(tmp_path)=='already_complete'
    assert tracks.doc['audio_features']['energy']==.9

def test_concurrent_feature_update_fails_cas_and_remains_retryable(tmp_path):
    args=prepare(tmp_path);tracks=Tracks(conflict=True)
    features(SimpleNamespace(tracks=tracks),args)
    assert state(tmp_path)=='retry_wait' and tracks.doc['audio_features']=={}

def test_unversioned_fallback_is_never_published(tmp_path):
    args=prepare(tmp_path,{**VECTOR,'note':'fake fallback'});tracks=Tracks()
    features(SimpleNamespace(tracks=tracks),args)
    assert tracks.writes==0 and state(tmp_path)=='retry_wait'
