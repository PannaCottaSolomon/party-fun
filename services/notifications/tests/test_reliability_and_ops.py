"""Tests for the notification log, API key, discarded messages, graceful shutdown and JSON logs."""
import io
import json
import logging
import os
import tempfile
import unittest
from types import SimpleNamespace

from pika.exceptions import UnroutableError

from app import create_app, logging_setup
from app.config import Config
from app.consumer import NotificationConsumer
from app.email_sender import DryRunSender, EmailSendError
from app.main import stop_consumer
from app.messaging import RETRY, process
from app.samples import SAMPLE_PAYLOADS
from app.service import NotificationService
from app.store import (
    InMemoryStore,
    LogEntry,
    MySQLStore,
    SQLiteStore,
    StoreError,
    open_store,
)
from tests.test_messaging_and_consumer import FakeChannel, FlakySender, body, deliver

CONFIG = Config(enable_consumer=False, admin_email="admin@example.com", retry_delays_ms=(1_000,))


class BrokenStore:
    """A notification log whose database is down."""

    def already_sent(self, *_):
        raise StoreError("database unreachable")

    def record(self, *_):
        raise StoreError("database unreachable")

    def find(self, **_):
        raise StoreError("database unreachable")


# ------------------------------------------------------------------- stores

class StoreContract:
    """Same tests run against every store implementation."""

    def make_store(self):
        raise NotImplementedError

    def setUp(self):
        self.store = self.make_store()

    def test_records_and_finds_newest_first(self):
        self.store.record(LogEntry("signup", "sent", "n1", "Alex@Example.com", "Hi", "em_1"))
        self.store.record(LogEntry("refund", "failed", "n2", "alex@example.com", "Refund", error="503"))
        self.store.record(LogEntry("refund", "sent", "n3", "sam@example.com", "Refund", "em_3"))
        rows = self.store.find(email="ALEX@example.com")
        self.assertEqual([r["notification_id"] for r in rows], ["n2", "n1"])
        self.assertEqual(rows[0]["status"], "failed")
        self.assertEqual(rows[0]["recipient"], "alex@example.com")
        self.assertIsInstance(rows[0]["created_at"], str)

    def test_attempt_number_is_stored(self):
        self.store.record(LogEntry("refund", "retrying", "n1", "a@b.com", error="503", attempt=1))
        self.store.record(LogEntry("refund", "sent", "n1", "a@b.com", attempt=2))
        self.store.record(LogEntry("signup", "sent", "n2", "a@b.com"))
        self.assertEqual([r["attempt"] for r in self.store.find()], [None, 2, 1])
        self.assertFalse(self.store.already_sent("refund", "n9"))

    def test_filters_and_limit(self):
        for i in range(5):
            self.store.record(LogEntry("refund", "sent", f"r{i}", "a@b.com"))
        self.store.record(LogEntry("signup", "sent", "s1", "a@b.com"))
        self.assertEqual(len(self.store.find(type_name="refund")), 5)
        self.assertEqual(len(self.store.find(limit=2)), 2)
        self.assertEqual(self.store.find(notification_id="s1")[0]["type"], "signup")

    def test_already_sent_only_counts_successful_sends(self):
        self.store.record(LogEntry("refund", "failed", "x"))
        self.assertFalse(self.store.already_sent("refund", "x"))
        self.store.record(LogEntry("refund", "sent", "x"))
        self.assertTrue(self.store.already_sent("refund", "x"))
        self.assertFalse(self.store.already_sent("signup", "x"))


class InMemoryStoreTests(StoreContract, unittest.TestCase):
    def make_store(self):
        return InMemoryStore()


