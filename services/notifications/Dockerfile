# Same Python version as the CI pipeline
FROM python:3.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OTEL_ENABLED=false \
    OTEL_SERVICE_NAME=notifications \
    OTEL_PYTHON_FLASK_EXCLUDED_URLS="[^/]/notifications(?!-),/health,/ready" \
    LOG_FORMAT=json

WORKDIR /srv

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app
COPY scripts ./scripts
COPY tests ./tests

# Don't run as root. data/ holds the SQLite notification log when no MySQL is configured.
RUN useradd --create-home --uid 10001 appuser && mkdir -p /srv/data && chown appuser /srv/data
USER appuser

EXPOSE 5000

# With OTEL_ENABLED=true the service is auto-instrumented (Flask, pika, requests, pymysql)
# and traces go to OTEL_EXPORTER_OTLP_ENDPOINT, e.g. Jaeger.
CMD ["sh", "-c", "if [ \"$OTEL_ENABLED\" = \"true\" ]; then exec opentelemetry-instrument python -m app.main; else exec python -m app.main; fi"]
