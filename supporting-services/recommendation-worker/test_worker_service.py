import json
import pytest
import worker_service as service


class Connection:
    def __init__(self, acquired=1, owned=1):
        self.acquired, self.owned = acquired, owned
        self.query = ''
        self.closed = False

    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, query, params): self.query = query
    def fetchone(self):
        return {'acquired': self.acquired} if 'GET_LOCK' in self.query else {'owned': self.owned}
    def ping(self, reconnect): assert reconnect is False
    def close(self): self.closed = True


def test_duplicate_worker_never_generates_and_writes_failure_heartbeat(monkeypatch, tmp_path):
    sql, client = Connection(acquired=0), Connection()
    monkeypatch.setattr(service.worker, 'connections', lambda env: (sql, object(), client))
    monkeypatch.setattr(service.worker, 'run_once', lambda *args, **kwargs: pytest.fail('lock must precede work'))
    with pytest.raises(SystemExit): service.serve(None, tmp_path, once=True)
    status = json.loads((tmp_path/'heartbeat.json').read_text())
    assert status['status'] == 'retry_wait'
    assert status['error_type'] == 'RuntimeError'
    assert sql.closed and client.closed


def test_lost_lock_blocks_profile_publication(monkeypatch, tmp_path):
    sql, client = Connection(owned=0), Connection()
    monkeypatch.setattr(service.worker, 'connections', lambda env: (sql, object(), client))
    def run(*args, before_publish, **kwargs):
        before_publish()
        pytest.fail('must not publish after lock loss')
    monkeypatch.setattr(service.worker, 'run_once', run)
    with pytest.raises(SystemExit): service.serve(None, tmp_path, once=True)
    assert json.loads((tmp_path/'heartbeat.json').read_text())['status'] == 'retry_wait'


def test_reconnect_reacquires_lock_and_retries_failed_training(monkeypatch, tmp_path):
    monkeypatch.setenv('RECOMMENDATIONS_TRAINING_ENABLED', 'true'); monkeypatch.delenv('RECOMMENDATIONS_DEMO_CATALOG_ENABLED', raising=False)
    connections = [Connection(owned=0), Connection()]
    used, training, delays = [], [], []
    def connect(env):
        sql = connections[len(used)]; used.append(sql)
        return sql, object(), Connection()
    def run(sql, mongo, train, before_publish, **kwargs):
        training.append(train); before_publish(); return {'published': 1}
    class Stop(Exception): pass
    def sleep(delay):
        delays.append(delay)
        if len(delays) == 2: raise Stop()
    monkeypatch.setattr(service.worker, 'connections', connect)
    monkeypatch.setattr(service.worker, 'run_once', run)
    monkeypatch.setattr(service.time, 'sleep', sleep)
    with pytest.raises(Stop): service.serve(None, tmp_path)
    assert len(used) == 2 and all(sql.closed for sql in used)
    assert training == [True, True]
    assert 15 <= delays[0] <= 65 and delays[1] == 900
    assert json.loads((tmp_path/'heartbeat.json').read_text())['status'] == 'ready'