class SQLiteStoreTests(StoreContract, unittest.TestCase):
    def make_store(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        return SQLiteStore(os.path.join(self.tmp.name, "sub", "notifications.db"))

    def test_survives_a_restart(self):
        path = os.path.join(self.tmp.name, "sub", "notifications.db")
        self.store.record(LogEntry("refund", "sent", "r1", "a@b.com"))
        reopened = SQLiteStore(path)
        self.assertTrue(reopened.already_sent("refund", "r1"))

    def test_adds_the_attempt_column_to_an_older_table(self):
        import sqlite3

        path = os.path.join(self.tmp.name, "old.db")
        with sqlite3.connect(path) as conn:
            conn.execute("CREATE TABLE notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, "
                         "notification_id TEXT, recipient TEXT, subject TEXT, status TEXT NOT NULL, "
                         "email_id TEXT, error TEXT, created_at TEXT NOT NULL)")
            conn.execute("INSERT INTO notifications (type, status, created_at) VALUES ('refund', 'sent', 'x')")
        store = SQLiteStore(path)
        store.record(LogEntry("refund", "sent", "r2", attempt=2))
        self.assertEqual([r["attempt"] for r in store.find()], [2, None])
        SQLiteStore(path).find()  # opening it again doesn't try to add the column twice

    def test_database_errors_become_store_errors(self):
        self.store._conn.close()
        with self.assertRaises(StoreError):
            self.store.find()


class OpenStoreTests(unittest.TestCase):
    def test_urls(self):
        self.assertIsInstance(open_store("memory"), InMemoryStore)
        self.assertIsInstance(open_store("sqlite:///:memory:"), SQLiteStore)
        mysql = open_store("mysql://user:p%40ss@db.example.com:3307/notif")
        self.assertIsInstance(mysql, MySQLStore)
        self.assertEqual(mysql._params, {"host": "db.example.com", "port": 3307, "user": "user",
                                         "password": "p@ss", "database": "notif"})
        with self.assertRaises(ValueError):
            open_store("postgres://x")


# ------------------------------------------------------------------ service

class ServiceLogTests(unittest.TestCase):
    def test_sent_and_failed_emails_are_logged(self):
        store = InMemoryStore()
        NotificationService(CONFIG, DryRunSender(), store).notify("refund", SAMPLE_PAYLOADS["refund"])
        with self.assertRaises(EmailSendError):
            NotificationService(CONFIG, FlakySender(failures=1), store).notify(
                "signup", SAMPLE_PAYLOADS["signup"])
        statuses = {r["type"]: r["status"] for r in store.find()}
        self.assertEqual(statuses, {"refund": "sent", "signup": "failed"})

    def test_duplicate_check_is_shared_between_replicas_and_restarts(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "n.db")
            first, second = DryRunSender(), DryRunSender()
            NotificationService(CONFIG, first, SQLiteStore(path)).notify("refund", SAMPLE_PAYLOADS["refund"])
            result = NotificationService(CONFIG, second, SQLiteStore(path)).notify(
                "refund", SAMPLE_PAYLOADS["refund"])
            self.assertEqual(result.status, "duplicate")
            self.assertEqual((len(first.sent), len(second.sent)), (1, 0))

    def test_database_down_means_retry_later(self):
        service = NotificationService(CONFIG, DryRunSender(), BrokenStore())
        with self.assertRaises(StoreError):
            service.notify("refund", SAMPLE_PAYLOADS["refund"])
        body = json.dumps(SAMPLE_PAYLOADS["refund"]).encode()
        self.assertEqual(process(service, "notify.refund", body, {}, 3).action, RETRY)

    def test_logging_failure_after_sending_does_not_raise(self):
        class WriteFails(InMemoryStore):
            def record(self, entry):
                raise StoreError("disk full")
        sender = DryRunSender()
        result = NotificationService(CONFIG, sender, WriteFails()).notify("refund", SAMPLE_PAYLOADS["refund"])
        self.assertEqual(result.status, "sent")  # the email went out; a retry would send it twice

    def test_record_discarded_extracts_what_it_can(self):
        store = InMemoryStore()
        service = NotificationService(CONFIG, DryRunSender(), store)
        service.record_discarded("notify.refund", json.dumps(SAMPLE_PAYLOADS["refund"]).encode(), "bad")
        service.record_discarded("notify.signup", b"{broken", "not JSON")
        rows = store.find()
        self.assertEqual(rows[1]["recipient"], "participant@example.com")
        self.assertEqual(rows[0]["status"], "failed")
        self.assertEqual(rows[0]["error"], "discarded: not JSON")
        self.assertIsNone(rows[0]["recipient"])


# --------------------------------------------------------------------- HTTP

class ApiKeyTests(unittest.TestCase):
    def setUp(self):
        config = Config(enable_consumer=False, admin_email="admin@example.com", api_key="s3cret")
        self.client = create_app(config, NotificationService(config, DryRunSender())).test_client()

    def test_notify_requires_the_key(self):
        payload = SAMPLE_PAYLOADS["refund"]
        self.assertEqual(self.client.post("/notify_refund", json=payload).status_code, 401)
        wrong = self.client.post("/notify_refund", json=payload, headers={"X-API-Key": "nope"})
        self.assertEqual(wrong.status_code, 401)
        ok = self.client.post("/notify_refund", json=payload, headers={"X-API-Key": "s3cret"})
        self.assertEqual(ok.status_code, 200)

    def test_log_requires_the_key(self):
        self.assertEqual(self.client.get("/notifications").status_code, 401)
        self.assertEqual(self.client.get("/notifications", headers={"X-API-Key": "s3cret"}).status_code, 200)

    def test_health_and_contract_stay_open(self):
        for path in ("/health", "/ready", "/notification-types"):
            self.assertEqual(self.client.get(path).status_code, 200, path)


class NotificationLogEndpointTests(unittest.TestCase):
    def setUp(self):
        self.service = NotificationService(CONFIG, DryRunSender())
        self.app = create_app(CONFIG, self.service)
        self.client = self.app.test_client()

    def test_did_alex_get_the_email(self):
        self.client.post("/notify_refund", json=dict(SAMPLE_PAYLOADS["refund"], email="alex@example.com"))
        self.client.post("/notify_signup", json=SAMPLE_PAYLOADS["signup"])
        body = self.client.get("/notifications?email=Alex@example.com").get_json()
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["notifications"][0]["type"], "refund")
        self.assertEqual(body["notifications"][0]["status"], "sent")

    def test_bad_limit(self):
        self.assertEqual(self.client.get("/notifications?limit=abc").status_code, 400)

    def test_database_down(self):
        app = create_app(CONFIG, NotificationService(CONFIG, DryRunSender(), BrokenStore()))
        client = app.test_client()
        self.assertEqual(client.get("/notifications").status_code, 503)
        self.assertEqual(client.post("/notify_refund", json=SAMPLE_PAYLOADS["refund"]).status_code, 503)


