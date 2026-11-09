FROM python:3.12.12-slim
WORKDIR /app/supporting-services/recommendation-worker
COPY supporting-services/recommendation-worker/requirements.lock ./requirements.lock
RUN pip install --no-cache-dir -r requirements.lock
COPY supporting-services/recommendation-worker/*.py ./
RUN useradd --uid 10001 --create-home recommender && mkdir /state && chown recommender /state
USER recommender
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s CMD python -c "import json,time,datetime; h=json.load(open('/state/heartbeat.json')); assert h['status']=='ready' and time.time()-datetime.datetime.fromisoformat(h['at']).timestamp()<1800"
CMD ["python", "worker_service.py", "--env", "/run/secrets/recommendation-worker.env", "--state", "/state"]
