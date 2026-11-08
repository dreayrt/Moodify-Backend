#!/usr/bin/env python3
"""Expose the Spring room WebSocket through the production API Nginx route."""

from __future__ import annotations

import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


API_HOST = re.compile(
    r"(?m)^[ \t]*server_name[ \t]+[^;]*\bapi\.moodify\.click\b[^;]*;",
    re.IGNORECASE,
)
API_PROXY = re.compile(
    r"(?is)location[ \t]+(?:\^~[ \t]+)?/api(?:/|[ \t]*\{)[^{}]*\{[^{}]*"
    r"proxy_pass[ \t]+http://(?:127\.0\.0\.1|localhost):8080(?:/[^;]*)?;"
)
HTTP_REDIRECT = re.compile(r"(?m)^[ \t]*return[ \t]+30[128](?:[ \t]|;)", re.IGNORECASE)
ROOM_ROUTE = "/api/ws/karaoke"
LOCATION = f'''location = {ROOM_ROUTE} {{
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;
}}'''


def format_location(indent: str) -> str:
    return "\n".join(indent + line if line else "" for line in LOCATION.splitlines())


def find_block_end(text: str, opening: int) -> int:
    depth = 0
    quote: str | None = None
    escaped = False
    comment = False

    for index in range(opening, len(text)):
        char = text[index]
        if comment:
            if char == "\n":
                comment = False
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char == "#":
            comment = True
        elif char in ("'", '"'):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index

    raise ValueError("Unclosed Nginx server block")


def enabled_config_files() -> list[Path]:
    result = subprocess.run(
        ["nginx", "-T"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    candidates = {
        Path(match.group(1)).resolve()
        for match in re.finditer(
            r"(?m)^# configuration file (.+):$", result.stdout
        )
        if Path(match.group(1)).is_file()
    }
    if not candidates:
        raise RuntimeError("nginx -T returned no active configuration files")
    print(f"[karaoke-ws] Found {len(candidates)} active Nginx config files.", flush=True)
    return sorted(candidates)


def update_file(path: Path) -> tuple[str, bool]:
    source = path.read_text(encoding="utf-8")
    updates: list[tuple[int, int, str]] = []
    search_from = 0

    while True:
        match = re.search(r"(?m)^[ \t]*server[ \t]*\{", source[search_from:])
        if not match:
            break
        start = search_from + match.start()
        opening = search_from + match.end() - 1
        end = find_block_end(source, opening)
        block = source[start : end + 1]
        search_from = end + 1

        host_match = API_HOST.search(block)
        proxy_match = API_PROXY.search(block)
        if not host_match and not proxy_match:
            continue
        # api.moodify.click also has an HTTP-only server block that redirects
        # all requests to HTTPS. Keep the WebSocket location in the proxying
        # server block instead of adding it to that redirect block.
        if host_match and not proxy_match and HTTP_REDIRECT.search(block):
            continue

        route_match = re.search(
            rf"(?m)^[ \t]*location[ \t]*=[ \t]*{re.escape(ROOM_ROUTE)}[ \t]*\{{",
            block,
        )
        if route_match:
            route_opening = route_match.end() - 1
            route_end = find_block_end(block, route_opening)
            existing_location = block[route_match.start() : route_end + 1]
            required = (
                "proxy_pass http://127.0.0.1:8080;",
                "proxy_set_header Upgrade $http_upgrade;",
                'proxy_set_header Connection "upgrade";',
                "proxy_http_version 1.1;",
            )
            if all(directive in existing_location for directive in required):
                continue

            line_start = block.rfind("\n", 0, route_match.start()) + 1
            route_indent = re.match(r"[ \t]*", block[line_start:]).group(0)  # type: ignore[union-attr]
            new_block = (
                block[:line_start]
                + format_location(route_indent)
                + block[route_end + 1 :]
            )
            updates.append((start, end + 1, new_block))
            continue

        indent_source = host_match.start() if host_match else proxy_match.start()  # type: ignore[union-attr]
        line_start = block.rfind("\n", 0, indent_source) + 1
        indent = re.match(r"[ \t]*", block[line_start:]).group(0)  # type: ignore[union-attr]
        closing_line_start = block.rfind("\n", 0, len(block) - 1) + 1
        closing_indent = re.match(r"[ \t]*", block[closing_line_start:-1]).group(0)  # type: ignore[union-attr]
        indented_location = format_location(indent)
        new_block = block[: -1].rstrip() + "\n\n" + indented_location + "\n" + closing_indent + "}"
        updates.append((start, end + 1, new_block))

    result = source
    for start, end, replacement in reversed(updates):
        result = result[:start] + replacement + result[end:]
    return result, bool(updates)


def write_atomically(path: Path, content: str) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
        os.chmod(temporary, stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def run(*command: str) -> None:
    subprocess.run(command, check=True)


def main() -> None:
    changes: dict[Path, tuple[str, str]] = {}
    matched_servers = 0
    for path in enabled_config_files():
        content, changed = update_file(path)
        if API_HOST.search(content) or API_PROXY.search(content):
            matched_servers += 1
        if changed:
            changes[path] = (path.read_text(encoding="utf-8"), content)

    if matched_servers == 0:
        raise RuntimeError(
            "No active Nginx server block proxies api.moodify.click to the Moodify API"
        )
    if not changes:
        print(f"Nginx already proxies {ROOM_ROUTE} with WebSocket upgrade headers.")
        return

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backups: dict[Path, Path] = {}
    try:
        for path, (original, updated) in changes.items():
            backup = path.with_name(f"{path.name}.backup-{stamp}")
            shutil.copy2(path, backup)
            backups[path] = backup
            write_atomically(path, updated)
        print("[karaoke-ws] Validating Nginx configuration...", flush=True)
        run("nginx", "-t")
        print("[karaoke-ws] Reloading Nginx...", flush=True)
        run("systemctl", "reload", "nginx")
    except Exception:
        for path, backup in backups.items():
            shutil.copy2(backup, path)
        subprocess.run(["nginx", "-t"], check=False)
        subprocess.run(["systemctl", "reload", "nginx"], check=False)
        raise

    for path in changes:
        print(f"Enabled WebSocket upgrade for {ROOM_ROUTE} in {path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(
            f"[karaoke-ws] ERROR: {type(error).__name__}: {error}",
            file=sys.stderr,
            flush=True,
        )
        raise