# ----------------------------------------------------------------- consumer

class DiscardedMessageTests(unittest.TestCase):
    """Without a DLQ, messages that can never be delivered are logged and dropped."""

    def consumer(self, sender=None, config=CONFIG):
        self.store = InMemoryStore()
        self.sender = sender or DryRunSender()
        return NotificationConsumer(config, NotificationService(config, self.sender, self.store))

    def test_discarded_message_is_recorded_as_failed_and_acked(self):
        consumer, channel = self.consumer(), FakeChannel()
        deliver(consumer, channel, "notify.refund", json.dumps({"email": "alex@example.com"}).encode())
        self.assertEqual((channel.acks, channel.published), ([1], []))
        rows = self.store.find(email="alex@example.com")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["status"], "failed")
        self.assertIn("discarded", rows[0]["error"])

    def attempts(self):
        """(attempt, status) for each row, oldest first."""
        return [(r["attempt"], r["status"]) for r in reversed(self.store.find())]

    def test_email_dropped_after_all_retries_has_a_row_per_attempt(self):
        config = Config(enable_consumer=False, admin_email="admin@example.com", retry_delays_ms=(1, 2, 3))
        consumer, channel = self.consumer(FlakySender(failures=99), config), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"))
        for _ in range(config.max_retries):
            msg = channel.published[-1]
            deliver(consumer, channel, "notification_service", msg.body, msg.properties.headers)
        self.assertEqual(self.attempts(), [(1, "retrying"), (2, "retrying"), (3, "retrying"), (4, "failed")])
        rows = self.store.find()
        self.assertTrue(rows[0]["error"].startswith("discarded: gave up after 3 retries"))
        self.assertEqual(rows[1]["error"], "Resend returned 503")
        # Rows built from the message still get the subject, since the payload is valid.
        self.assertTrue(all(r["subject"] for r in rows))

    def test_one_log_line_per_attempt_with_matching_numbers(self):
        config = Config(enable_consumer=False, admin_email="admin@example.com")  # default 30s, 2m, 10m
        consumer, channel = self.consumer(FlakySender(failures=99), config), FakeChannel()
        with self.assertLogs("app", level="WARNING") as logs:
            deliver(consumer, channel, "notify.refund", body("refund"))
            for _ in range(config.max_retries):
                msg = channel.published[-1]
                deliver(consumer, channel, "notification_service", msg.body, msg.properties.headers)
        self.assertEqual([r.getMessage() for r in logs.records], [
            ("attempt 1 of 4 failed for notify.refund refund-reg-1001, retrying in 30s via "
             "notification_service.retry.30s: Resend returned 503"),
            ("attempt 2 of 4 failed for notify.refund refund-reg-1001, retrying in 2m via "
             "notification_service.retry.2m: Resend returned 503"),
            ("attempt 3 of 4 failed for notify.refund refund-reg-1001, retrying in 10m via "
             "notification_service.retry.10m: Resend returned 503"),
            ("discarding notify.refund refund-reg-1001 after attempt 4 of 4: "
             "gave up after 3 retries: Resend returned 503"),
        ])
        self.assertEqual(logs.records[-1].levelname, "ERROR")  # what the CloudWatch alarm matches

    def test_email_sent_on_a_retry(self):
        consumer, channel = self.consumer(FlakySender(failures=1)), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"))
        msg = channel.published[-1]
        deliver(consumer, channel, "notification_service", msg.body, msg.properties.headers)
        self.assertEqual(self.attempts(), [(1, "retrying"), (2, "sent")])

    def test_email_sent_first_time_is_attempt_1(self):
        consumer, channel = self.consumer(), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"))
        self.assertEqual(self.attempts(), [(1, "sent")])

    def test_permanently_rejected_email_has_one_row(self):
        class Rejects(DryRunSender):
            def send(self, email):
                raise EmailSendError("Resend returned 422: invalid address", permanent=True, status=422)

        consumer, channel = self.consumer(Rejects()), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"))
        self.assertEqual(self.attempts(), [(1, "failed")])
        self.assertTrue(self.store.find()[0]["error"].startswith("discarded: email rejected"))

    def test_no_row_for_an_attempt_whose_retry_could_not_be_queued(self):
        """If the retry can't be queued the message is redelivered, so the attempt is logged then, once."""
        consumer, channel = self.consumer(FlakySender(failures=99)), FakeChannel(publish_error=UnroutableError([]))
        deliver(consumer, channel, "notify.refund", body("refund"))
        self.assertEqual(self.attempts(), [])

    def test_no_admin_email_for_discarded_messages(self):
        consumer, channel = self.consumer(), FakeChannel()
        deliver(consumer, channel, "notify.signup", b"{broken")
        self.assertEqual(self.sender.sent, [])

    def test_log_failure_does_not_break_the_consumer(self):
        consumer, channel = self.consumer(), FakeChannel()
        consumer.service.store = BrokenStore()
        deliver(consumer, channel, "notify.signup", b"{broken")
        self.assertEqual(channel.acks, [1])

    def test_admin_alert_type_still_works_for_payments_dlq(self):
        # The Payment service publishes notify.admin_alert when its own DLQ catches something.
        consumer, channel = self.consumer(), FakeChannel()
        deliver(consumer, channel, "notify.admin_alert", json.dumps(SAMPLE_PAYLOADS["admin_alert"]).encode())
        self.assertEqual(channel.acks, [1])
        self.assertEqual(self.sender.sent[0].to, "admin@example.com")


