"""Bounded synthetic listening journeys through the live Moodify API."""

import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import random
import re
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid
from zoneinfo import ZoneInfo


API = "http://moodify-backend:8080/api/"
ZONE = ZoneInfo("Asia/Ho_Chi_Minh")
SLOTS = (8, 12, 19)
BATCH = "rec-seed-auto-20261107-v1"
DOMAIN = "@recommendation-seed.invalid"
PERSONAS = (
    ("rec_seed_auto_minh_an", "Nguyễn Minh An", "0000001101"),
    ("rec_seed_auto_khanh_linh", "Trần Khánh Linh", "0000001102"),
    ("rec_seed_auto_hoang_phuc", "Lê Hoàng Phúc", "0000001103"),
)
AUDIO_BYTES = 3 * 1024 * 1024
SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2


class StopRun(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = build_opener(ProxyHandler({}), NoRedirect())


def request(path, method="GET", body=None, token=None, device=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if device:
        headers["X-Device-Uuid"] = device
    data = json.dumps(body).encode() if body is not None else None
    with HTTP.open(Request(API + path, data, headers, method=method), timeout=8) as response:
        raw = response.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise StopRun("oversized_api_response")
    return json.loads(raw) if raw else None


def write_private(path, value):
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
            temporary.replace(path)
            directory = os.open(path.parent, os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)


def audit(state, event, **fields):
    entry = {"at": datetime.now(timezone.utc).isoformat(), "batch": BATCH, "synthetic": True, "event": event, **fields}
    with (state / "events.jsonl").open("a", encoding="utf-8") as output:
        output.write(json.dumps(entry, ensure_ascii=False) + "\n")
        output.flush()
        os.fsync(output.fileno())
    print(json.dumps(entry, ensure_ascii=False), flush=True)


def login(account):
    device = account["device"]
    auth = request("auth/login", "POST", {
        "identifier": account["username"], "password": account["password"],
        "deviceUuid": device, "deviceName": "Moodify Seed Automation", "platform": "WEB",
    })
    if (auth["username"], auth["email"], auth["fullName"], auth["role"], auth["status"]) != (
        account["username"], account["username"] + DOMAIN, account["full_name"], "USER", "ACTIVE"
    ):
        raise StopRun("identity_mismatch")
    if account.get("user_id") is not None and auth["userId"] != account["user_id"]:
        raise StopRun("account_id_mismatch")
    token = auth["accessToken"]
    profile = request("auth/me", token=token, device=device)
    if (profile["id"], profile["username"], profile["email"], profile["role"], profile["status"]) != (
        auth["userId"], account["username"], account["username"] + DOMAIN, "USER", "ACTIVE"
    ):
        raise StopRun("profile_mismatch")
    if request("auth/devices/ping", token=token, device=device).get("active") is not True:
        raise StopRun("device_inactive")
    return auth["userId"], token


def provision(state):
    path = state / "accounts.private.json"
    if path.exists():
        accounts = json.loads(path.read_text(encoding="utf-8"))
    else:
        accounts = [{"username": user, "full_name": f"{name} [SEED]", "phone": phone,
                     "password": secrets.token_urlsafe(24), "device": str(uuid.uuid4())}
                    for user, name, phone in PERSONAS]
        write_private(path, accounts)
    validate_accounts(accounts)
    for account in accounts:
        if account.get("user_id") is None:
            try:
                request("auth/register", "POST", {
                    "username": account["username"], "email": account["username"] + DOMAIN,
                    "fullName": account["full_name"], "phone": account["phone"], "role": "USER",
                    "password": account["password"], "confirmPassword": account["password"],
                })
            except HTTPError as exc:
                if exc.code != 409:
                    raise
            # A lost registration response can be recovered only with the original password.
            account["user_id"], _ = login(account)
            write_private(path, accounts)
            audit(state, "ACCOUNT_READY", user_id=account["user_id"], username=account["username"], full_name=account["full_name"])


def validate_accounts(accounts):
    expected = [(user, f"{name} [SEED]", phone) for user, name, phone in PERSONAS]
    actual = [(a["username"], a["full_name"], a["phone"]) for a in accounts]
    if actual != expected:
        raise StopRun("cohort_allowlist_mismatch")


def audio_excerpt(track_id, seconds):
    url = API + "tracks/" + quote(track_id, safe="") + "/stream"
    for _ in range(3):
        try:
            with HTTP.open(Request(url, headers={"Range": f"bytes=0-{AUDIO_BYTES - 1}"}), timeout=8) as response:
                if response.status not in (200, 206) or not response.headers.get_content_type().startswith("audio/"):
                    raise StopRun("invalid_audio_response")
                source = response.read(AUDIO_BYTES)
            break
        except HTTPError as exc:
            with exc:
                target = urlparse(exc.headers.get("Location", ""))
            if (exc.code not in (301, 302, 303, 307, 308) or target.scheme != "https"
                    or target.netloc not in {"moodify.click", "api.moodify.click"}):
                raise StopRun("audio_unavailable") from None
            url = target.geturl()
    else:
        raise StopRun("audio_redirect_limit")
    decoded = subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "1", "-i", "pipe:0",
        "-map", "0:a:0", "-t", str(seconds), "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "s16le", "pipe:1",
    ], input=source, capture_output=True, timeout=15, check=True).stdout
    if len(decoded) < seconds * BYTES_PER_SECOND:
        raise StopRun("insufficient_decoded_audio")
    return memoryview(decoded)


