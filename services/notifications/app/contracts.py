"""Message contracts: what each notification type needs from the caller.

This file is the single source of truth for the payloads other services send.
The same payload works whether it arrives over HTTP (POST /notify_signup)
or RabbitMQ (routing key notify.signup).

Unknown extra fields are ignored, so producers can add fields without
breaking this service.

Recipient: send either `email` or `user_id`. With a user_id, the address (and
the name, unless one is sent) comes from the user directory this service keeps
from the Authentication service's user.* messages (see users.py), so producers
never need to handle email addresses.
"""
from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MAX_NOTIFICATION_ID_LEN = 200  # Resend idempotency keys are capped at 256 chars


class UnknownRecipient(Exception):
    """A user_id that isn't in the user directory (yet). Temporary: the user.created
    message may still be on its way, so the notification is retried."""

    def __init__(self, user_id: str):
        self.user_id = user_id
        super().__init__(f"user {user_id} is not in the user directory")


class ValidationError(Exception):
    """The payload is wrong. Retrying the same payload will never succeed."""

    def __init__(self, errors: dict[str, str]):
        self.errors = errors
        super().__init__("; ".join(f"{k}: {v}" for k, v in errors.items()))


@dataclass(frozen=True)
class NotificationType:
    name: str                     # e.g. "signup"
    endpoint: str                 # HTTP path, e.g. "/notify_signup"
    subject: str                  # Jinja template for the email subject
    template: str                 # HTML template file in email_templates/
    required: tuple[str, ...]     # fields the caller must send
    optional: tuple[str, ...] = ()
    description: str = ""
    recipient_required: bool = True  # admin_alert falls back to ADMIN_EMAIL

    @property
    def routing_key(self) -> str:
        return f"notify.{self.name}"


# Fields every type accepts.
COMMON_OPTIONAL = ("notification_id", "name", "user_id")

_TYPES = [
    NotificationType(
        name="signup",
        endpoint="/notify_signup",
        subject="You're in! {{ event_title }}",
        template="signup.html",
        required=("email", "event_title", "event_date", "location", "registration_id"),
        optional=("amount", "currency"),
        description="Participant signed up and paid for an event.",
    ),
    NotificationType(
        name="account_created",
        endpoint="/notify_account_created",
        subject="Welcome to {{ app_name }}",
        template="account_created.html",
        required=("email", "role"),
        description="New participant or organiser account.",
    ),
    NotificationType(
        name="event_created",
        endpoint="/notify_create_event",
        subject="Your event is live: {{ event_title }}",
        template="event_created.html",
        required=("email", "event_title", "event_date", "location", "event_id"),
        description="Organiser created an event.",
    ),
    NotificationType(
        name="event_updated",
        endpoint="/notify_update_event",
        subject="Event updated: {{ event_title }}",
        template="event_updated.html",
        required=("email", "event_title", "event_id", "changes"),
        optional=("audience",),
        description="Event details changed. audience=organiser (confirmation) or participant (heads-up).",
    ),
    NotificationType(
        name="event_deleted",
        endpoint="/notify_delete_event",
        subject="Event cancelled: {{ event_title }}",
        template="event_deleted.html",
        required=("email", "event_title", "event_id"),
        optional=("reason", "audience"),
        description="Event cancelled or deleted (by organiser or auto-cancel).",
    ),
    NotificationType(
        name="refund",
        endpoint="/notify_refund",
        subject="Refund issued: {{ event_title }}",
        template="refund.html",
        required=("email", "event_title", "amount", "registration_id"),
        optional=("currency", "refund_id"),
        description="Participant was refunded.",
    ),
    NotificationType(
        name="admin_alert",
        endpoint="/notify_admin_alert",
        subject="[{{ app_name }} ALERT] {{ source }}: {{ error }}",
        template="admin_alert.html",
        required=("source", "error"),
        optional=("email", "details"),
        description="A message landed in a dead-letter queue (e.g. a failed refund). Goes to ADMIN_EMAIL.",
        recipient_required=False,
    ),
]

TYPES: dict[str, NotificationType] = {t.name: t for t in _TYPES}
BY_ROUTING_KEY: dict[str, NotificationType] = {t.routing_key: t for t in _TYPES}

DEFAULTS: dict[str, Any] = {
    "name": "there",
    "currency": "SGD",
    "audience": "organiser",
}


