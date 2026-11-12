"""User directory: who each user is, so other services can send a user_id instead of an email address.

The Authentication service owns user accounts. It publishes on the `user.events`
topic exchange:

    user.created / user.updated   {"id": "<Cognito sub>", "email": "...", "fullName": "...", ...}
    user.deleted                  {"id": "<Cognito sub>"}

This service keeps its own copy of id -> email and name (table `users`), so the
email address stays with the two services that need it: Authentication and
this one. Events and Payment only ever handle user ids. Fields we don't need
(e.g. phoneNumber) are never stored.

Two ids per user, for now:
    user_id     the Cognito `sub` (a UUID), as Authentication uses it
    service_id  the number the frontend derives from the sub (toServiceId in
                the frontend's src/api/serviceIds.ts), which Events and Payment
                use until they take the user from the token. A notification may
                name the recipient by either. Remove service_id once every
                service uses the Cognito sub.

Like messaging.py, this module has no RabbitMQ code, so it is easy to test.
"""
from __future__ import annotations

import json
import logging
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from .contracts import EMAIL_RE
from .store import NotificationStore, StoreError

log = logging.getLogger(__name__)

USER_EVENT_KEYS = ("user.created", "user.updated", "user.deleted")
ACK, RETRY, DISCARD = "ack", "retry", "discard"
RETRY_COUNT_HEADER = "x-retry-count"
ORIGINAL_ROUTING_KEY_HEADER = "x-original-routing-key"


def service_id(user_id: str) -> str:
    """The frontend's toServiceId(): 32-bit FNV-1a of the id, shifted to a positive 31-bit int (never 0)."""
    data = str(user_id).encode("utf-16-le")  # JavaScript's charCodeAt works on UTF-16 code units
    h = 0x811C9DC5
    for i in range(0, len(data), 2):
        h ^= int.from_bytes(data[i:i + 2], "little")
        h = (h * 0x01000193) & 0xFFFFFFFF
    return str((h >> 1) or 1)


@dataclass(frozen=True)
class UserDecision:
    action: str            # ack | retry | discard
    routing_key: str
    reason: str = ""
    retry_count: int = 0   # for a retry: how many retries this will be


def _as_str(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return "" if value is None else str(value)


def process_user_message(store: NotificationStore, routing_key: str, body: bytes,
                         headers: Mapping[str, Any] | None, max_retries: int) -> UserDecision:
    """Apply one user.* message to the user directory."""
    headers = headers or {}
    key = _as_str(headers.get(ORIGINAL_ROUTING_KEY_HEADER)) or routing_key
    try:
        retries_so_far = max(0, int(_as_str(headers.get(RETRY_COUNT_HEADER)) or 0))
    except ValueError:
        retries_so_far = 0

    def discard(reason: str) -> UserDecision:
        log.error("user directory: dropping %s message: %s", key, reason)
        return UserDecision(DISCARD, key, reason)

    if key not in USER_EVENT_KEYS:
        return discard("not a user.created, user.updated or user.deleted message")
    try:
        payload = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return discard("body is not valid JSON")
    if not isinstance(payload, dict):
        return discard("body is not a JSON object")
    user_id = payload.get("id")
    if not isinstance(user_id, str) or not user_id.strip():
        return discard("no user id ('id')")
    user_id = user_id.strip()

    try:
        if key == "user.deleted":
            store.delete_user(user_id)
            log.info("user directory: removed user %s", user_id)
        else:
            email = payload.get("email")
            if not isinstance(email, str) or not EMAIL_RE.match(email.strip()):
                return discard(f"user {user_id} has no valid email address")
            name = payload.get("fullName")
            store.save_user(user_id, service_id(user_id), email, name if isinstance(name, str) else None)
            log.info("user directory: saved user %s (%s)", user_id, key)
        return UserDecision(ACK, key)
    except StoreError as exc:  # database down: keep the message and try again later
        if retries_so_far >= max_retries:
            return discard(f"gave up after {max_retries} retries: {exc}")
        log.warning("user directory: could not apply %s for user %s, retrying: %s", key, user_id, exc)
        return UserDecision(RETRY, key, str(exc), retries_so_far + 1)


def retry_headers(original: Mapping[str, Any] | None, decision: UserDecision) -> dict[str, Any]:
    headers = dict(original or {})  # keeps traceparent
    headers[ORIGINAL_ROUTING_KEY_HEADER] = decision.routing_key
    headers[RETRY_COUNT_HEADER] = decision.retry_count
    return headers
