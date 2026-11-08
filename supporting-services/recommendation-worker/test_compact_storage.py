from datetime import date, datetime, timedelta, timezone
from bson import json_util
import pytest
from compact_storage import documents, encode_dates, fingerprint, migrate


NOW = datetime(2026, 10, 27, tzinfo=timezone.utc)


def test_backup_preserves_sql_dates_and_bson_datetimes():
    source = {"sql": {"user_track_daily": {"ddl": "fixture", "rows": [{"day": date(2026, 10, 26), "at": NOW}]}}, "mongo": {}}
    restored = json_util.loads(json_util.dumps(encode_dates(source)))
    assert restored["sql"]["user_track_daily"]["rows"][0]["day"] == {"$sqlDate": "2026-10-26"}
    assert isinstance(restored["sql"]["user_track_daily"]["rows"][0]["at"], datetime)
    assert fingerprint(source) == fingerprint(restored)


def test_only_superseded_mix_versions_expire():
    source = {"sql": {}, "mongo": {
        "personalized_playlists": [{"_id": "mix", "user_id": 1, "active_version_id": "v2"}],
        "personalized_playlist_versions": [{"_id": "v1", "user_id": 1}, {"_id": "v2", "user_id": 1}]}}
    state, events = documents(source, NOW)
    by_id = {d["_id"]: d for d in state}
    assert by_id["mix"]["kind"] == "MIX" and not events
    assert by_id["v1"]["expires_at"] == NOW + timedelta(days=7)
    assert "expires_at" not in by_id["v2"]


def test_log_retention_does_not_reimport_expired_requests():
    def row(key, created):
        return {"request_id": key, "created_at": created, "user_id": 1,
                "model_version": "baseline", "policy_version": "v1"}
    source = {"sql": {"recommendation_requests": {"rows": [row("old", NOW-timedelta(days=8)), row("new", NOW)]}}, "mongo": {}}
    _, events = documents(source, NOW)
    assert [d["request_id"] for d in events] == ["new"]


def test_cleanup_cannot_run_without_backup_apply():
    with pytest.raises(ValueError, match="verified backup"):
        migrate(None, None, remove_legacy=True)
