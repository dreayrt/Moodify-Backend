from datetime import datetime, timezone, timedelta
from monitor_recommendations import render

def test_exporter_has_fixed_metric_names_and_rejects_personal_labels():
    now=datetime.now(timezone.utc)
    readiness={'catalogTracks':12,'eligibleTracks':3,'issues':{'NO_LICENSE':9,'MISSING_MOOD_FEATURES':2,'listener@example.invalid':1}}
    runtime={'flags':{'enabled':True,'collaborative':False,'campaigns':False},'counters':{'queue.requests':4,'listener@example.invalid':999}}
    text=render(readiness,{'runtime':runtime},{'at':(now-timedelta(minutes=31)).isoformat(),'status':'ready'},now)
    assert 'catalog_features_complete 10' in text and 'worker_ready 0' in text
    assert 'queue_requests 4' in text and 'listener' not in text and '@' not in text
