"""Logging: readable text locally, one JSON object per line in containers.

JSON logs can be searched and filtered by field (level, logger, trace_id) in
CloudWatch or any log tool. When OpenTelemetry tracing is on, every line
carries the trace_id and span_id of the request or message being handled, so
you can jump from a log line straight to its trace in Jaeger.
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

try:
    from opentelemetry import trace as _otel_trace
except ImportError:  # tracing libraries not installed
    _otel_trace = None

TEXT_FORMAT = "%(asctime)s %(levelname)s [%(name)s] %(message)s"


def current_trace_ids() -> dict[str, str]:
    if _otel_trace is None:
        return {}
    context = _otel_trace.get_current_span().get_span_context()
    if not context.is_valid:
        return {}
    return {"trace_id": format(context.trace_id, "032x"), "span_id": format(context.span_id, "016x")}


class JsonFormatter(logging.Formatter):
    def __init__(self, service: str = "notifications"):
        super().__init__()
        self.service = service

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "time": datetime.fromtimestamp(record.created, timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "service": self.service,
            "logger": record.name,
            "message": record.getMessage(),
            **current_trace_ids(),
        }
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False, default=str)


def setup_logging(level: str = "INFO", fmt: str = "text") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter() if fmt == "json" else logging.Formatter(TEXT_FORMAT))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
