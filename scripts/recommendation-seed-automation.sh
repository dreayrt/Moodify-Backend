#!/bin/sh
set -eu

mode=${1:-scheduled}
case "$mode" in
    provision|initial|scheduled|check) ;;
    *) exit 2 ;;
esac

base=/opt/moodify/recommendation-seed-automation
backend=sha256:0f8ee72808165597d96d35406c8b2a3729c6e0c04ec233f267410c2d44cfeb0c
worker=sha256:e1e5156d9a1c773072538b58269d7258c8351d68f499172cc845e2c394979f3d
runtime=sha256:86169063a66fdf18e39735653a1687d6f830865e1dc37e611a366c8028c2d38c

if [ "$(/usr/bin/docker inspect -f '{{.Image}} {{.State.Status}}' moodify-backend)" != "$backend running" ] ||
   [ "$(/usr/bin/docker inspect -f '{{.Image}} {{.State.Status}} {{.State.Health.Status}}' moodify-recommendation-worker)" != "$worker running healthy" ]; then
    echo 'Seed automation stopped because the reviewed backend or worker is unavailable' >&2
    exit 1
fi
if [ "$mode" = check ]; then
    exit 0
fi

exec /usr/bin/docker run --rm --name "moodify-seed-automation-$mode" --pull never \
    --entrypoint python3 --user 1000:1000 --network moodify_moodify-net \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m --memory 192m --cpus 0.35 \
    --pids-limit 64 --cap-drop ALL --security-opt no-new-privileges \
    --mount "type=bind,source=$base/runner.py,target=/opt/automation.py,readonly" \
    --mount "type=bind,source=$base/state,target=/state" \
    "$runtime" /opt/automation.py "$mode"
