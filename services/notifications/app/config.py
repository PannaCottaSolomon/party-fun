"""Service configuration, read from environment variables.

Every setting has a sensible default for local development, so the service
starts with no configuration at all (in dry-run mode, logging emails instead
of sending them).
"""
from __future__ import annotations

import logging
import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import quote

log = logging.getLogger(__name__)

_TRUE = {"1", "true", "yes", "on"}


def _bool(value: str | None, default: bool) -> bool:
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in _TRUE


def _int(value: str | None, default: int) -> int:
    if value is None or value.strip() == "":
        return default
    return int(value)


# Wait before retry 1, 2 and 3: 30 seconds, 2 minutes, 10 minutes. Together they
# ride out a provider outage of about 12 minutes before an email is given up on.
DEFAULT_RETRY_DELAYS_MS: tuple[int, ...] = (30_000, 120_000, 600_000)


def _delays(value: str | None, default: tuple[int, ...]) -> tuple[int, ...]:
    """'30000,120000,600000' -> (30000, 120000, 600000)."""
    if value is None or value.strip() == "":
        return default
    try:
        delays = tuple(int(part) for part in value.replace(" ", "").split(",") if part)
    except ValueError:
        delays = ()
    if not delays or any(d <= 0 for d in delays):
        raise ValueError(
            f"RETRY_DELAYS_MS={value!r}: expected comma-separated positive milliseconds, "
            "e.g. 30000,120000,600000"
        )
    return delays


def _rabbitmq_url(env: Mapping[str, str], default: str) -> str:
    """RABBITMQ_URL, or one built from RABBITMQ_HOST / _PORT / _USERNAME / _PASSWORD / _VHOST.

    The parts form is for AWS: ECS injects the username and password from Secrets
    Manager as separate variables, and a password may contain characters that
    have to be escaped inside a URL.
    """
    if env.get("RABBITMQ_URL"):
        return env["RABBITMQ_URL"]
    host = env.get("RABBITMQ_HOST", "").strip()
    if not host:
        return default
    user = quote(env.get("RABBITMQ_USERNAME", "guest"), safe="")
    password = quote(env.get("RABBITMQ_PASSWORD", "guest"), safe="")
    vhost = quote(env.get("RABBITMQ_VHOST", "/"), safe="")
    return f"amqp://{user}:{password}@{host}:{env.get('RABBITMQ_PORT') or 5672}/{vhost}"


def _database_url(env: Mapping[str, str], default: str) -> str:
    """DATABASE_URL, or a MySQL URL built from DB_HOST / _PORT / _NAME / _USERNAME / _PASSWORD (same reason)."""
    if env.get("DATABASE_URL"):
        return env["DATABASE_URL"]
    host = env.get("DB_HOST", "").strip()
    if not host:
        return default
    user = quote(env.get("DB_USERNAME", ""), safe="")
    password = quote(env.get("DB_PASSWORD", ""), safe="")
    name = env.get("DB_NAME") or "notifications"
    return f"mysql://{user}:{password}@{host}:{env.get('DB_PORT') or 3306}/{name}"


def duration_label(ms: int) -> str:
    """600000 -> '10m', 30000 -> '30s', 1500 -> '1500ms' (used in retry queue names)."""
    if ms % 60_000 == 0:
        return f"{ms // 60_000}m"
    if ms % 1_000 == 0:
        return f"{ms // 1_000}s"
    return f"{ms}ms"


