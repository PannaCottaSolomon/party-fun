"""Core logic shared by the HTTP endpoints and the RabbitMQ consumer."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

from .config import Config
from .contracts import TYPES, UnknownRecipient, ValidationError, validate
from .email_sender import (
    DryRunSender,
    Email,
    EmailSender,
    EmailSendError,
    ResendSender,
    mask_email,
)
from .store import (
    FAILED,
    RETRYING,
    SENT,
    InMemoryStore,
    LogEntry,
    NotificationStore,
    StoreError,
    open_store,
)
from .templates import render

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class NotifyResult:
    type: str
    status: str               # "sent" or "duplicate"
    email_id: str | None = None


class NotificationService:
    def __init__(self, config: Config, sender: EmailSender, store: NotificationStore | None = None):
        self.config = config
        self.sender = sender
        self.store = store if store is not None else InMemoryStore()

    def notify(self, type_name: str, payload: Any, *, attempt: int = 1,
               record_failures: bool = True) -> NotifyResult:
        """Validate, render and send one notification, and log the outcome.

        Raises ValidationError (bad payload, don't retry), EmailSendError
        (check .permanent to decide whether to retry), UnknownRecipient (a user_id
        not in the user directory yet: retry later) or StoreError (the database is
        unreachable: retry later).

        `attempt` is 1 for the first try, 2 for the first retry, and so on.
        record_failures=False is for the RabbitMQ consumer: it knows whether a
        failure will be retried, so it records the row itself ('retrying' or
        'failed').
        """
        ntype = TYPES.get(type_name)
        if ntype is None:
            raise ValidationError({"type": f"unknown notification type '{type_name}'"})

        data = validate(ntype, self._with_recipient(payload), admin_email=self.config.admin_email)
        nid = data.get("notification_id")
        nid = None if nid is None else str(nid)

        # Duplicate check against the notification log (shared by all replicas).
        if nid is not None and self.store.already_sent(ntype.name, nid):
            log.info("duplicate %s notification %s skipped", ntype.name, nid)
            return NotifyResult(type=ntype.name, status="duplicate")

        subject, body_html, body_text = render(ntype, data, self.config.app_name)
        to, sent_subject = data["email"], subject
        if self.config.email_redirect_to:  # testing: everything goes to one inbox
            to, sent_subject = self.config.email_redirect_to, f"[to: {data['email']}] {subject}"[:250]
        email = Email(to=to, subject=sent_subject, html=body_html, text=body_text,
                      idempotency_key=f"{ntype.name}:{nid}" if nid is not None else None)
        try:
            email_id = self.sender.send(email)
        except EmailSendError as exc:
            if record_failures:
                self._log(LogEntry(ntype.name, FAILED, nid, data["email"], subject, error=str(exc),
                                   attempt=attempt))
            raise

        self._log(LogEntry(ntype.name, SENT, nid, data["email"], subject, email_id=email_id,
                           attempt=attempt))
        log.info("sent %s %s to %s%s on attempt %d (email id %s)", ntype.name, nid or "(no notification_id)",
                 mask_email(data["email"]),
                 f" (redirected to {mask_email(to)})" if to != data["email"] else "", attempt, email_id)
        return NotifyResult(type=ntype.name, status="sent", email_id=email_id)

    def _with_recipient(self, payload: Any) -> Any:
        """Fill in email (and name, unless sent) from the user directory when only user_id is given."""
        if not isinstance(payload, dict):
            return payload
        email, user_id = payload.get("email"), payload.get("user_id")
        if (isinstance(email, str) and email.strip()) or user_id is None or str(user_id).strip() == "":
            return payload
        user = self.store.find_user(str(user_id).strip())  # StoreError: database down, retry later
        if user is None:
            raise UnknownRecipient(str(user_id).strip())
        filled = {**payload, "email": user["email"]}
        if not (isinstance(payload.get("name"), str) and payload["name"].strip()) and user.get("name"):
            filled["name"] = user["name"]
        return filled

    def record_retrying(self, routing_key: str, body: bytes, reason: str, attempt: int) -> None:
        """Record a failed attempt that the consumer will retry."""
        self._log(self._entry_for(routing_key, body, RETRYING, reason, attempt))

    def record_discarded(self, routing_key: str, body: bytes, reason: str,
                         attempt: int | None = None) -> None:
        """Record a message the consumer gave up on as 'failed', picking out what we can from its body."""
        self._log(self._entry_for(routing_key, body, FAILED, f"discarded: {reason}", attempt))

    def _entry_for(self, routing_key: str, body: bytes, status: str, error: str,
                   attempt: int | None) -> LogEntry:
        payload: dict[str, Any] = {}
        try:
            parsed = json.loads(body)
            if isinstance(parsed, dict):
                payload = parsed
        except (ValueError, UnicodeDecodeError):
            pass
        type_name = routing_key.removeprefix("notify.") or "unknown"
        try:
            payload = self._with_recipient(payload)
        except (UnknownRecipient, StoreError):  # recipient unknown: the row just has no recipient
            pass
        nid = payload.get("notification_id")
        recipient = payload.get("email") if isinstance(payload.get("email"), str) else None
        subject = None
        ntype = TYPES.get(type_name)
        if ntype is not None:
            # A valid payload gives the real recipient (admin_alert fills it in) and the subject.
            try:
                data = validate(ntype, payload, admin_email=self.config.admin_email)
                recipient, subject = data["email"], render(ntype, data, self.config.app_name)[0]
            except (ValidationError, KeyError, TypeError, ValueError) as exc:  # broken payload: keep the basics
                log.debug("no subject for the %s log row: %s", type_name, exc)
        return LogEntry(type_name[:50], status, None if nid is None else str(nid), recipient, subject,
                        error=error, attempt=attempt)

    def _log(self, entry: LogEntry) -> None:
        # The email has already gone out (or failed); never let a logging problem
        # turn that into an error, or a retry would send the email twice.
        try:
            self.store.record(entry)
        except StoreError as exc:
            log.error("could not write to the notification log: %s", exc)


def build_service(config: Config) -> NotificationService:
    if config.email_enabled:
        sender: EmailSender = ResendSender(config.resend_api_key, config.email_from, config.resend_timeout_s)
    else:
        sender = DryRunSender()
    return NotificationService(config, sender, open_store(config.database_url))
