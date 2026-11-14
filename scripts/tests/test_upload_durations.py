import importlib.util
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("durations", Path(__file__).parents[1] / "backfill_upload_durations.py")
durations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(durations)


class UploadDurationTest(unittest.TestCase):
    def test_audio_stream_wins_over_video_container(self):
        self.assertEqual(durations.parse_duration({"streams": [{"codec_type": "audio", "duration": "302.347"}],
                                                   "format": {"duration": "310"}}), 302347)

    def test_container_fallback(self):
        self.assertEqual(durations.parse_duration({"streams": [{"codec_type": "audio", "duration": "N/A"}],
                                                   "format": {"duration": "302.347"}}), 302347)

    def test_invalid_duration_cannot_be_written(self):
        for value in [None, 0, -1, "NaN", "Infinity", 2_147_484, True]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                durations.parse_duration({"streams": [{"codec_type": "audio", "duration": value}]})
        with self.assertRaises(ValueError):
            durations.parse_duration({"streams": [], "format": {"duration": 300}})

    def test_only_uploaded_audio_inside_root_is_used(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "audio").mkdir()
            track = root / "audio" / "song.mp3"
            track.write_bytes(b"fixture")
            self.assertEqual(durations.uploaded_audio_path("/uploads/audio/song.mp3", root), track.resolve())
            for path in ["https://example.com/song.mp3", "/uploads/audio/../../secret", "/uploads/covers/image.jpg",
                         "/uploads/audio/missing.mp3", "/uploads/audio/../covers/image.jpg"]:
                with self.subTest(path=path), self.assertRaises(ValueError):
                    durations.uploaded_audio_path(path, root)

    def test_updates_match_exact_original_fields_even_if_missing_or_null(self):
        before = {"_id": "id", "local_path": "/uploads/audio/song.mp3", "duration_ms": None}
        self.assertEqual(durations.original_state(before), {
            "_id": "id", "local_path": "/uploads/audio/song.mp3",
            "duration_ms": {"$eq": None, "$exists": True}, "duration_formatted": {"$exists": False},
        })

    def test_preview_does_not_write_and_apply_backs_up_before_conditional_update(self):
        for apply in (False, True):
            with self.subTest(apply=apply), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "audio").mkdir()
                (root / "audio" / "song.mp3").write_bytes(b"fixture")
                before = {"_id": "id", "name": "Bài hát", "local_path": "/uploads/audio/song.mp3"}
                client = MagicMock()
                collection = client.__enter__.return_value.__getitem__.return_value.__getitem__.return_value
                collection.find.return_value = [before]
                backup = root / "backup.jsonl"

                def write(query, update):
                    self.assertEqual(query, durations.original_state(before))
                    self.assertEqual(update, {"$set": {"duration_ms": 302347, "duration_formatted": "5:02"}})
                    saved = json.loads(backup.read_text(encoding="utf-8"))
                    self.assertEqual(saved["before"], before)
                    return MagicMock(modified_count=1)

                collection.update_one.side_effect = write
                args = ["backfill", "--database", "fixture", "--uploads-root", str(root)]
                if apply:
                    args += ["--apply", "--backup-file", str(backup)]
                with patch("sys.argv", args), patch.dict("os.environ", {"MONGODB_URI": "mongodb://localhost"}), \
                     patch("pymongo.MongoClient", return_value=client), \
                     patch.object(durations, "measure_duration", return_value=302347), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(durations.main(), 0)
                self.assertEqual(collection.update_one.call_count, int(apply))
                self.assertEqual(backup.exists(), apply)


if __name__ == "__main__":
    unittest.main()
