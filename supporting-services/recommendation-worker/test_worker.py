from datetime import datetime, timedelta, timezone
import numpy as np
import pytest
from worker import aggregate_daily, canonical_tracks, interaction_matrix, fit_implicit_als, evaluate, ndcg_at_k, sql_statements, effective_licensed_tracks, normalize_top_scores

UTC = timezone.utc
NOW = datetime(2026, 10, 27, 12, tzinfo=UTC)


def test_alias_collision_is_rejected():
    with pytest.raises(ValueError):
        canonical_tracks([{"_id": "a", "spotify_id": "same"}, {"_id": "b", "spotify_id": "same"}])


def test_internal_id_priority_does_not_depend_on_catalog_iteration_order():
    rows = [{"_id":"a","spotify_id":"b"},{"_id":"b","spotify_id":"external-b"}]
    assert canonical_tracks(rows) == canonical_tracks(list(reversed(rows)))
    assert canonical_tracks(rows)["b"] == "b"


def test_library_and_history_resolve_to_same_canonical_daily_pair():
    history = [{"user_id": 1, "track_id": "external", "started_at": NOW - timedelta(hours=1),
                "listened_duration_ms": 60_000, "is_revenue": True}]
    library = [{"user_id": 1, "track_id": "canonical", "added_at": NOW - timedelta(hours=1)}]
    daily = aggregate_daily(history, library, {"external": "canonical", "canonical": "canonical"}, NOW)
    assert len(daily) == 1
    bucket = next(iter(daily.values()))
    assert bucket["engaged"] == 1 and bucket["saves"] == 1


def test_repeated_play_contributions_are_capped():
    row = {"user_id": 1, "track_id": "t", "started_at": NOW - timedelta(hours=1),
           "listened_duration_ms": 100_000, "is_revenue": True}
    bucket = next(iter(aggregate_daily([row] * 100, [], {"t": "t"}, NOW).values()))
    assert bucket["engaged"] == 5 and bucket["valid_ms"] <= 3_600_000


def test_training_cutoff_never_includes_holdout_day_or_future_track():
    cutoff = NOW - timedelta(days=7)
    day = cutoff.date()
    daily = {(1,"old",day-timedelta(days=1)): {"engaged": 2, "saves": 0},
             (1,"future",day): {"engaged": 5, "saves": 1}}
    matrix, users, tracks = interaction_matrix(daily, cutoff)
    assert users == [1] and tracks == ["old"] and matrix.shape == (1, 1)


def test_als_is_reproducible_and_rejects_signed_confidence():
    matrix = np.array([[3., 2., 0.], [0., 2., 4.], [1., 0., 5.]])
    first = fit_implicit_als(matrix, factors=2, iterations=3)
    second = fit_implicit_als(matrix, factors=2, iterations=3)
    np.testing.assert_allclose(first[0], second[0])
    assert np.all(np.isfinite(first[0] @ first[1].T))
    with pytest.raises(ValueError):
        fit_implicit_als(np.array([[-1., 2.]]))


def test_sparse_data_never_publishes_unvalidated_learned_model():
    evidence = evaluate({}, NOW)
    assert evidence["accepted"] is False and evidence["evaluation_users"] == 0


def test_ndcg_rewards_earlier_relevant_results():
    assert ndcg_at_k([1, 2, 3], {1}) == 1.0
    assert ndcg_at_k([1, 2, 3], {3}) < 1.0


def test_sql_split_preserves_semicolons_in_quoted_comments():
    statements = sql_statements("-- schema\nCREATE TABLE x(a VARCHAR(30) COMMENT 'one; two'); INSERT INTO x VALUES ('it''s;ok');")
    assert len(statements) == 2
    assert "one; two" in statements[0] and "it''s;ok" in statements[1]


def test_canonical_revocation_overrides_external_id_license_in_readiness_audit():
    aliases = {"internal":"internal","external":"internal"}
    active = {"id":1,"track_id":"external","status":"ACTIVE"}
    revoked = {"id":2,"track_id":"internal","status":"REVOKED"}
    assert effective_licensed_tracks([active], aliases, NOW.date()) == {"internal"}
    assert effective_licensed_tracks([active,revoked], aliases, NOW.date()) == set()


def test_serving_profile_is_bounded_and_keeps_highest_scores():
    scores = normalize_top_scores(np.arange(600, dtype=float), [str(i) for i in range(600)])
    assert len(scores) == 500 and scores["599"] == 1.0 and "0" not in scores
    with pytest.raises(ValueError, match="non-finite"):
        normalize_top_scores(np.array([float("nan")]), ["bad"])
