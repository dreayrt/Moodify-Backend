"""Prepare, apply and read back an explicitly simulated, Mongo-only editorial seed."""

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
from uuid import NAMESPACE_URL, uuid5

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'supporting-services/recommendation-worker'))
import worker
from bson import ObjectId, json_util
from pymongo.errors import DuplicateKeyError


PLAYLISTS = [
    '6af1811d846be1f56ac7747f', '6af1811d846be1f56ac77480',
    '6af1811e846be1f56ac77481', '6af1811e846be1f56ac77482',
    '6af1811e846be1f56ac77483', '6af1811f846be1f56ac77484',
    '6af1811f846be1f56ac77485',
]
BATCH = 'editorial-playlists-20261109-v1'
KIND = 'SIMULATED_PLAYLIST_BEHAVIOR'


def scope(sql, db):
    assert db.name == 'music_streaming', 'Unexpected database'
    with sql.cursor() as cursor:
        cursor.execute("""SELECT id,username FROM users WHERE role='USER' AND status='ACTIVE'
            AND LEFT(username,9)='rec_seed_' AND email LIKE '%@recommendation-seed.invalid' ORDER BY id""")
        users = list(cursor.fetchall())
        cursor.execute('SELECT id,track_id,status,issue_date,expiry_date,license_type FROM song_licenses')
        licenses = list(cursor.fetchall())
    assert len(users) >= 6
    users = users[:6]
    playlists = list(db.playlists.find({'_id': {'$in': [ObjectId(i) for i in PLAYLISTS]}}).sort('_id', 1))
    assert len(playlists) == 7
    tracks = list(db.tracks.find({}))
    aliases = worker.canonical_tracks(tracks)
    eligible = worker.eligible_track_ids(tracks, licenses, datetime.now(timezone.utc), True)
    by_id = {str(track['_id']): track for track in tracks}
    for playlist in playlists:
        assert playlist.get('is_public') is True and playlist.get('owner_user_id') == 111
        ids = [aliases.get(i) for i in playlist['track_spotify_ids']]
        assert len(ids) == len(set(ids)) == 5 and set(ids) <= eligible
    return users, playlists, aliases, by_id


def prepare(sql, db):
    users, playlists, aliases, tracks = scope(sql, db)
    collection = db.recommendation_events
    assert collection.count_documents({'seed_batch': BATCH}) == 0, 'Reuse the existing prepared manifest for retries'
    anchor = datetime.now(timezone.utc).replace(microsecond=0)
    events = []
    for index, playlist in enumerate(playlists):
        for position in range(6):
            actor = users[(index + position) % len(users)]
            track_id = aliases[playlist['track_spotify_ids'][(index + position * 2) % 5]]
            track_duration = tracks[track_id].get('duration_ms')
            assert isinstance(track_duration, (int, float)) and track_duration >= 30000
            event_type = 'LISTEN' if position < 3 else 'SKIP' if position < 5 else 'SAVE'
            duration = min((43000, 91000, 167000, 6000, 13000, 0)[position], int(track_duration))
            at = anchor - timedelta(days=index + 1, minutes=position * 17 + 11)
            identity = f'{BATCH}:{playlist["_id"]}:{actor["id"]}:{track_id}:{event_type}'
            events.append({
                '_id': 'simulated-playlist:' + str(uuid5(NAMESPACE_URL, identity)),
                'kind': KIND, 'provenance': 'SIMULATED', 'schema_version': 1,
                'seed_batch': BATCH, 'user_id': actor['id'], 'event_type': event_type,
                'entity_type': 'TRACK', 'entity_id': track_id,
                'parent_playlist_type': 'PLAYLIST', 'parent_playlist_id': str(playlist['_id']),
                'listened_duration_ms': duration, 'occurred_at': at,
                'received_at': anchor, 'expires_at': at + timedelta(days=30),
                'revenue_eligible': False,
            })
    assert len(events) == len({event['_id'] for event in events}) == 42
    return {'batch': BATCH, 'prepared_at': anchor, 'users': users, 'playlists_before': playlists,
            'existing_records': [], 'events': events, 'sql_writes': 0}


def validate(manifest, sql, db):
    users, playlists, aliases, tracks = scope(sql, db)
    assert manifest['batch'] == BATCH and manifest['sql_writes'] == 0
    assert manifest['existing_records'] == [] and manifest['playlists_before'] == playlists
    assert manifest['users'] == users
    events = manifest['events']
    assert len(events) == len({event['_id'] for event in events}) == 42
    assert Counter(event['event_type'] for event in events) == {'LISTEN': 21, 'SKIP': 14, 'SAVE': 7}
    for event in events:
        assert event['seed_batch'] == BATCH and event['kind'] == KIND
        assert event['provenance'] == 'SIMULATED' and event['revenue_eligible'] is False
        assert event['user_id'] in {user['id'] for user in users}
        playlist = next(p for p in playlists if str(p['_id']) == event['parent_playlist_id'])
        assert event['entity_id'] in {aliases[i] for i in playlist['track_spotify_ids']}
        assert 0 <= event['listened_duration_ms'] <= tracks[event['entity_id']]['duration_ms']
        assert event['occurred_at'] <= manifest['prepared_at'] < event['expires_at']
    return events


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'apply', 'verify'))
    args = parser.parse_args()
    sql, db, client = worker.connections(Path('/dev/null'))
    try:
        if args.action == 'prepare':
            print(json_util.dumps(prepare(sql, db), json_options=json_util.CANONICAL_JSON_OPTIONS))
            return
        manifest = json_util.loads(sys.stdin.read(), json_options=json_util.JSONOptions(tz_aware=True))
        events = validate(manifest, sql, db)
        collection = db.recommendation_events
        for event in events:
            existing = collection.find_one({'_id': event['_id']})
            assert existing is None or existing == event, 'Conflicting existing seed record'
        inserted = 0
        if args.action == 'apply':
            for event in events:
                if collection.find_one({'_id': event['_id']}) is None:
                    print(json_util.dumps({'stage': 'insert-intent', 'id': event['_id']}), flush=True)
                    try:
                        collection.insert_one(event)
                        inserted += 1
                    except DuplicateKeyError:
                        assert collection.find_one({'_id': event['_id']}) == event
                    print(json_util.dumps({'stage': 'inserted', 'id': event['_id']}), flush=True)
        actual = list(collection.find({'seed_batch': BATCH}))
        assert len(actual) == len(events) and {d['_id'] for d in actual} == {d['_id'] for d in events}
        assert all(collection.find_one({'_id': event['_id']}) == event for event in events)
        print(json_util.dumps({'stage': 'verified', 'batch': BATCH, 'records': len(actual),
              'inserted_this_run': inserted, 'event_types': dict(Counter(e['event_type'] for e in actual)),
              'playlists': 7, 'accounts': 6, 'sql_writes': 0}), flush=True)
    finally:
        sql.close()
        client.close()


if __name__ == '__main__':
    run()
