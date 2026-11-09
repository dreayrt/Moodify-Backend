"""Supervised recommender with reconnect, bounded retries and per-session SQL lock."""
from datetime import datetime, timezone
import argparse
import json
import os
from pathlib import Path
import random
import signal
from contextlib import contextmanager
import time
import worker

LOCK = 'moodify_recommendation_worker_v1'

@contextmanager
def deadline(seconds):
    if not hasattr(signal,'setitimer'):
        yield;return
    def expired(signum,frame):raise TimeoutError('worker_batch_deadline')
    previous=signal.signal(signal.SIGALRM,expired)
    signal.setitimer(signal.ITIMER_REAL,seconds)
    try:yield
    finally:
        signal.setitimer(signal.ITIMER_REAL,0);signal.signal(signal.SIGALRM,previous)

def serve(env, state, once=False):
    state.mkdir(parents=True, exist_ok=True)
    failures = 0
    last_training = 0.0
    while True:
        sql = client = None
        status = {'at': datetime.now(timezone.utc).isoformat(), 'status': 'starting'}
        try:
            sql, mongo, client = worker.connections(env)
            with sql.cursor() as cursor:
                cursor.execute('SELECT GET_LOCK(%s,0) acquired', (LOCK,))
                if cursor.fetchone()['acquired'] != 1:
                    raise RuntimeError('worker_lock_unavailable')
            def guard():
                sql.ping(reconnect=False)
                with sql.cursor() as cursor:
                    cursor.execute('SELECT IS_USED_LOCK(%s)=CONNECTION_ID() owned', (LOCK,))
                    if cursor.fetchone()['owned'] != 1:
                        raise RuntimeError('worker_lock_lost')
            train = (time.monotonic() - last_training >= 86400 or last_training == 0) and \
                os.environ.get('RECOMMENDATIONS_TRAINING_ENABLED','false').lower()=='true' and \
                os.environ.get('RECOMMENDATIONS_DEMO_CATALOG_ENABLED','false').lower()!='true'
            with deadline(max(30,min(600,int(os.environ.get('RECOMMENDATIONS_BATCH_TIMEOUT_SECONDS','180'))))):
                summary = worker.run_once(sql, mongo, train=train, artifact_dir=state/'models', before_publish=guard)
            if train: last_training = time.monotonic()
            status.update(status='ready', summary=summary)
            failures = 0
        except Exception as error:
            failures += 1
            # No credentials, queries, listener IDs or arbitrary exception messages.
            status.update(status='retry_wait', error_type=type(error).__name__, failures=failures)
        finally:
            if sql is not None: sql.close()
            if client is not None: client.close()
            status['at'] = datetime.now(timezone.utc).isoformat()
            temporary = state/'heartbeat.tmp'
            temporary.write_text(json.dumps(status, default=str), encoding='utf-8')
            temporary.replace(state/'heartbeat.json')
            print(json.dumps(status, default=str), flush=True)
        if once:
            if failures: raise SystemExit(1)
            return
        delay = min(900, 15 * 2 ** min(failures, 6)) + random.uniform(0, 5) if failures else 900
        time.sleep(delay)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--env', type=Path, required=True)
    parser.add_argument('--state', type=Path, default=Path('/state'))
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    serve(args.env, args.state, args.once)