@dataclass(frozen=True)
class Config:
    # Branding used in email subjects and footers
    app_name: str = "Campus Events"

    # HTTP server
    port: int = 5000

    # RabbitMQ
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/%2F"
    exchange: str = "notifications"          # topic exchange producers publish to
    queue: str = "notification_service"      # this service's work queue
    # Wait before each retry, in order; one retry per entry, then the email is discarded.
    retry_delays_ms: tuple[int, ...] = DEFAULT_RETRY_DELAYS_MS
    prefetch: int = 10                       # unacked messages held at once
    enable_consumer: bool = True             # false = HTTP only (handy for tests)

    # Email (Resend)
    resend_api_key: str = ""
    email_from: str = "Campus Events <onboarding@resend.dev>"
    admin_email: str = ""                    # receives admin_alert notifications
    dry_run: bool = False                    # log emails instead of sending
    # Testing: send every email to this address instead, with the real recipient in the
    # subject ("[to: alex@example.com] ..."). Resend's test sender only delivers to the
    # Resend account's own address. Empty = send to the real recipient.
    email_redirect_to: str = ""
    resend_timeout_s: float = 10.0

    # Notification log: a record of every email, also used for the duplicate check.
    #   sqlite:///data/notifications.db         local file (default; lost when a container is replaced)
    #   mysql://user:pass@host:3306/notifications   e.g. AWS RDS, shared by all replicas
    #   memory                                  in-memory only (tests)
    database_url: str = "sqlite:///data/notifications.db"

    # User directory: user.* messages from the Authentication service.
    user_exchange: str = "user.events"       # topic exchange the Authentication service publishes to
    user_queue: str = "notifications.users"  # this service's queue for them
    user_directory_enabled: bool = True
    user_retry_delay_ms: int = 30_000        # database down: try the user message again after this
    user_max_retries: int = 20               # ...this many times (about 10 minutes)

    # Shared secret for the HTTP endpoints (X-API-Key header). Empty = no check.
    api_key: str = ""

    # Seconds to finish the current message when the container is told to stop.
    shutdown_timeout_s: float = 25.0

    log_level: str = "INFO"
    log_format: str = "text"                 # "json" in containers, so logs are searchable

    @property
    def max_retries(self) -> int:
        return len(self.retry_delays_ms)

    def retry_queue_name(self, delay_ms: int) -> str:
        # The delay is part of the name, so changing a delay creates a new queue
        # instead of clashing with the old one's settings.
        return f"{self.queue}.retry.{duration_label(delay_ms)}"

    @property
    def retry_queues(self) -> list[tuple[str, int]]:
        """(queue name, delay) for each distinct retry delay, in order."""
        seen: dict[str, int] = {}
        for delay in self.retry_delays_ms:
            seen.setdefault(self.retry_queue_name(delay), delay)
        return list(seen.items())

    def retry_delay_for(self, retry_number: int) -> int:
        """Delay in ms before retry 1, 2, 3...; later retries reuse the last (longest) delay."""
        index = min(max(retry_number, 1), len(self.retry_delays_ms)) - 1
        return self.retry_delays_ms[index]

    def retry_queue_for(self, retry_number: int) -> str:
        """Queue for retry 1, 2, 3..."""
        return self.retry_queue_name(self.retry_delay_for(retry_number))

    @property
    def user_retry_queue(self) -> str:
        return f"{self.user_queue}.retry.{duration_label(self.user_retry_delay_ms)}"

    @property
    def email_enabled(self) -> bool:
        """True when emails really go out through Resend."""
        return bool(self.resend_api_key) and not self.dry_run

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Config:
        env = os.environ if env is None else env
        d = cls()
        config = cls(
            app_name=env.get("APP_NAME", d.app_name),
            port=_int(env.get("PORT"), d.port),
            rabbitmq_url=_rabbitmq_url(env, d.rabbitmq_url),
            exchange=env.get("NOTIFICATION_EXCHANGE", d.exchange),
            queue=env.get("NOTIFICATION_QUEUE", d.queue),
            retry_delays_ms=_delays(env.get("RETRY_DELAYS_MS"), d.retry_delays_ms),
            prefetch=_int(env.get("PREFETCH_COUNT"), d.prefetch),
            enable_consumer=_bool(env.get("ENABLE_CONSUMER"), d.enable_consumer),
            resend_api_key=env.get("RESEND_API_KEY", d.resend_api_key),
            email_from=env.get("EMAIL_FROM", d.email_from),
            admin_email=env.get("ADMIN_EMAIL", d.admin_email),
            dry_run=_bool(env.get("EMAIL_DRY_RUN"), d.dry_run),
            email_redirect_to=env.get("EMAIL_REDIRECT_TO", d.email_redirect_to).strip(),
            user_exchange=env.get("USER_EXCHANGE", d.user_exchange),
            user_queue=env.get("USER_QUEUE", d.user_queue),
            user_directory_enabled=_bool(env.get("USER_DIRECTORY_ENABLED"), d.user_directory_enabled),
            resend_timeout_s=float(env.get("RESEND_TIMEOUT_S", d.resend_timeout_s)),
            database_url=_database_url(env, d.database_url),
            api_key=env.get("API_KEY", d.api_key),
            shutdown_timeout_s=float(env.get("SHUTDOWN_TIMEOUT_S", d.shutdown_timeout_s)),
            log_level=env.get("LOG_LEVEL", d.log_level).upper(),
            log_format=env.get("LOG_FORMAT", d.log_format).lower(),
        )
        if not config.email_enabled:
            log.warning(
                "Email sending is OFF (dry run). Set RESEND_API_KEY and "
                "EMAIL_DRY_RUN=false to send real emails."
            )
        if env.get("RETRY_DELAY_MS") or env.get("MAX_RETRIES"):
            log.warning(
                "RETRY_DELAY_MS and MAX_RETRIES are no longer used; set RETRY_DELAYS_MS instead "
                "(now %s ms)", ",".join(str(d) for d in config.retry_delays_ms)
            )
        if config.email_redirect_to:
            log.warning("EMAIL_REDIRECT_TO is set: every email goes to %s instead of its recipient.",
                        config.email_redirect_to)
        if not config.api_key:
            log.warning("API_KEY is not set: the HTTP endpoints accept requests without a key.")
        return config
