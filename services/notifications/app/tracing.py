"""Business details on trace spans, so Jaeger can search by email type, id, outcome and severity.

The spans themselves come from OpenTelemetry's automatic instrumentation (pika,
Flask, requests, PyMySQL) when the service runs with OTEL_ENABLED=true. This
module only adds attributes to the span that is already active. Without
tracing (or without OpenTelemetry installed) every call here does nothing.

Severity levels, shared with the logs and alerts:
  info      sent on the first attempt, or a duplicate that was skipped
  warning   an attempt failed and a retry is queued, or it was sent only after a retry
  error     discarded: the email will never go out (also marks the span as failed)
  critical  discarded because Resend rejected the API key: every email is failing

Never put a full email address in a span: traces are visible to the whole team.
"""
from __future__ import annotations

import re
from typing import Any

try:
    from opentelemetry import trace
    from opentelemetry.trace import Status, StatusCode
except ImportError:  # tracing libraries not installed
    trace = None

INFO, WARNING, ERROR, CRITICAL = "info", "warning", "error", "critical"
SENT, DUPLICATE, RETRYING, DISCARDED = "sent", "duplicate", "retrying", "discarded"

_EMAIL = re.compile(r"[^\s@'\"<>(),;:]+@([^\s@'\"<>(),;:]+)")


def mask_emails(text: str) -> str:
    """'... (alex@example.com)' -> '... (***@example.com)'."""
    return _EMAIL.sub(lambda m: "***@" + m.group(1), text)


def severity(outcome: str, attempt: int, critical: bool = False) -> str:
    if outcome == DISCARDED:
        return CRITICAL if critical else ERROR
    if outcome == RETRYING or (outcome == SENT and attempt > 1):
        return WARNING
    return INFO


def current_span() -> Any:
    return trace.get_current_span() if trace is not None else None


def annotate(decision, span: Any = None) -> None:
    """Describe what happened to one RabbitMQ message on its span."""
    span = span if span is not None else current_span()
    if span is None or not span.is_recording():
        return
    outcome = decision.outcome
    span.set_attribute("notification.type", decision.routing_key.removeprefix("notify.") or "unknown")
    if decision.notification_id is not None:
        span.set_attribute("notification.id", decision.notification_id)
    span.set_attribute("notification.attempt", decision.attempt)
    span.set_attribute("notification.retried", decision.attempt > 1)
    span.set_attribute("notification.outcome", outcome)
    span.set_attribute("severity", severity(outcome, decision.attempt, decision.critical))
    if outcome in (RETRYING, DISCARDED) and decision.reason:
        span.set_attribute("notification.reason", mask_emails(decision.reason)[:500])
    if outcome == DISCARDED and trace is not None:
        # Red in Jaeger, and found by its "errors only" search. Retries stay unmarked:
        # they may still succeed, and their severity is 'warning'.
        span.set_status(Status(StatusCode.ERROR, "discarded"))
