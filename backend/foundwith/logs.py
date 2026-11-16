"""One-line JSON logs that Cloud Logging understands (severity, trace correlation).
Phone numbers are never logged; requests are identified by internal user id only."""

from __future__ import annotations

import json
import logging
import sys

_EXTRA_FIELDS = ("event", "method", "route", "status", "latency_ms", "user")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "severity": record.levelname,
            "message": record.getMessage(),
            "logger": record.name,
        }
        for key in _EXTRA_FIELDS:
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        trace = getattr(record, "trace", None)
        if trace:
            payload["logging.googleapis.com/trace"] = trace
        if record.exc_info:
            payload["stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def setup_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    if any(isinstance(h.formatter, JsonFormatter) for h in root.handlers):
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root.handlers = [handler]
    root.setLevel(level)
