"""Create missing CONTENT_LEAD accounts for exact catalog artists with tracks.

Default is preview. Writes only users; never changes existing users or catalog.
Requires pymysql, pymongo, python-dotenv, bcrypt (run in the worker venv).
Credentials and before/after manifests must be stored under ignored storage/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
import unicodedata

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'recommendation-worker'))
import worker
import bcrypt


def slug(name):
    value = unicodedata.normalize('NFKD', name.lower().replace('đ', 'd'))
    return re.sub(r'[^a-z0-9]+', '_', value.encode('ascii', 'ignore').decode()).strip('_')[:40] or 'artist'


def private_json(path, value):
    # Exclusive creation prevents overwriting an earlier credentials/backup file.
    with path.open('x', encoding='utf-8') as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2, default=str)
        stream.flush()
        os.fsync(stream.fileno())


def run(args):
    output = args.output.resolve()
    storage = Path(__file__).resolve().parents[1] / 'storage'
    if not output.is_relative_to(storage.resolve()):
        raise ValueError('Output must be inside ignored backend storage/')
    output.mkdir(parents=True, exist_ok=True)
    sql, mongo, client = worker.connections(args.env)
    try:
        artists = {}
        duplicates = set()
        for doc in mongo.artists.find({}, {'spotify_id': 1, 'name': 1, 'image_url': 1}):
            key = doc.get('spotify_id')
            if not isinstance(key, str) or not key.strip():
                continue
            if key in artists:
                duplicates.add(key)
            artists[key] = doc
        counts = {}
        missing = 0
        for track in mongo.tracks.find({}, {'artist_spotify_id': 1}):
            key = track.get('artist_spotify_id')
            if isinstance(key, str) and key.strip():
                counts[key] = counts.get(key, 0) + 1
            else:
                missing += 1
        with sql.cursor() as cursor:
            cursor.execute('START TRANSACTION' if args.apply else 'START TRANSACTION READ ONLY')
            cursor.execute('SELECT * FROM users')
            before = cursor.fetchall()
            usernames = {str(u['username']).casefold() for u in before if u['username']}
            emails = {str(u['email']).casefold() for u in before if u['email']}
            linked = {u['artist_spotify_id'] for u in before if u['artist_spotify_id']}
            candidates, skipped = [], []
            for key in sorted(counts):
                if key in linked:
                    skipped.append({'artist_id': key, 'reason': 'existing_account_preserved'})
                    continue
                if key not in artists or key in duplicates or not artists[key].get('name', '').strip():
                    skipped.append({'artist_id': key, 'reason': 'missing_or_ambiguous_artist'})
                    continue
                artist = artists[key]
                suffix = hashlib.sha256(key.encode()).hexdigest()[:12]
                base = f'artist_{slug(artist["name"])}_{suffix}'
                username = base
                index = 2
                while username.casefold() in usernames or f'{username}@artist.moodify.local'.casefold() in emails:
                    username = f'{base}_{index}'
                    index += 1
                email = f'{username}@artist.moodify.local'
                usernames.add(username.casefold())
                emails.add(email.casefold())
                candidates.append({'artist_id': key, 'artist_name': artist['name'], 'username': username,
                                   'email': email, 'avatar_url': artist.get('image_url'), 'track_count': counts[key]})
            preview = {'candidates': candidates, 'skipped': skipped, 'tracks_without_artist_id': missing}
            private_json(output / 'preview.json', preview)
            if not args.apply:
                sql.rollback()
                return {'mode': 'preview', 'create': len(candidates), 'skipped': len(skipped), 'tracks_without_artist_id': missing}
            # Save recovery material before any INSERT. Never export it to logs.
            private_json(output / 'users-before.json', before)
            credentials = []
            for row in candidates:
                password = secrets.token_urlsafe(24)
                credentials.append({**row, 'password': password,
                                    'password_hash': bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()})
            private_json(output / 'credentials.json', credentials)
            created = []
            for row in credentials:
                cursor.execute('''INSERT INTO users
                    (full_name,phone,email,username,password,avatar_url,role,artist_spotify_id,status)
                    VALUES (%s,NULL,%s,%s,%s,%s,'CONTENT_LEAD',%s,'ACTIVE')''',
                    (row['artist_name'], row['email'], row['username'], row['password_hash'], row['avatar_url'], row['artist_id']))
                created.append({'id': cursor.lastrowid, 'artist_id': row['artist_id'], 'username': row['username']})
            cursor.execute('SELECT * FROM users')
            after = cursor.fetchall()
            after_by_id = {u['id']: u for u in after}
            if any(after_by_id.get(u['id']) != u for u in before):
                raise RuntimeError('Existing user changed: rolling back')
            for row in credentials:
                matches = [u for u in after if u['artist_spotify_id'] == row['artist_id']]
                if len(matches) != 1 or matches[0]['role'] != 'CONTENT_LEAD' or not bcrypt.checkpw(row['password'].encode(), matches[0]['password'].encode()):
                    raise RuntimeError('Created account verification failed: rolling back')
            private_json(output / 'inserted-users.json', created)
            sql.commit()
            return {'mode': 'applied', 'created': len(created), 'skipped': len(skipped), 'existing_users_unchanged': len(before)}
    finally:
        sql.rollback()
        sql.close()
        client.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(run(parser.parse_args()), ensure_ascii=False))
