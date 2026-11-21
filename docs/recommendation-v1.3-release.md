# Recommendation v1.3 operational handoff

Disposition and evidence: `../../tasks/recommendation-v1.3-progress.md`. Production remains WAITING_FOR_LICENSED_CATALOG.

Smoke must use `supporting-services/recommendation-worker/.env.smoke.example`, dedicated loopback ports 13316/13318, `rec_fixture`, server_id 773313 and fixture-prefix-only grants. Application `.env` is forbidden. Build first; the harness copies the JAR to a per-run path so another build cannot replace the running artifact. Run Maven verify, worker pytest, then integration_smoke.py. The new GitHub workflow gates the existing deployment job; cloud CI has not been executed here.

Use separate FREE acceptance accounts (browser 2, Android 1), because sharing one FREE account legitimately revokes a device at its device limit. `prepare_browser_session.py` uses the real login endpoint. Match shared test audio duration with `prepare_acceptance_fixture.py --duration-ms`.

Freeze and review a clean release commit and immutable image digests before production. Record dependency locks and artifact hashes. Current dirty-workspace JAR/APK are staging-only; Android APK uses the existing internal signing configuration, not an approved production signing release.

Worker: use dedicated SQL read-only and Mongo recommendation-only credentials with compose.worker.yaml, persistent /state, restart policy and healthcheck. Its 15-minute content-profile refresh does not renew the training age of an old CF model. Backend requires accepted evaluation, behavior-v1 schema, <=45-minute refreshed profile and <=3-day model. CF and campaigns default off.

Rollout: begin with RECOMMENDATIONS_ENABLED=false, RECOMMENDATIONS_COLLABORATIVE_ENABLED=false, RECOMMENDATIONS_CAMPAIGNS_ENABLED=false. Verify authenticated admin readiness/catalog-review/operations. Complete actual moderation and licenses through existing administration. Set RECOMMENDATIONS_PILOT_USER_IDS to approved ACTIVE IDs before enabling generation. Empty allowlist means all ACTIVE accounts; allowlist changes need restart. Observe >=24 hours before general opening.

`monitor_recommendations.py` consumes RECOMMENDATION_MONITOR_TOKEN from the environment and a worker heartbeat file, and atomically writes bounded-name Prometheus textfile metrics. Do not put tokens, mood text or user IDs into metric labels. External collector/scheduler and alert routing are not deployed yet. Alert on scrape failure, zero eligible catalog, heartbeat age >30 minutes, stale profiles, errors/latency, queue failures and dropped outcomes. Runtime counters reset on restart.

Rollback: revision-checked admin controls disable generation, CF and campaigns. Keep strict rights checks on existing mixes/queues. Client entry hiding currently needs a compatible client build/configuration change. Never deploy an older rights-bypass image. Restore data only from per-document immutable journals with version/hash CAS; never restore a whole database or overwrite audio/lyrics.
