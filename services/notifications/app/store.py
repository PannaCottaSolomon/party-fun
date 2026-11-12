"""Notification log: one row per attempt to send an email.

Each row has an attempt number (1 = first try). A failed attempt that will be
retried is 'retrying'; the last row for an email is 'sent' or 'failed'.

The same database also holds the user directory (table `users`): who each user
is (user id -> email address and name), kept up to date from the Authentication
service's user.created / user.updated / user.deleted messages. Other services
then send a user_id instead of an email address.

It answers "did Alex get the refund email?" (GET /notifications?email=...) and
is also the duplicate check: a notification_id that is already logged as
'sent' is never emailed again. Because the log lives in a database, the check
is shared by every copy of the service and survives restarts, which is why
no separate Redis cache is needed.

Pick the database with DATABASE_URL:
    sqlite:///data/notifications.db               local file (default)
    mysql://user:password@host:3306/notifications  MySQL, e.g. AWS RDS
    memory                                         in-memory, for tests
"""
from __future__ import annotations

import logging
import os
import sqlite3
import threading
from contextlib import closing
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Protocol
from urllib.parse import unquote, urlparse

log = logging.getLogger(__name__)

SENT, FAILED, RETRYING = "sent", "failed", "retrying"
MAX_LIMIT = 200


class StoreError(Exception):
    """The notification log couldn't be read or written (e.g. database down)."""


@dataclass(frozen=True)
class LogEntry:
    type: str
    status: str                      # sent | retrying | failed
    notification_id: str | None = None
    recipient: str | None = None
    subject: str | None = None
    email_id: str | None = None
    error: str | None = None
    attempt: int | None = None       # 1 = first try, 2 = first retry, ...


def _now() -> str:
    # One string format that both SQLite (TEXT) and MySQL (DATETIME) accept.
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")


def _clean(entry: LogEntry) -> dict[str, Any]:
    row = asdict(entry)
    if row["recipient"]:
        row["recipient"] = row["recipient"].strip().lower()
    if row["notification_id"] is not None:
        row["notification_id"] = str(row["notification_id"])[:200]
    for field, size in (("subject", 255), ("email_id", 100), ("error", 2000)):
        if row[field]:
            row[field] = str(row[field])[:size]
    return row


class NotificationStore(Protocol):
    def already_sent(self, type_name: str, notification_id: str) -> bool: ...

    def record(self, entry: LogEntry) -> None: ...

    def find(self, email: str | None = None, type_name: str | None = None,
             notification_id: str | None = None, limit: int = 50) -> list[dict[str, Any]]: ...

    # user directory
    def save_user(self, user_id: str, service_id: str, email: str, name: str | None) -> None: ...

    def delete_user(self, user_id: str) -> None: ...

    def find_user(self, ref: str) -> dict[str, Any] | None: ...


def _user_row(user_id: str, service_id: str, email: str, name: str | None) -> tuple:
    return (str(user_id)[:64], str(service_id)[:20], email.strip().lower()[:320],
            None if name is None else str(name).strip()[:255] or None, _now())


# --------------------------------------------------------------------- memory

class InMemoryStore:
    """For tests and quick experiments. Lost when the process stops."""

    def __init__(self):
        self._rows: list[dict[str, Any]] = []
        self._users: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()

    def already_sent(self, type_name: str, notification_id: str) -> bool:
        with self._lock:
            return any(r["type"] == type_name and r["notification_id"] == str(notification_id)
                       and r["status"] == SENT for r in self._rows)

    def record(self, entry: LogEntry) -> None:
        with self._lock:
            row = _clean(entry)
            row["id"] = len(self._rows) + 1
            row["created_at"] = _now()
            self._rows.append(row)

    def find(self, email=None, type_name=None, notification_id=None, limit=50):
        with self._lock:
            rows = [r for r in reversed(self._rows)
                    if (email is None or r["recipient"] == email.strip().lower())
                    and (type_name is None or r["type"] == type_name)
                    and (notification_id is None or r["notification_id"] == str(notification_id))]
            return [dict(r) for r in rows[: min(limit, MAX_LIMIT)]]

    def save_user(self, user_id, service_id, email, name):
        uid, sid, mail, nm, now = _user_row(user_id, service_id, email, name)
        with self._lock:
            self._users[uid] = {"user_id": uid, "service_id": sid, "email": mail, "name": nm, "updated_at": now}

    def delete_user(self, user_id):
        with self._lock:
            self._users.pop(str(user_id), None)

    def find_user(self, ref):
        ref = str(ref)
        with self._lock:
            user = self._users.get(ref) or next((u for u in self._users.values() if u["service_id"] == ref), None)
            return None if user is None else dict(user)


