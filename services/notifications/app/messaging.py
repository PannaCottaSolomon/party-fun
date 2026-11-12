"""Decides what to do with each RabbitMQ message. No RabbitMQ code here, so it's easy to test.

Outcomes:
  ack      email sent, or a duplicate we already sent
  retry    temporary failure (Resend down, rate limited, network, database down, or a
           user_id not in the user directory yet): try again later
  discard  will never succeed (bad JSON, missing fields, unknown type, retries used up):
           logged and recorded as 'failed' in the notification log, then dropped.
           This service has no dead-letter queue; the Payment service owns the
           team's DLQ, because a lost payment matters far more than a lost email.
"""
from __future__ import annotations

import json
import logging
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from .contracts import BY_ROUTING_KEY, UnknownRecipient, ValidationError
from .email_sender import EmailSendError
from .service import NotificationService
from .store import StoreError

log = logging.getLogger(__name__)

RETRY_COUNT_HEADER = "x-retry-count"
ORIGINAL_ROUTING_KEY_HEADER = "x-original-routing-key"
ACK, RETRY, DISCARD = "ack", "retry", "discard"


@dataclass(frozen=True)
class Decision:
    action: str
    reason: str = ""
    routing_key: str = ""
    retry_count: int = 0
    attempt: int = 1   # which attempt this was: 1 = first try, 2 = first retry, ...
    notification_id: str | None = None
    outcome: str = ""  # sent | duplicate | retrying | discarded (for traces)
    critical: bool = False  # discarded because every email is failing (Resend rejected the API key)

    @property
    def label(self) -> str:
        """'notify.signup signup-reg-2002': what log lines call the message."""
        return f"{self.routing_key} {self.notification_id or '(no notification_id)'}"


def _as_str(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return "" if value is None else str(value)


def _retry_count(headers: Mapping[str, Any]) -> int:
    try:
        return max(0, int(_as_str(headers.get(RETRY_COUNT_HEADER)) or 0))
    except ValueError:
        return 0


def process(service: NotificationService, routing_key: str, body: bytes,
            headers: Mapping[str, Any] | None, max_retries: int) -> Decision:
    headers = headers or {}
    # A retried message comes back via the retry queue, which changes its routing
    # key, so the original one travels in a header.
    key = _as_str(headers.get(ORIGINAL_ROUTING_KEY_HEADER)) or routing_key
    attempts_so_far = _retry_count(headers)
    attempt = attempts_so_far + 1
    nid: str | None = None  # filled in once the body is parsed, for log lines

    def discard(reason: str, critical: bool = False) -> Decision:
        decision = Decision(DISCARD, reason=reason, routing_key=key, retry_count=attempts_so_far,
                            attempt=attempt, notification_id=nid, outcome="discarded", critical=critical)
        # The CloudWatch alarm matches this line (ERROR + "discarding"): keep the word.
        log.error("discarding %s after attempt %d of %d: %s",
                  decision.label, attempt, max_retries + 1, reason)
        return decision

    ntype = BY_ROUTING_KEY.get(key)
    if ntype is None:
        return discard(f"unknown routing key '{key}'")

    try:
        payload = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return discard("body is not valid JSON")
    if isinstance(payload, dict) and payload.get("notification_id") is not None:
        nid = str(payload["notification_id"])

    try:
        # The consumer records failed attempts itself, as 'retrying' or 'failed'.
        result = service.notify(ntype.name, payload, attempt=attempt, record_failures=False)
        return Decision(ACK, routing_key=key, retry_count=attempts_so_far, attempt=attempt,
                        notification_id=nid, outcome=result.status)
    except ValidationError as exc:
        return discard(f"validation failed: {exc}")
    except EmailSendError as exc:
        if exc.permanent:
            # 401: Resend rejected the API key itself, so every email is failing, not just this one.
            return discard(f"email rejected: {exc}", critical=exc.status == 401)
        reason = str(exc)
    except UnknownRecipient as exc:  # user.created may not have arrived yet: try again later
        reason = str(exc)
    except StoreError as exc:  # can't check for duplicates right now: try again later
        reason = str(exc)
    except Exception as exc:  # unexpected bug or outage: treat as temporary
        log.exception("unexpected error handling %s", key)
        reason = f"unexpected error: {exc}"

    if attempts_so_far >= max_retries:
        return discard(f"gave up after {max_retries} retries: {reason}")
    # The consumer logs the retry once it is safely queued.
    return Decision(RETRY, reason=reason, routing_key=key, retry_count=attempts_so_far + 1,
                    attempt=attempt, notification_id=nid, outcome="retrying")


def retry_headers(original: Mapping[str, Any] | None, decision: Decision) -> dict[str, Any]:
    """Headers for a message we re-publish to the retry queue.

    Keeps the original headers (including any tracing headers such as
    `traceparent`) and adds our bookkeeping.
    """
    headers = dict(original or {})
    headers[ORIGINAL_ROUTING_KEY_HEADER] = decision.routing_key
    headers[RETRY_COUNT_HEADER] = decision.retry_count
    return headers
