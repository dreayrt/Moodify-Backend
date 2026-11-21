#!/bin/sh
set -eu

base=/opt/moodify/recommendation-operations
runtime=sha256:e1e5156d9a1c773072538b58269d7258c8351d68f499172cc845e2c394979f3d
analyzer=moodify-recommendation-audio-analysis
reviewed_analyzer="$(cat "$base/analyzer-image")"
if [ "$(/usr/bin/docker inspect -f '{{.Image}} {{.State.Status}} {{.State.Health.Status}}' "$analyzer")" != "$reviewed_analyzer running healthy" ]; then
    echo 'Reviewed audio analyzer is unavailable' >&2
    exit 1
fi

exec /usr/bin/docker run --rm --name moodify-catalog-features-run --pull never \
    --entrypoint python --user 1000:1000 --network moodify_moodify-net \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m --memory 320m --cpus 0.35 \
    --pids-limit 64 --cap-drop ALL --security-opt no-new-privileges \
    --mount "type=bind,source=$base/catalog_maintenance.py,target=/app/supporting-services/recommendation-worker/catalog_maintenance.py,readonly" \
    --mount "type=bind,source=$base/features,target=/state" \
    "$runtime" catalog_maintenance.py features --output /state --max-jobs 2 \
    --mongo-uri mongodb://moodify-mongo:27017/music_streaming \
    --analyzer "http://$analyzer:8000" --analyzer-version essentia-descriptors-v2