# ------------------------------------------------------------------------ SQL

_COLUMNS = "id, type, notification_id, recipient, subject, status, attempt, email_id, error, created_at"


class _SQLStore:
    """Shared SQL; subclasses supply the connection and the CREATE TABLE statements."""

    schema: tuple[str, ...] = ()
    # Tables created before attempt numbers existed get the column added on startup.
    add_attempt_column = "ALTER TABLE notifications ADD COLUMN attempt INTEGER"

    def __init__(self):
        self._schema_ready = False
        self._schema_lock = threading.Lock()

    # subclasses implement this; `sql` uses ? placeholders
    def _run(self, sql: str, params: tuple = (), fetch: bool = False) -> list[dict[str, Any]]:
        raise NotImplementedError

    def _column_names(self) -> set[str]:
        raise NotImplementedError

    def _ensure_schema(self) -> None:
        # Created lazily, so the service still starts if the database is briefly unavailable.
        if self._schema_ready:
            return
        with self._schema_lock:
            if not self._schema_ready:
                for statement in self.schema:
                    self._run(statement)
                self._add_missing_columns()
                self._schema_ready = True

    def _add_missing_columns(self) -> None:
        if "attempt" in self._column_names():
            return
        try:
            self._run(self.add_attempt_column)
            log.info("added the attempt column to the notifications table")
        except Exception:  # another replica may have just added it
            if "attempt" not in self._column_names():
                raise

    def _execute(self, sql: str, params: tuple = (), fetch: bool = False) -> list[dict[str, Any]]:
        try:
            self._ensure_schema()
            return self._run(sql, params, fetch)
        except StoreError:
            raise
        except Exception as exc:  # driver-specific errors -> one error type for callers
            raise StoreError(f"notification log unavailable: {exc}") from exc

    def already_sent(self, type_name: str, notification_id: str) -> bool:
        rows = self._execute(
            "SELECT 1 AS hit FROM notifications WHERE type = ? AND notification_id = ? AND status = ? LIMIT 1",
            (type_name, str(notification_id), SENT), fetch=True)
        return bool(rows)

    def record(self, entry: LogEntry) -> None:
        row = _clean(entry)
        self._execute(
            "INSERT INTO notifications (type, notification_id, recipient, subject, status, attempt, email_id, "
            "error, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (row["type"], row["notification_id"], row["recipient"], row["subject"], row["status"],
             row["attempt"], row["email_id"], row["error"], _now()))

    def find(self, email=None, type_name=None, notification_id=None, limit=50):
        where, params = [], []
        if email is not None:
            where.append("recipient = ?")
            params.append(email.strip().lower())
        if type_name is not None:
            where.append("type = ?")
            params.append(type_name)
        if notification_id is not None:
            where.append("notification_id = ?")
            params.append(str(notification_id))
        sql = f"SELECT {_COLUMNS} FROM notifications"
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY id DESC LIMIT ?"
        params.append(max(1, min(int(limit), MAX_LIMIT)))
        rows = self._execute(sql, tuple(params), fetch=True)
        for row in rows:
            row["created_at"] = str(row["created_at"])
        return rows

    # ---- user directory
    save_user_sql = ""  # subclasses: insert, or update the row with the same user_id

    def save_user(self, user_id, service_id, email, name):
        self._execute(self.save_user_sql, _user_row(user_id, service_id, email, name))

    def delete_user(self, user_id):
        self._execute("DELETE FROM users WHERE user_id = ?", (str(user_id),))

    def find_user(self, ref):
        rows = self._execute("SELECT user_id, service_id, email, name FROM users "
                             "WHERE user_id = ? OR service_id = ? LIMIT 1", (str(ref), str(ref)), fetch=True)
        return rows[0] if rows else None


