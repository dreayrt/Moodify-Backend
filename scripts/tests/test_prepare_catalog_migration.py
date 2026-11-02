import importlib.util
import hashlib
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('migration', Path(__file__).resolve().parents[1] / 'prepare_catalog_migration.py')
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)

class CatalogMigrationTests(unittest.TestCase):
    def setUp(self):
        self.row = {'title':'Vùng An Toàn', 'artists':'B Ray, V#', 'duration':265.95, 'isrc':'QMDA62505555'}
        self.track = {'id':'real', 'name':'Vùng An Toàn', 'artists':[{'name':'B Ray'}], 'duration_ms':265950, 'isrc':'QMDA62505555'}

    def test_verified_metadata_requires_identity_version_duration_and_isrc(self):
        self.assertTrue(migration.strict_spotify(self.row, self.track))
        for changes in ({'name':'Khác'}, {'artists':[{'name':'Khác'}]}, {'duration_ms':200000}, {'isrc':'OTHER'}, {'name':'Vùng An Toàn Remix'}):
            self.assertFalse(migration.strict_spotify(self.row, {**self.track, **changes}))

    def test_missing_duration_never_matches(self):
        self.assertFalse(migration.strict_spotify({**self.row, 'duration':None}, self.track))

    def test_featured_credit_requires_all_artists_and_no_conflict(self):
        row = {'title':'Tìm Em', 'artists':'Hngle, Bảo Anh', 'isrc':'VNA0R2601458'}
        track = {'name':'Tìm Em (feat. Bảo Anh)', 'artists':[{'name':'Hngle'},{'name':'Bảo Anh'}],
                 'duration_ms':274590, 'isrc':'VNA0R2601458'}
        self.assertTrue(migration.normalized_spotify(row, track, 274.59, 'unknown'))
        self.assertFalse(migration.normalized_spotify(row, track, 274.59, 'conflict'))
        self.assertFalse(migration.normalized_spotify(row, {**track, 'artists':[{'name':'Hngle'}]}, 274.59, 'unknown'))
        self.assertFalse(migration.normalized_spotify(row, {**track, 'name':'Tìm Em (Remix)'}, 274.59, 'unknown'))
        self.assertFalse(migration.normalized_spotify(row, {**track, 'isrc':'DIFFERENT'}, 274.59, 'unknown'))

    def test_genre_mapping_uses_genres_not_country(self):
        self.assertEqual(migration.genres_from_evidence([{'raw':'Việt Nam,V-Pop,R&B Việt'}, {'raw':'Pop'}]), ['V-Pop', 'R&B', 'Pop'])
        self.assertEqual(migration.genres_from_evidence([{'raw':'Việt Nam'}]), [])

    def test_acoustic_binding_requires_three_current_samples_and_same_isrc(self):
        pilot = {'title': self.row['title'], 'artists': ['B Ray', 'V#'], 'duration_seconds': 265.95,
                 'spotify': {'matched': True, 'isrc': 'WRONG', 'duration_seconds': 265.95}}
        revision = [pilot['title'], pilot['artists'], pilot['duration_seconds'], True, 'WRONG', 265.95]
        digest = hashlib.sha256(json.dumps(revision, ensure_ascii=False).encode()).hexdigest()
        samples = [{'recognized': True, 'key': 'one', 'isrc': 'QMDA62505555', 'start_seconds': t,
                    'offset': t, 'timeskew': 0, 'frequencyskew': 0} for t in (30, 120, 220)]
        pilot['audio'] = {'recognition': {'target_revision': digest, 'samples': samples}}
        self.assertEqual(migration.acoustic_spotify(self.row, pilot, [self.track], 265.95)['id'], 'real')
        bad = {**self.track, 'isrc':'DIFFERENT'}
        self.assertIsNone(migration.acoustic_spotify(self.row, pilot, [bad], 265.95))
        samples[1]['isrc'] = 'DIFFERENT'
        self.assertIsNone(migration.acoustic_spotify(self.row, pilot, [self.track], 265.95))

    def test_apple_preview_requires_current_evidence_and_matching_recording(self):
        pilot = {'title': self.row['title'], 'artists': ['B Ray', 'V#'], 'duration_seconds': 265.95,
                 'spotify': {'matched': True, 'isrc': 'id', 'duration_seconds': 265.95}}
        revision = [pilot['title'], pilot['artists'], pilot['duration_seconds'], True, 'id', 265.95]
        evidence = {'target_revision': hashlib.sha256(json.dumps(revision, ensure_ascii=False).encode()).hexdigest(),
                    'independent_preview_reference': {'matched': True, 'reference_sha256': 'hash',
                    'catalog': {'provider':'apple_itunes', 'track_id': 12}}}
        pilot['audio'] = {'recognition': evidence}
        entry = {'trackId':12, 'trackName':'Vùng An Toàn', 'artistName':'B Ray', 'trackTimeMillis':265950}
        self.assertEqual(migration.apple_preview_metadata(self.row, pilot, {'12':[entry]}, 265.95), entry)
        evidence['target_revision'] = 'stale'
        self.assertIsNone(migration.apple_preview_metadata(self.row, pilot, {'12':[entry]}, 265.95))

    def test_fresh_preview_requires_audio_and_all_artists(self):
        row = {**self.row, 'artists':'B Ray'}
        entry = {'trackId':12, 'trackName':'Vùng An Toàn', 'artistName':'B Ray', 'trackTimeMillis':265950}
        evidence = {'matched':True, 'reference_sha256':'a'*64, 'waveform_correlation':.96,
                    'reference_coverage':.9, 'worst_window':.9,
                    'catalog':{'provider':'apple_itunes','track_id':12}}
        self.assertEqual(migration.fresh_apple_preview_metadata(row,evidence,{'12':[entry]},265.95),entry)
        self.assertIsNone(migration.fresh_apple_preview_metadata(self.row,evidence,{'12':[entry]},265.95))
        evidence['waveform_correlation'] = .2
        self.assertIsNone(migration.fresh_apple_preview_metadata(row,evidence,{'12':[entry]},265.95))

if __name__ == '__main__':
    unittest.main()
