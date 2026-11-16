# Found With API, migration job and maintenance job all ship in this one image.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY foundwith ./foundwith

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin app
USER 10001

EXPOSE 8080
# One process per instance; Cloud Run scales instances. Access logs come from the app (JSON, numbers masked).
CMD ["sh", "-c", "exec uvicorn foundwith.api:create_app --factory --host 0.0.0.0 --port ${PORT:-8080} --no-access-log --proxy-headers --forwarded-allow-ips='*' --timeout-graceful-shutdown 8"]
