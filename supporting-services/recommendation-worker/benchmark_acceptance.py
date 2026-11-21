"""Bounded local acceptance benchmark; synthetic isolated catalog, real model dependency."""
from concurrent.futures import ThreadPoolExecutor
import json
import math
import time
from pathlib import Path
from urllib.request import Request, urlopen
import worker
from integration_smoke import DISPOSABLE_DATABASE

SAMPLES=[
    'Hôm nay tôi rất vui và yêu đời!',
    'Tôi buồn và nhớ những ngày đã qua.',
    'Công việc làm tôi căng thẳng, áp lực quá.',
    'Tôi muốn thư giãn và bình tâm sau một ngày dài.',
]

def run():
    fixture=json.loads((worker.ROOT/'target/recommendation-browser-fixture.json').read_text())
    assert fixture['fixtureOnly'] and DISPOSABLE_DATABASE.fullmatch(fixture['database'])
    def call(kind, index=0):
        body=None if kind=='home' else json.dumps({'text':SAMPLES[index%4],'strategy':'empathy','limit':30}).encode()
        req=Request('http://127.0.0.1:62423/api/'+('recommendations/home' if kind=='home' else 'emotions/recommend'),
            data=body,headers={'Authorization':'Bearer '+fixture['token'],'Content-Type':'application/json'})
        start=time.perf_counter()
        try:
            with urlopen(req,timeout=15) as response: result=json.load(response)
            return {'ms':round((time.perf_counter()-start)*1000,2),'ok':True,
                'matchStatus':result.get('matchStatus'),'emotion':(result.get('emotion') or {}).get('label')}
        except Exception as error:
            return {'ms':round((time.perf_counter()-start)*1000,2),'ok':False,'errorType':type(error).__name__,'status':getattr(error,'code',None)}
    results={}
    for kind,target in [('home',1500),('emotion',5000)]:
        warm=[call(kind,i) for i in range(5)]
        with ThreadPoolExecutor(max_workers=5) as pool: rows=list(pool.map(lambda i:call(kind,i),range(100)))
        times=sorted(row['ms'] for row in rows)
        results[kind]={'warmup':warm,'requests':100,'concurrency':5,'p50Ms':times[49],'p95Ms':times[94],
            'maxMs':times[-1],'errors':sum(not r['ok'] for r in rows),'targetMs':target,
            'passed':all(r['ok'] for r in rows) and times[94]<=target,'samples':rows}
        print(kind,json.dumps({k:v for k,v in results[kind].items() if k not in {'warmup','samples'}}),flush=True)
    report={'environment':'local isolated SQL/Mongo + copied backend + SSH to real self-hosted production emotion model',
        'fixtureCatalog':426,'productionServingRights':False,'listenerQualityVerified':False,'results':results}
    output=worker.ROOT.parent/'output/recommendation-check-20261106/v13-benchmark.json'
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':run()
