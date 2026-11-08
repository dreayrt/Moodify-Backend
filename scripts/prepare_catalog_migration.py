"""Build a read-only, reviewable catalog migration plan; never writes to either database."""
import argparse
import collections
import csv
import hashlib
import json
import math
import re
import statistics
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from bson import json_util
from dotenv import dotenv_values
from pymongo import MongoClient


def normalize(value):
    value = unicodedata.normalize('NFKD', (value or '').casefold().replace('đ', 'd'))
    return ' '.join(re.sub(r'[^a-z0-9]+', ' ', ''.join(c for c in value if not unicodedata.combining(c))).split())


def versions(value):
    text = normalize(value)
    patterns = {'remix': r'\bremix\b', 'live': r'\blive\b', 'acoustic': r'\bacoustic\b',
                'sped_up': r'\bsped\b|\bspeed\b', 'slowed': r'\bslowed\b',
                'instrumental': r'\binstrumental\b|\bkaraoke\b'}
    return sorted(name for name, pattern in patterns.items() if re.search(pattern, text))


def strict_spotify(row, track):
    if not track or track.get('error'):
        return False
    try:
        if not (row.get('duration') and track.get('duration_ms')):
            return False
        expected_isrc, actual_isrc = row.get('isrc'), track.get('isrc')
        return (normalize(row['title']) == normalize(track.get('name'))
                and normalize(row['artists'].split(',')[0]) in [normalize(a.get('name')) for a in track.get('artists', [])]
                and versions(row['title']) == versions(track.get('name'))
                and abs(float(row['duration']) - track['duration_ms'] / 1000) <= 3
                and not (expected_isrc and actual_isrc and expected_isrc.upper() != actual_isrc.upper()))
    except (ValueError, TypeError, KeyError):
        return False


def title_without_credit(value):
    text = re.sub(r'\s*[\(\[]\s*(?:feat\.?|ft\.?)\s+[^)\]]+[)\]]', '', value or '', flags=re.I)
    text = re.sub(r'\s*[\(\[]explicit[)\]]', '', text, flags=re.I)
    return normalize(text)


def normalized_spotify(row, track, measured_duration, identity_assessment):
    """Handle explicit/featured-credit spelling, without accepting different performers."""
    if not track or track.get('error') or identity_assessment == 'conflict':
        return False
    try:
        expected_artists = [normalize(x.strip()) for x in row['artists'].split(',') if x.strip()]
        actual_artists = {normalize(x.get('name')) for x in track.get('artists', [])}
        expected_isrc, actual_isrc = row.get('isrc'), track.get('isrc')
        explicit = bool(re.search(r'[\(\[]explicit[)\]]', row['title'], re.I))
        return (bool(title_without_credit(row['title']))
                and title_without_credit(row['title']) == title_without_credit(track.get('name'))
                and bool(expected_artists) and all(x in actual_artists for x in expected_artists)
                and versions(row['title']) == versions(track.get('name'))
                and (not explicit or track.get('explicit') is True)
                and measured_duration > 0 and bool(track.get('duration_ms'))
                and abs(measured_duration - track['duration_ms']/1000) <= 3
                and not (expected_isrc and actual_isrc and expected_isrc.upper() != actual_isrc.upper()))
    except (ValueError, TypeError, KeyError):
        return False


def current_revision(pilot, recognition):
    spotify = pilot.get('spotify') or {}
    revision = [pilot.get('title'), pilot.get('artists'), pilot.get('duration_seconds'),
                spotify.get('matched'), spotify.get('isrc'), spotify.get('duration_seconds')]
    digest = hashlib.sha256(json.dumps(revision, ensure_ascii=False).encode()).hexdigest()
    return recognition.get('target_revision') == digest


