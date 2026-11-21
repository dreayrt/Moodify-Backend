from datetime import datetime, timezone
import pytest
from worker import eligible_track_ids
from catalog_maintenance import valid_features, source_path
from integration_smoke import smoke_connections

def test_simulated_licenses_are_excluded_unless_demo_mode_is_explicit():
    t={'_id':'demo','status':'published','visibility':'public','moderation_status':'approved','local_path':'data/audio/a.mp3','recommendation_demo':{'simulated':True}}
    license={'id':1,'track_id':'demo','status':'ACTIVE','license_type':'DEMO_SIMULATED'}
    now=datetime.now(timezone.utc)
    assert eligible_track_ids([t],[license],now)==set()
    assert eligible_track_ids([t],[license],now,demo_catalog=True)=={'demo'}
    assert eligible_track_ids([{**t,'visibility':'private'}],[license],now,demo_catalog=True)==set()

def test_training_eligibility_is_strict_even_for_imports():
    now=datetime.now(timezone.utc)
    t={'_id':'canonical','spotify_id':'external','status':'published','visibility':'public',
       'moderation_status':'approved','local_path':'data/audio/song.mp3','catalog_migration_batch':'allowed'}
    license={'id':1,'track_id':'canonical','status':'ACTIVE'}
    assert eligible_track_ids([t],[license],now)=={'canonical'}
    assert eligible_track_ids([t],[],now)==set()
    assert eligible_track_ids([{**t,'moderation_status':None}],[license],now)==set()
    assert eligible_track_ids([{**t,'local_path':'data/../private'}],[license],now)==set()

@pytest.mark.parametrize('bad',[float('nan'),float('inf'),True,None,-.2,1.2])
def test_feature_validation_never_accepts_missing_or_fake_numeric_values(bad):
    features=dict(valence=.5,energy=.5,danceability=.5,acousticness=.5,bpm=120)
    assert valid_features(features)
    features['energy']=bad
    assert not valid_features(features)

def test_technical_audio_metadata_is_not_a_mood_feature_vector():
    assert not valid_features({'duration_sec':180,'sample_rate':44100,'bitrate_kbps':320})
    with pytest.raises(ValueError):source_path('data/../private.mp3')

def test_smoke_never_falls_back_to_application_environment(monkeypatch):
    monkeypatch.delenv('RECOMMENDATION_SMOKE_ENV',raising=False)
    with pytest.raises(RuntimeError,match='application .env is forbidden'):smoke_connections()
