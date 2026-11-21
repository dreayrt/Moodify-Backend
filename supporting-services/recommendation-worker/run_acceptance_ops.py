"""Run the real supervised worker and exporter only against the disposable fixture."""
import json
import os
from datetime import datetime, timezone
from integration_smoke import smoke_connections, DISPOSABLE_DATABASE
from worker_service import serve
from monitor_recommendations import collect
import worker

fixture=json.loads((worker.ROOT/'target/recommendation-browser-fixture.json').read_text())
assert fixture['fixtureOnly'] and DISPOSABLE_DATABASE.fullmatch(fixture['database'])
sql,_,client=smoke_connections();sql.select_db(fixture['database']);sql.close();client.close()
os.environ.update(DATABASE_SOURCE_URL=f"jdbc:mysql://127.0.0.1:13316/{fixture['database']}",MONGODB_DATABASE=fixture['database'])
state=worker.ROOT/'target/recommendation-acceptance-worker'
serve(None,state,once=True)
heartbeat=json.loads((state/'heartbeat.json').read_text())
body=collect('http://127.0.0.1:62423',fixture['adminToken'],heartbeat)
output=worker.ROOT.parent/'output/recommendation-check-20261106/v13-monitor.prom'
output.write_text(body,encoding='utf-8')
assert 'moodify_recommendation_scrape_success 1' in body
assert 'moodify_recommendation_worker_ready 1' in body
print('Worker heartbeat and authenticated bounded-label exporter verified on isolated fixture')