class GracefulShutdownTests(unittest.TestCase):
    def test_messages_arriving_during_shutdown_go_back_to_the_queue(self):
        sender = DryRunSender()
        consumer = NotificationConsumer(CONFIG, NotificationService(CONFIG, sender))
        consumer.stop()
        channel = FakeChannel()
        deliver(consumer, channel, "notify.refund", json.dumps(SAMPLE_PAYLOADS["refund"]).encode())
        self.assertEqual(channel.nacks, [(1, True)])
        self.assertEqual((channel.acks, sender.sent), ([], []))
        self.assertEqual(consumer.state, "stopping")

    def test_ready_reports_not_ready_while_stopping(self):
        app = create_app(CONFIG, NotificationService(CONFIG, DryRunSender()))
        app.extensions["notification_consumer"] = SimpleNamespace(state="stopping")
        self.assertEqual(app.test_client().get("/ready").status_code, 503)

    def test_stop_consumer_waits_for_it(self):
        class Fake:
            def __init__(self, finishes):
                self.finishes, self.stopped, self.waited = finishes, False, None

            def stop(self):
                self.stopped = True

            def join(self, timeout):
                self.waited = timeout

            def is_alive(self):
                return not self.finishes

        quick, stuck = Fake(True), Fake(False)
        self.assertTrue(stop_consumer(quick, 5))
        self.assertTrue(quick.stopped)
        self.assertEqual(quick.waited, 5)
        self.assertFalse(stop_consumer(stuck, 1))
        self.assertTrue(stop_consumer(None, 1))


