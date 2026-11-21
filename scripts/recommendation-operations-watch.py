"""Record operational alert transitions and deliver them to a configured HTTPS receiver."""

from datetime import datetime, time, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

ROOT = Path("/opt/moodify/recommendation-operations")
SEED = Path("/opt/moodify/recommendation-seed-automation")
LOCAL = ZoneInfo("Asia/Ho_Chi_Minh")
SEED_HOURS = (8, 12, 19)


def command(*args):
    return subprocess.check_output(args, text=True, timeout=12, stderr=subprocess.DEVNULL).strip()


def collect(now, since):
    alerts = set()
    try:
        status = json.loads(command("docker", "exec", "moodify-recommendation-monitor", "cat", "/monitor/status.json"))
        alerts.update(code for code in status["alerts"] if re.fullmatch(r"[A-Z_]{1,60}", code))
        if now - datetime.fromisoformat(status["at"]) > timedelta(minutes=3):
            alerts.add("MONITOR_STALE")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        alerts.add("MONITOR_UNAVAILABLE")

    for timer in ("moodify-recommendation-seed", "moodify-catalog-features"):
        try:
            command("systemctl", "is-active", timer + ".timer")
        except (OSError, subprocess.SubprocessError):
            alerts.add(timer.removeprefix("moodify-").replace("-", "_").upper() + "_TIMER_INACTIVE")

    try:
        command(str(SEED / "run.sh"), "check")
    except (OSError, subprocess.SubprocessError):
        alerts.add("SEED_RELEASE_GUARD_BLOCKED")
    try:
        rows = [json.loads(line) for line in (SEED / "state/events.jsonl").read_text(encoding="utf-8").splitlines()]
        closed = {row["session"] for row in rows if row["event"] == "SESSION_CLOSED"}
        if any(row["event"] == "PLAY_INTENT" and row["session"] not in closed
               and now - datetime.fromisoformat(row["at"]) > timedelta(minutes=3) for row in rows):
            alerts.add("SEED_SESSION_UNRESOLVED")
        if any(row["event"] == "QUALIFICATION_GUARD_TRIPPED" for row in rows):
            alerts.add("SEED_QUALIFICATION_GUARD_TRIPPED")
        day = now.astimezone(LOCAL).date()
        windows = [(datetime.combine(date, time(hour), LOCAL), slot) for date in (day-timedelta(days=1), day)
                   for slot, hour in enumerate(SEED_HOURS)]
        due = [(start, slot) for start, slot in windows if since <= start <= now-timedelta(minutes=45)]
        if due:
            start, slot = due[-1]
            reserved = next((row for row in rows if row["event"] == "SLOT_RESERVED"
                             and row["day"] == start.date().isoformat() and row["slot"] == slot), None)
            if not reserved or not any(row["event"] == "SESSION_CLOSED"
                                       and row["user_id"] == reserved["user_id"]
                                       and reserved["at"] <= row["at"]
                                       and datetime.fromisoformat(row["at"]) <= start+timedelta(minutes=45)
                                       for row in rows):
                alerts.add("SEED_WINDOW_MISSED")
    except (OSError, ValueError, KeyError):
        alerts.add("SEED_JOURNAL_UNAVAILABLE")

    try:
        features = json.loads((ROOT / "features/status.json").read_text(encoding="utf-8"))
        if now - datetime.fromisoformat(features["at"]) > timedelta(minutes=75):
            alerts.add("CATALOG_FEATURE_SCAN_STALE")
        if features["missing_hash"] or features["stale_features_review"]:
            alerts.add("CATALOG_FEATURES_REQUIRE_REVIEW")
        if features["states"].get("retry_wait"):
            alerts.add("CATALOG_FEATURE_RETRY_PENDING")
    except (OSError, ValueError, KeyError):
        if now - since > timedelta(minutes=75):
            alerts.add("CATALOG_FEATURE_SCAN_UNAVAILABLE")
    return sorted(alerts)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def deliver(endpoint, message):
    target = urlsplit(endpoint)
    if target.scheme != "https" or not target.hostname or target.username or target.password:
        raise ValueError("invalid_receiver")
    request = Request(endpoint, json.dumps(message).encode("utf-8"), {
        "Content-Type": "application/json",
    }, method="POST")
    with build_opener(NoRedirect()).open(request, timeout=8) as response:
        if not 200 <= response.status < 300:
            raise ValueError("receiver_rejected")


def save(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state), encoding="utf-8")
    temporary.replace(path)


def main():
    now = datetime.now(timezone.utc)
    path = ROOT / "watch/status.json"
    state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {
        "since": now.isoformat(), "alerts": [], "delivered": [],
    }
    alerts = collect(now, datetime.fromisoformat(state["since"]))
    state["at"] = now.isoformat()
    if alerts != state["alerts"]:
        event = {"at": state["at"], "alerts": alerts, "resolved": sorted(set(state["alerts"])-set(alerts))}
        with (ROOT / "watch/transitions.jsonl").open("a", encoding="utf-8") as journal:
            journal.write(json.dumps(event)+"\n")
        print(json.dumps(event), flush=True)
    state["alerts"] = alerts
    save(path, state)
    endpoint = os.environ.get("MOODIFY_ALERT_RECEIVER", "")
    if endpoint and alerts != state["delivered"]:
        try:
            deliver(endpoint, {"service": "moodify-recommendation", "at": state["at"], "alerts": alerts,
                               "resolved": sorted(set(state["delivered"])-set(alerts))})
        except Exception:
            raise SystemExit("Alert receiver unavailable, delivery will be retried") from None
        state["delivered"] = alerts
        save(path, state)


if __name__ == "__main__":
    main()
