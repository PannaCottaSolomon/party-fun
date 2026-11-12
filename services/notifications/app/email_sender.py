"""Sends emails through Resend's REST API (https://resend.com/docs/api-reference/emails/send-email).

We call the API with `requests` instead of the Resend SDK so we can tell
"retry later" errors (rate limit, Resend down, network blip) apart from
"never going to work" errors (bad address, invalid request).
"""
from __future__ import annotations

import logging
import threading
import uuid
from dataclasses import dataclass, field
from typing import Protocol

import requests

log = logging.getLogger(__name__)

RESEND_URL = "https://api.resend.com/emails"

# Status codes worth retrying: timeout, rate limit, and any 5xx.
_RETRYABLE_4XX = {408, 429}


class EmailSendError(Exception):
    """Sending failed. `permanent=True` means retrying the same email won't help."""

    def __init__(self, message: str, permanent: bool, status: int | None = None):
        super().__init__(message)
        self.permanent = permanent
        self.status = status


@dataclass(frozen=True)
class Email:
    to: str
    subject: str
    html: str
    text: str
    idempotency_key: str | None = None


class EmailSender(Protocol):
    def send(self, email: Email) -> str:
        """Send the email and return the provider's message id."""


def mask_email(address: str) -> str:
    """'participant@example.com' -> 'p***@example.com', so logs don't hold full addresses."""
    local, _, domain = address.partition("@")
    return f"{local[:1]}***@{domain}" if domain else "***"


class ResendSender:
    def __init__(self, api_key: str, from_address: str, timeout_s: float = 10.0,
                 session: requests.Session | None = None):
        self._api_key = api_key
        self._from = from_address
        self._timeout = timeout_s
        self._shared_session = session
        # requests.Session isn't guaranteed thread-safe, and the HTTP server and
        # RabbitMQ consumer run in different threads, so each thread gets its own.
        self._local = threading.local()

    @property
    def _session(self) -> requests.Session:
        if self._shared_session is not None:
            return self._shared_session
        if not hasattr(self._local, "session"):
            self._local.session = requests.Session()
        return self._local.session

    def send(self, email: Email) -> str:
        headers = {"Authorization": f"Bearer {self._api_key}"}
        if email.idempotency_key:
            # Resend remembers this key for 24h, so a retried request (e.g. after
            # a pod restart mid-send) returns the original email instead of a second one.
            headers["Idempotency-Key"] = email.idempotency_key
        body = {
            "from": self._from,
            "to": [email.to],
            "subject": email.subject,
            "html": email.html,
            "text": email.text,
        }
        try:
            resp = self._session.post(RESEND_URL, json=body, headers=headers, timeout=self._timeout)
        except requests.RequestException as exc:
            raise EmailSendError(f"network error calling Resend: {exc}", permanent=False) from exc

        if 200 <= resp.status_code < 300:
            try:
                return str(resp.json().get("id", ""))
            except ValueError:
                return ""

        detail = resp.text[:300]
        status = resp.status_code
        if status >= 500 or status in _RETRYABLE_4XX:
            permanent = False
        elif status == 409 and "concurrent" in detail.lower():
            # Same idempotency key is still being processed by Resend: try again shortly.
            permanent = False
        else:
            permanent = True
        raise EmailSendError(f"Resend returned {status}: {detail}", permanent=permanent, status=status)


@dataclass
class DryRunSender:
    """Logs emails instead of sending them. Used for local dev, CI and tests."""

    sent: list[Email] = field(default_factory=list)

    def send(self, email: Email) -> str:
        self.sent.append(email)
        log.info("[dry run] email to %s: %s", mask_email(email.to), email.subject)
        return f"dry-run-{uuid.uuid4()}"