def acoustic_spotify(row, pilot, candidates, measured_duration):
    """Resolve a track only when three stored samples agree with one Spotify ISRC."""
    recognition = ((pilot.get('audio') or {}).get('recognition') or {})
    samples = recognition.get('samples') or []
    if not current_revision(pilot, recognition) or len(samples) != 3:
        return None
    if not all(s.get('recognized') and s.get('key') and s.get('isrc') for s in samples):
        return None
    if len({s['key'] for s in samples}) != 1 or len({s['isrc'].upper() for s in samples}) != 1:
        return None
    try:
        if any(not isinstance(s.get(k), (int, float)) or not math.isfinite(s[k])
               for s in samples for k in ('start_seconds', 'offset', 'timeskew', 'frequencyskew')):
            return None
        if any(abs(s[k]) > .005 for s in samples for k in ('timeskew', 'frequencyskew')):
            return None
        offsets = [s['offset'] - s['start_seconds'] for s in samples]
        if max(offsets) - min(offsets) > 1.5 or abs(statistics.median(offsets)) > 2:
            return None
        selected = {}
        for candidate in candidates:
            title = normalize(candidate.get('name'))
            target = normalize(row['title'])
            artists = [normalize(a.get('name')) for a in candidate.get('artists', [])]
            if (candidate.get('id') and (candidate.get('isrc') or '').upper() == samples[0]['isrc'].upper()
                and (title == target or title.startswith(target + ' feat '))
                and normalize(row['artists'].split(',')[0]) in artists
                and versions(row['title']) == versions(candidate.get('name'))
                and candidate.get('duration_ms') and measured_duration > 0
                and abs(measured_duration - candidate['duration_ms'] / 1000) <= 3):
                selected[candidate['id']] = candidate
        return next(iter(selected.values())) if len(selected) == 1 else None
    except (ValueError, TypeError, KeyError):
        return None


def apple_preview_metadata(row, pilot, lookups, measured_duration):
    """Use Apple fields only when a current independently matched preview identifies the file."""
    recognition = ((pilot.get('audio') or {}).get('recognition') or {})
    preview = recognition.get('independent_preview_reference') or {}
    catalog = preview.get('catalog') or {}
    if not (current_revision(pilot, recognition) and preview.get('matched') is True
            and preview.get('reference_sha256') and catalog.get('provider') == 'apple_itunes'):
        return None
    entries = lookups.get(str(catalog.get('track_id'))) or []
    if not isinstance(entries, list):
        return None
    selected = []
    for entry in entries:
        try:
            if (entry.get('trackId') == catalog.get('track_id')
                and normalize(row['title']) == normalize(entry.get('trackName'))
                and normalize(row['artists'].split(',')[0]) in normalize(entry.get('artistName'))
                and versions(row['title']) == versions(entry.get('trackName'))
                and entry.get('trackTimeMillis') and measured_duration > 0
                and abs(measured_duration - entry['trackTimeMillis'] / 1000) <= 3):
                selected.append(entry)
        except (ValueError, TypeError, KeyError):
            continue
    return selected[0] if len(selected) == 1 else None