# ------------------------------------------------------------------ logging

class JsonLoggingTests(unittest.TestCase):
    def record(self, msg="hello %s", args=("world",), exc_info=None):
        return logging.LogRecord("app.test", logging.INFO, __file__, 1, msg, args, exc_info)

    def test_json_line(self):
        entry = json.loads(logging_setup.JsonFormatter().format(self.record()))
        self.assertEqual(entry["message"], "hello world")
        self.assertEqual(entry["level"], "INFO")
        self.assertEqual(entry["service"], "notifications")
        self.assertIn("time", entry)

    def test_trace_ids_are_included_when_tracing(self):
        context = SimpleNamespace(is_valid=True, trace_id=0xABC, span_id=0x12)
        fake_trace = SimpleNamespace(get_current_span=lambda: SimpleNamespace(get_span_context=lambda: context))
        original = logging_setup._otel_trace
        logging_setup._otel_trace = fake_trace
        try:
            entry = json.loads(logging_setup.JsonFormatter().format(self.record()))
        finally:
            logging_setup._otel_trace = original
        self.assertEqual(entry["trace_id"], format(0xABC, "032x"))
        self.assertEqual(entry["span_id"], format(0x12, "016x"))

    def test_exceptions_are_included(self):
        try:
            raise ValueError("boom")
        except ValueError:
            import sys
            entry = json.loads(logging_setup.JsonFormatter().format(self.record(exc_info=sys.exc_info())))
        self.assertIn("ValueError: boom", entry["exception"])

    def test_setup_logging_switches_format(self):
        root = logging.getLogger()
        saved = root.handlers[:], root.level
        try:
            logging_setup.setup_logging("INFO", "json")
            stream = io.StringIO()
            root.handlers[0].stream = stream
            logging.getLogger("x").info("hi")
            self.assertEqual(json.loads(stream.getvalue())["message"], "hi")
        finally:
            root.handlers[:], level = saved
            root.setLevel(level)


if __name__ == "__main__":
    unittest.main()