# ---- field checks: return an error message, or None if the value is fine ----

def _non_empty_str(v: Any) -> str | None:
    if not isinstance(v, str) or not v.strip():
        return "must be a non-empty string"
    return None


def _id(v: Any) -> str | None:
    # IDs may be numbers (database keys) or strings (UUIDs)
    if isinstance(v, bool) or not isinstance(v, (str, int)) or str(v).strip() == "":
        return "must be a string or integer id"
    return None


def _email(v: Any) -> str | None:
    if not isinstance(v, str) or not EMAIL_RE.match(v.strip()):
        return "must be a valid email address"
    return None


def _amount(v: Any) -> str | None:
    if isinstance(v, bool) or not isinstance(v, int) or v < 0:
        return "must be a non-negative integer in cents (e.g. 1250 for $12.50)"
    return None


def _currency(v: Any) -> str | None:
    if not isinstance(v, str) or not re.fullmatch(r"[A-Za-z]{3}", v):
        return "must be a 3-letter currency code, e.g. SGD"
    return None


def _one_of(*allowed: str) -> Callable[[Any], str | None]:
    def check(v: Any) -> str | None:
        if v not in allowed:
            return f"must be one of: {', '.join(allowed)}"
        return None
    return check


def _changes(v: Any) -> str | None:
    if isinstance(v, dict) and v:
        return None
    if isinstance(v, list) and v and all(isinstance(x, str) for x in v):
        return None
    return 'must be a non-empty object ({"venue": "New hall"}) or list of strings'


def _notification_id(v: Any) -> str | None:
    if _id(v) or len(str(v)) > MAX_NOTIFICATION_ID_LEN:
        return f"must be a string or integer up to {MAX_NOTIFICATION_ID_LEN} characters"
    return None


def _details(v: Any) -> str | None:
    if not isinstance(v, (dict, str)):
        return "must be an object or string"
    return None


CHECKS: dict[str, Callable[[Any], str | None]] = {
    "email": _email,
    "name": _non_empty_str,
    "event_title": _non_empty_str,
    "event_date": _non_empty_str,
    "location": _non_empty_str,
    "registration_id": _id,
    "event_id": _id,
    "user_id": _id,
    "refund_id": _id,
    "notification_id": _notification_id,
    "amount": _amount,
    "currency": _currency,
    "role": _one_of("participant", "organiser"),
    "audience": _one_of("organiser", "participant"),
    "changes": _changes,
    "reason": _non_empty_str,
    "source": _non_empty_str,
    "error": _non_empty_str,
    "details": _details,
}


def _present(v: Any) -> bool:
    return v is not None and not (isinstance(v, str) and v.strip() == "")


def validate(ntype: NotificationType, payload: Any, admin_email: str = "") -> dict[str, Any]:
    """Check a payload and return a clean copy with defaults filled in.

    Every known field is present in the result (None if not sent), so the
    email templates never hit a missing variable.
    """
    if not isinstance(payload, dict):
        raise ValidationError({"payload": "must be a JSON object"})

    errors: dict[str, str] = {}
    fields = ntype.required + ntype.optional + COMMON_OPTIONAL
    data: dict[str, Any] = {}

    for field in fields:
        value = payload.get(field)
        if not _present(value):
            if field in ntype.required:
                errors[field] = "is required (or send user_id)" if field == "email" else "is required"
            data[field] = DEFAULTS.get(field)
            continue
        problem = CHECKS[field](value)
        if problem:
            errors[field] = problem
        data[field] = value.strip() if isinstance(value, str) else value

    if not ntype.recipient_required and not data.get("email"):
        if admin_email:
            data["email"] = admin_email
        else:
            errors["email"] = "no recipient: send 'email' or set ADMIN_EMAIL on the notification service"

    if errors:
        raise ValidationError(errors)

    if data.get("currency"):
        data["currency"] = data["currency"].upper()
    return data


def describe() -> list[dict[str, Any]]:
    """Machine-readable contract, served at GET /notification-types."""
    return [
        {
            "type": t.name,
            "http_endpoint": f"POST {t.endpoint}",
            "routing_key": t.routing_key,
            "required": list(t.required),
            "optional": list(t.optional + COMMON_OPTIONAL),
            "description": t.description,
        }
        for t in _TYPES
    ]