class SQLiteStore(_SQLStore):
    schema = (
        """CREATE TABLE IF NOT EXISTS notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            notification_id TEXT,
            recipient TEXT,
            subject TEXT,
            status TEXT NOT NULL,
            attempt INTEGER,
            email_id TEXT,
            error TEXT,
            created_at TEXT NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_notifications_dedup ON notifications (type, notification_id)",
        "CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient)",
        """CREATE TABLE IF NOT EXISTS users (
            user_id TEXT PRIMARY KEY,
            service_id TEXT,
            email TEXT NOT NULL,
            name TEXT,
            updated_at TEXT NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_users_service_id ON users (service_id)",
    )
    save_user_sql = ("INSERT INTO users (user_id, service_id, email, name, updated_at) VALUES (?, ?, ?, ?, ?) "
                     "ON CONFLICT (user_id) DO UPDATE SET service_id = excluded.service_id, "
                     "email = excluded.email, name = excluded.name, updated_at = excluded.updated_at")

    def __init__(self, path: str):
        super().__init__()
        if path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        # One connection shared by the HTTP and consumer threads, guarded by a lock.
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()

    def _run(self, sql, params=(), fetch=False):
        with self._lock:
            cursor = self._conn.execute(sql, params)
            return [dict(r) for r in cursor.fetchall()] if fetch else []

    def _column_names(self):
        return {r["name"] for r in self._run("PRAGMA table_info(notifications)", fetch=True)}


class MySQLStore(_SQLStore):
    schema = (
        """CREATE TABLE IF NOT EXISTS notifications (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            type VARCHAR(50) NOT NULL,
            notification_id VARCHAR(200) NULL,
            recipient VARCHAR(320) NULL,
            subject VARCHAR(255) NULL,
            status VARCHAR(20) NOT NULL,
            attempt INT NULL,
            email_id VARCHAR(100) NULL,
            error TEXT NULL,
            created_at DATETIME(6) NOT NULL,
            INDEX idx_notifications_dedup (type, notification_id),
            INDEX idx_notifications_recipient (recipient)
        )""",
        """CREATE TABLE IF NOT EXISTS users (
            user_id VARCHAR(64) PRIMARY KEY,
            service_id VARCHAR(20) NULL,
            email VARCHAR(320) NOT NULL,
            name VARCHAR(255) NULL,
            updated_at DATETIME(6) NOT NULL,
            INDEX idx_users_service_id (service_id)
        )""",
    )
    save_user_sql = ("INSERT INTO users (user_id, service_id, email, name, updated_at) VALUES (?, ?, ?, ?, ?) "
                     "ON DUPLICATE KEY UPDATE service_id = VALUES(service_id), email = VALUES(email), "
                     "name = VALUES(name), updated_at = VALUES(updated_at)")
    add_attempt_column = "ALTER TABLE notifications ADD COLUMN attempt INT NULL AFTER status"

    def __init__(self, url: str):
        super().__init__()
        parsed = urlparse(url)
        self._params = {
            "host": parsed.hostname or "localhost",
            "port": parsed.port or 3306,
            "user": unquote(parsed.username or ""),
            "password": unquote(parsed.password or ""),
            "database": parsed.path.lstrip("/") or "notifications",
        }

    def _connect(self):
        import pymysql  # only needed when MySQL is used

        return pymysql.connect(**self._params, autocommit=True, connect_timeout=5,
                               cursorclass=pymysql.cursors.DictCursor, charset="utf8mb4")

    def _run(self, sql, params=(), fetch=False):
        # A short-lived connection per call: simple, and never stale after RDS restarts.
        with closing(self._connect()) as conn, conn.cursor() as cursor:
            cursor.execute(sql.replace("?", "%s"), params)
            return list(cursor.fetchall()) if fetch else []

    def _column_names(self):
        rows = self._run("SELECT COLUMN_NAME AS name FROM information_schema.COLUMNS "
                         "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'", fetch=True)
        return {r["name"] for r in rows}


def open_store(url: str) -> NotificationStore:
    if url == "memory":
        return InMemoryStore()
    if url.startswith("sqlite:///"):
        return SQLiteStore(url[len("sqlite:///"):])
    if url.startswith(("mysql://", "mysql+pymysql://")):
        return MySQLStore(url)
    raise ValueError(f"unsupported DATABASE_URL '{url}' (use sqlite:///path, mysql://... or memory)")