def fresh_apple_preview_metadata(row, preview, lookups, measured_duration):
    """Bind a newly checked Apple preview to the same source title, artists and duration."""
    catalog = preview.get('catalog') or {}
    try:
        if not (preview.get('matched') is True and len(preview.get('reference_sha256') or '') == 64
                and preview.get('waveform_correlation', 0) >= .93
                and preview.get('reference_coverage', 0) >= .85
                and preview.get('worst_window', 0) >= .75
                and catalog.get('provider') == 'apple_itunes'):
            return None
        entries = lookups.get(str(catalog.get('track_id'))) or []
        if not isinstance(entries, list):
            return None
        matches = []
        for entry in entries:
            names = ' ' + normalize(entry.get('artistName')) + ' '
            if (entry.get('trackId') == catalog.get('track_id')
                and normalize(row['title']) == normalize(entry.get('trackName'))
                and all(' ' + normalize(a.strip()) + ' ' in names for a in row['artists'].split(',') if a.strip())
                and versions(row['title']) == versions(entry.get('trackName'))
                and entry.get('trackTimeMillis') and measured_duration > 0
                and abs(measured_duration - entry['trackTimeMillis'] / 1000) <= 3):
                matches.append(entry)
        return matches[0] if len(matches) == 1 else None
    except (ValueError, TypeError, KeyError):
        return None


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def genres_from_evidence(evidence):
    """Map explicit source genres only; country/market labels are not genres."""
    mapping = {'v pop': 'V-Pop', 'vpop': 'V-Pop', 'rap viet': 'Rap',
               'r b viet': 'R&B', 'dance viet': 'Dance', 'rock viet': 'Rock',
               'pop': 'Pop', 'rap': 'Rap', 'hip hop rap': 'Rap',
               'r b soul': 'R&B', 'dance': 'Dance', 'rock': 'Rock'}
    genres = []
    for entry in evidence:
        for raw in (entry.get('raw') or '').split(','):
            value = mapping.get(normalize(raw))
            if value and value not in genres:
                genres.append(value)
    return genres


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--collector', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--backend-env', required=True, type=Path)
    parser.add_argument('--quality-csv', required=True, type=Path)
    args = parser.parse_args()
    manifest = read_json(args.collector / 'data/production_export/production_manifest.json')
    spotify = read_json(args.output / 'spotify-track-cache.json')
    apple = read_json(args.output / 'apple-metadata-cache.json')
    search_path = args.output / 'spotify-unresolved-search.json'
    searches = read_json(search_path) if search_path.exists() else {}
    preview_path = args.output / 'apple-preview-lookups.json'
    preview_lookups = read_json(preview_path) if preview_path.exists() else {}
    fresh_preview_path = args.output / 'apple-preview-recheck.json'
    fresh_previews = read_json(fresh_preview_path) if fresh_preview_path.exists() else {}
    media = {v['sha256']: v for v in read_json(args.output / 'media-staging-manifest.json')}
    with args.quality_csv.open(encoding='utf-8-sig', newline='') as f:
        quality = {r['sha256']: r for r in csv.DictReader(f)}
    pilots = {}
    for batch in manifest['sources']:
        if batch.startswith('vn_'):
            for t in read_json(args.collector / 'data/collector-v2/pilots' / batch / 'report.json')['tracks']:
                pilots[(batch, (t.get('audio') or {}).get('sha256'))] = t
    config = dotenv_values(args.backend_env)
    uri = config['MONGODB_URI']
    if not (uri.startswith('mongodb://127.0.0.1') or uri.startswith('mongodb://localhost')):
        raise ValueError('Only the explicitly configured local MongoDB is allowed for this preview')
    db = MongoClient(uri, serverSelectionTimeoutMS=5000)[config['MONGODB_DATABASE']]
    existing = collections.defaultdict(list)
    for document in db.tracks.find({}):
        if document.get('spotify_id'):
            existing[document['spotify_id']].append(document)
    # The incoming pilot reference report directly compares these two hashes and
    # identifies the same recording. Retain the second file as an alternative.
    duplicate_to_primary = {'088e00ad0f8d': 'c24204ae1ed9'}
    plan = []
    for number, row in enumerate(manifest['tracks'], 1):
        h = row['sha256']
        if h not in media or media[h]['status'] != 'verified' or h not in quality:
            raise ValueError('Missing verified staging media or quality evidence for row '+str(number))
        q = quality[h]
        track = spotify.get(row.get('spotify_id')) or {}
        official_match = strict_spotify(row, track)
        batch = row['source_batch']
        pilot = pilots.get((batch, h), {})
        acoustic_match = None
        normalized_match = False
        if not official_match and pilot:
            candidates = [track] + searches.get(h, {}).get('results', [])
            acoustic_match = acoustic_spotify(row, pilot, candidates, float(q.get('duration_seconds') or 0))
            if acoustic_match:
                track = acoustic_match
                official_match = True
        if not official_match:
            normalized_match = normalized_spotify(row, track, float(q.get('duration_seconds') or 0),
                                                  q.get('identity_assessment'))
            official_match = normalized_match
        candidate_id = track.get('id') if official_match else None
        primary_prefix = next((v for k, v in duplicate_to_primary.items() if h.startswith(k)), None)
        duplicate = bool(primary_prefix)
        candidate_primary = next((x for x in manifest['tracks'] if primary_prefix and x['sha256'].startswith(primary_prefix)), None)
        if duplicate and (not candidate_primary or candidate_primary.get('spotify_id') != candidate_id):
            raise ValueError('Duplicate relationship no longer matches source data')
        if duplicate:
            primary_pilot = pilots.get((candidate_primary['source_batch'], candidate_primary['sha256']), {})
            evidence = primary_pilot.get('audio') or {}
            reference = evidence.get('reference_path') or ''
            identity = (evidence.get('checks') or {}).get('identity_match') or {}
            if Path(reference).stem != h or identity.get('status') != 'passed':
                raise ValueError('Duplicate lacks the recorded full-reference comparison')
        metadata = {}
        preview_match = None
        fresh_preview_match = None
        if official_match:
            album = track.get('album') or {}
            artists = track.get('artists') or []
            duration = track.get('duration_ms')
            metadata = {'spotify_id': candidate_id, 'name': track.get('name'),
                        'artist_name': artists[0]['name'] if artists else None,
                        'artist_spotify_id': artists[0]['id'] if artists else None,
                        'featured_artists': ', '.join(a['name'] for a in artists[1:]) or None,
                        'album_spotify_id': album.get('id'), 'album_name': album.get('name'),
                        'image_url': (album.get('images') or [{}])[0].get('url'),
                        'release_date': album.get('release_date'), 'duration_ms': duration,
                        'duration_formatted': f'{duration//60000}:{(duration//1000)%60:02d}' if duration else None,
                        'track_number': track.get('track_number'), 'explicit': track.get('explicit'),
                        'spotify_url': track.get('spotify_url')}
            metadata = {k: v for k, v in metadata.items() if v is not None}
        elif pilot:
            preview_match = apple_preview_metadata(row, pilot, preview_lookups, float(q.get('duration_seconds') or 0))
        if not official_match and not preview_match:
            fresh_preview_match = fresh_apple_preview_metadata(row, fresh_previews.get(h, {}), preview_lookups,
                                                               float(q.get('duration_seconds') or 0))
            preview_match = fresh_preview_match
        if not official_match and preview_match:
            duration = preview_match['trackTimeMillis']
            metadata = {'name': preview_match.get('trackName'), 'artist_name': row['artists'].split(',')[0].strip(),
                        'album_name': preview_match.get('collectionName'), 'image_url': preview_match.get('artworkUrl100'),
                        'release_date': (preview_match.get('releaseDate') or '')[:10], 'duration_ms': duration,
                        'duration_formatted': f'{duration//60000}:{(duration//1000)%60:02d}',
                        'track_number': preview_match.get('trackNumber')}
            metadata = {k: v for k, v in metadata.items() if v not in (None, '')}
        genre_evidence = []
        lang = pilot.get('language_evidence') or {}
        if official_match and lang.get('genre') and (pilot.get('schema_url') or pilot.get('zing_url')):
            genre_evidence.append({'provider': 'Zing metadata', 'raw': lang['genre'],
                                   'url': pilot.get('schema_url') or pilot.get('zing_url')})
        if preview_match and preview_match.get('primaryGenreName'):
            genre_evidence.append({'provider': 'Apple metadata with audio preview match',
                                   'raw': preview_match['primaryGenreName'], 'url': preview_match.get('trackViewUrl')})
        for item in apple.get(h, {}).get('results', []):
            if (normalize(row['title']) == normalize(item.get('trackName'))
                and normalize(row['artists'].split(',')[0]) == normalize(item.get('artistName'))
                and versions(row['title']) == versions(item.get('trackName'))
                and item.get('trackTimeMillis') and row.get('duration')
                and abs(item['trackTimeMillis']/1000 - row['duration']) <= 3):
                genre_evidence.append({'provider': 'Apple metadata', 'raw': item.get('primaryGenreName'),
                                       'url': item.get('trackViewUrl')})
        genres = genres_from_evidence(genre_evidence) if official_match or preview_match else []
        if genres:
            metadata['genres'] = genres
            metadata['genres_raw'] = list(dict.fromkeys(x['raw'] for x in genre_evidence if x.get('raw')))
        suggestions = []
        if not official_match:
            for candidate in searches.get(h, {}).get('results', []):
                try:
                    duration = float(q.get('duration_seconds') or 0)
                    if (normalize(row['title']) == normalize(candidate.get('name'))
                        and normalize(row['artists'].split(',')[0]) in [normalize(a.get('name')) for a in candidate.get('artists', [])]
                        and versions(row['title']) == versions(candidate.get('name'))
                        and duration > 0 and candidate.get('duration_ms')
                        and abs(duration - candidate['duration_ms'] / 1000) <= 3):
                        suggestions.append({'spotify_id': candidate['id'], 'isrc': candidate.get('isrc'),
                                            'name': candidate.get('name'), 'duration_ms': candidate['duration_ms'],
                                            'url': candidate.get('spotify_url')})
                except (ValueError, TypeError, KeyError):
                    continue
        matches = existing.get(candidate_id, []) if candidate_id else []
        matching_existing = [x for x in matches if normalize(x.get('name')) == normalize(row['title'])
                             and normalize(row['artists'].split(',')[0]) in normalize(x.get('artist_name'))]
        if duplicate:
            mapping = 'duplicate_alternative'
        elif len(matches) == 1 and len(matching_existing) == 1:
            mapping = 'preserve_existing_id_and_statistics'
        elif matches:
            mapping = 'existing_catalog_conflict_review'
        elif candidate_id:
            mapping = 'new_track'
        else:
            mapping = 'spotify_id_unresolved'
        old = matching_existing[0] if mapping == 'preserve_existing_id_and_statistics' else None
        old_hash = hashlib.sha256(json_util.dumps(old, sort_keys=True).encode('utf-8')).hexdigest() if old else None
        blockers = ['rights_unverified', 'production_media_url_not_configured']
        if mapping in ('spotify_id_unresolved', 'existing_catalog_conflict_review'):
            blockers.append(mapping)
        if q['identity_assessment'] in ('unknown', 'conflict') and not acoustic_match:
            blockers.append('audio_identity_'+q['identity_assessment'])
        for key in ('decode', 'duration_status', 'clipping_status'):
            if key == 'duration_status' and (acoustic_match or normalized_match):
                continue  # Duration was checked against the corrected recording above.
            if q[key] != 'passed':
                blockers.append(key+'_'+q[key])
        if q['silence_status'] == 'warning':
            blockers.append('near_silence_listening_review')
        if not genres:
            blockers.append('genre_unverified')
        plan.append({'index': number, 'source_title': row['title'], 'source_artists': row['artists'],
                     'source_sha256': h, 'source_spotify_id': row.get('spotify_id'),
                     'spotify_id_verified_by_metadata': candidate_id, 'spotify_metadata_match': official_match,
                     'proposed_internal_key': None if candidate_id else 'moodify_' + h[:24],
                     'metadata_binding': 'three_sample_isrc' if acoustic_match else 'normalized_metadata' if normalized_match else 'strict_metadata' if official_match else 'apple_audio_preview' if preview_match else 'unresolved',
                     'fresh_preview_verified': bool(fresh_preview_match),
                     'resolution_note': ('Three recorded samples match the corrected Spotify ISRC and measured duration; '
                                         'previous quality/identity findings compared against the older ID' if acoustic_match else ''),
                     'unverified_spotify_suggestions': suggestions,
                     'proposed_metadata': metadata, 'genre_evidence': genre_evidence,
                     'media_staging_file': media[h]['target'], 'mapping': mapping,
                     'existing_track_id': str(old['_id']) if old else None,
                     'existing_document_sha256': old_hash, 'identity_assessment': q['identity_assessment'],
                     'quality_issues': q['technical_issues'], 'blockers': blockers,
                     'alternate_of_sha256': candidate_primary['sha256'] if duplicate else None,
                     'rights_status': 'unverified',
                     'apply_instruction': 'preview only; preserve _id, statistics and all unrelated fields; recheck target before any update'})
    ids = [x['spotify_id_verified_by_metadata'] for x in plan if x['mapping'] != 'duplicate_alternative' and x['spotify_id_verified_by_metadata']]
    if len(ids) != len(set(ids)):
        raise ValueError('Verified Spotify ID collision remains; manual resolution required')
    local_keys = [x['proposed_internal_key'] for x in plan if x['proposed_internal_key']]
    if len(local_keys) != len(set(local_keys)):
        raise ValueError('Proposed internal key collision; use a longer hash prefix')
    summary = {'source_rows': len(plan), 'verified_spotify_metadata_rows': sum(x['spotify_metadata_match'] for x in plan),
               'mapping': dict(collections.Counter(x['mapping'] for x in plan)),
               'tracks_with_genre_evidence': sum(bool(x['proposed_metadata'].get('genres')) for x in plan),
               'staged_audio_verified': len(media), 'rights_unverified': sum(x['rights_status'] == 'unverified' for x in plan)}
    out = {'created_at': datetime.now(timezone.utc).isoformat(), 'scope': 'preview against local MongoDB; no database changes',
           'summary': summary, 'tracks': plan}
    (args.output / 'ke-hoach-chuyen-doi.json').write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    columns = ['index','source_title','source_artists','source_sha256','source_spotify_id',
               'spotify_id_verified_by_metadata','proposed_internal_key','spotify_metadata_match','metadata_binding','resolution_note','mapping','existing_track_id',
               'identity_assessment','fresh_preview_verified','quality_issues','genre_evidence','unverified_spotify_suggestions','blockers','alternate_of_sha256']
    with (args.output / 'ke-hoach-chuyen-doi.csv').open('w', newline='', encoding='utf-8-sig') as f:
        writer = csv.DictWriter(f, fieldnames=columns, extrasaction='ignore')
        writer.writeheader()
        for row in plan:
            writer.writerow({**row, 'genre_evidence': json.dumps(row['genre_evidence'], ensure_ascii=False),
                             'unverified_spotify_suggestions': json.dumps(row['unverified_spotify_suggestions'], ensure_ascii=False),
                             'blockers': '; '.join(row['blockers'])})
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