def choose_queue(home, recent, persona, rng, token, device):
    items = [item for section in home["sections"] for item in section["items"]
             if item.get("entityType") in {"TRACK", "PERSONALIZED_PLAYLIST", "PLAYLIST"} and item.get("attributionToken")]
    # Alternate between single tracks and lists while retaining the server's ordering within each list.
    preferred = ("TRACK", "PERSONALIZED_PLAYLIST", "PLAYLIST")[persona]
    rng.shuffle(items)
    items.sort(key=lambda item: item["entityType"] != preferred)
    for item in items[:3]:
        try:
            queue = request("playback/queues", "POST", {key: item[key] for key in
                            ("entityType", "entityId", "versionId", "attributionToken")}, token, device)
        except HTTPError as exc:
            if exc.code == 404:
                continue
            raise
        options = [entry for entry in queue["tracks"] if re.fullmatch(r"[A-Za-z0-9_-]{1,100}", entry["track"]["id"])
                   and entry["track"]["id"] not in recent and (entry["track"].get("durationMs") or 0) >= 65_000]
        if options:
            return queue, rng.choice(options[:4])
    raise StopRun("no_fresh_eligible_track")


def run(state, initial):
    now = datetime.now(ZONE)
    day = now.date().isoformat()
    slot = next((index for index, hour in enumerate(SLOTS) if now.hour == hour), None)
    if slot is None and initial:
        slot = next((index for index, hour in enumerate(SLOTS) if now.hour < hour), len(SLOTS) - 1)
    if slot is None:
        print("Outside the configured listening windows", flush=True)
        return
    journal = state / "events.jsonl"
    history = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines()] if journal.exists() else []
    if any(e["event"] == "QUALIFICATION_GUARD_TRIPPED" for e in history):
        raise StopRun("qualification_requires_reconciliation")
    pending = {e["session"] for e in history if e["event"] == "PLAY_INTENT"} - {
        e["session"] for e in history if e["event"] == "SESSION_CLOSED"
    }
    if pending:
        raise StopRun("previous_session_requires_reconciliation")
    if any(e["event"] == "SLOT_RESERVED" and e["day"] == day and e["slot"] == slot for e in history):
        print("Daily slot already used", flush=True)
        return
    persona = (now.date().toordinal() + slot) % len(PERSONAS)
    accounts = json.loads((state / "accounts.private.json").read_text(encoding="utf-8"))
    validate_accounts(accounts)
    account = accounts[persona]
    user_id = account.get("user_id")
    if not isinstance(user_id, int) or user_id <= 0:
        raise StopRun("account_not_provisioned")
    audit(state, "SLOT_RESERVED", day=day, slot=slot, user_id=user_id)
    _, token = login(account)
    device = account["device"]
    home = request("recommendations/home", token=token, device=device)
    recent = [e["track_id"] for e in history if e["event"] == "SESSION_CLOSED" and e["user_id"] == user_id][-4:]
    rng = random.Random(f"{day}:{slot}:{account['username']}")
    queue, entry = choose_queue(home, recent, persona, rng, token, device)
    track_id = entry["track"]["id"]
    seconds = rng.randint(*((18, 26), (30, 42), (22, 36))[persona])
    pcm = audio_excerpt(track_id, seconds)
    queue_token = entry["queueToken"]
    if request("playback/queues/validate", "POST", {"trackId": track_id, "queueToken": queue_token}, token, device).get("valid") is not True:
        raise StopRun("queue_invalid")
    session = str(uuid.uuid4())
    position = 0
    history_id = None

    def playback(event):
        nonlocal history_id
        result = request("analytics/listening-session", "POST", {
            "sessionKey": session, "trackId": track_id, "eventType": event, "positionMs": position,
            "source": "HOME" if queue["entityType"] == "TRACK" else "PLAYLIST",
            "deviceType": "OTHER", "deviceUuid": device, "queueToken": queue_token,
        }, token, device)
        if result.get("qualified") is True:
            audit(state, "QUALIFICATION_GUARD_TRIPPED", user_id=user_id, session=session, track_id=track_id)
            raise StopRun("unexpected_qualified_stream")
        if not result.get("success") or result.get("qualified") is not False:
            raise StopRun("invalid_playback_response")
        reported_id = result.get("historyId")
        if not isinstance(reported_id, int) or reported_id <= 0:
            raise StopRun("invalid_history_id")
        if history_id is not None and reported_id != history_id:
            raise StopRun("history_id_changed")
        history_id = reported_id
        audit(state, "PLAYBACK_EVENT", user_id=user_id, track_id=track_id, session=session,
              history_id=history_id, kind=event, position_ms=position)

    audit(state, "PLAY_INTENT", user_id=user_id, track_id=track_id, session=session, queue_id=queue["queueId"],
          entity_type=queue["entityType"], title=entry["track"].get("name"), planned_seconds=seconds)
    terminal = "PLAYBACK_STOPPED"
    try:
        playback("PLAY")
        # Consume decoded PCM at its sample rate. Report progress only after consumption.
        for offset in range(0, seconds * BYTES_PER_SECOND, BYTES_PER_SECOND):
            chunk = pcm[offset:offset + BYTES_PER_SECOND]
            time.sleep(len(chunk) / BYTES_PER_SECOND)
            position += len(chunk) * 1000 // BYTES_PER_SECOND
            if persona == 1 and position == 10_000:
                playback("PAUSE")
                time.sleep(rng.uniform(2, 4))
                playback("RESUME")
            elif position % 5000 == 0:
                playback("HEARTBEAT")
        if persona == 0:
            terminal = "SKIP_NEXT"
    finally:
        playback(terminal)
        audit(state, "SESSION_CLOSED", user_id=user_id, track_id=track_id, session=session,
              history_id=history_id, position_ms=position, qualified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("provision", "scheduled", "initial"))
    parser.add_argument("--state", type=Path, default=Path("/state"))
    args = parser.parse_args()
    os.umask(0o077)
    with (args.state / "runner.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("A seed run already owns the lock", flush=True)
            return
        try:
            if args.mode == "provision":
                provision(args.state)
            else:
                run(args.state, args.mode == "initial")
        except Exception as exc:
            reason = str(exc) if isinstance(exc, StopRun) else f"HTTP_{exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
            audit(args.state, "RUN_STOPPED", reason=reason)
            raise SystemExit(1) from None


if __name__ == "__main__":
    main()
