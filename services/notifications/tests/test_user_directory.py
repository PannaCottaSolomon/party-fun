"""Tests for the user directory: user.* messages from Authentication, and notifications sent by user_id."""
import json
import os
import tempfile
import unittest
from types import SimpleNamespace

from app import create_app
from app.config import Config
from app.consumer import NotificationConsumer
from app.contracts import UnknownRecipient, describe
from app.email_sender import DryRunSender
from app.messaging import (
    DISCARD,
    ORIGINAL_ROUTING_KEY_HEADER,
    RETRY,
    RETRY_COUNT_HEADER,
    process,
)
from app.service import NotificationService
from app.store import InMemoryStore, SQLiteStore, StoreError
from app.users import ACK, process_user_message, service_id
from tests.test_messaging_and_consumer import FakeChannel

SUB = "3f2a9c1e-8b7d-4e21-9a0f-5c6d7e8f9a0b"
CONFIG = Config(enable_consumer=False, admin_email="admin@example.com", retry_delays_ms=(1_000, 2_000))


def user_msg(**fields):
    return json.dumps({"id": SUB, "email": "Alex@Example.com", "fullName": "Alex Tan", "role": "PARTICIPANT",
                       "phoneNumber": "+65 8123 4567", **fields}).encode()


SIGNUP_BY_ID = {
    "notification_id": "signup-booking-7",
    "user_id": SUB,
    "event_title": "CCA Welcome Party",
    "event_date": "2026-11-14T20:00:00+08:00",
    "location": "SMU Connexion, Level 3",
    "registration_id": 7,
    "amount": 1500,
    "currency": "SGD",
}


class ServiceIdTests(unittest.TestCase):
    def test_matches_the_frontends_to_service_id(self):
        # Values computed with the frontend's toServiceId() (src/api/serviceIds.ts) in Node.
        self.assertEqual(service_id("sub-participant"), "1946168002")
        self.assertEqual(service_id(SUB), "1872460575")
        self.assertEqual(service_id(""), "1083068130")


class DirectoryStoreContract:
    def make_store(self):
        raise NotImplementedError

    def setUp(self):
        self.store = self.make_store()

    def test_save_find_update_delete(self):
        self.store.save_user(SUB, service_id(SUB), "Alex@Example.com", "Alex")
        by_sub, by_number = self.store.find_user(SUB), self.store.find_user(service_id(SUB))
        self.assertEqual((by_sub["email"], by_sub["name"]), ("alex@example.com", "Alex"))
        self.assertEqual(by_number["user_id"], SUB)
        self.store.save_user(SUB, service_id(SUB), "alex.tan@example.com", None)
        self.assertEqual(self.store.find_user(SUB)["email"], "alex.tan@example.com")
        self.store.delete_user(SUB)
        self.assertIsNone(self.store.find_user(SUB))
        self.store.delete_user(SUB)  # deleting twice is fine

    def test_unknown_user(self):
        self.assertIsNone(self.store.find_user("nobody"))


class InMemoryDirectoryTests(DirectoryStoreContract, unittest.TestCase):
    def make_store(self):
        return InMemoryStore()


class SQLiteDirectoryTests(DirectoryStoreContract, unittest.TestCase):
    def make_store(self):
        self.path = os.path.join(tempfile.mkdtemp(), "n.db")
        return SQLiteStore(self.path)

    def test_survives_a_restart(self):
        self.store.save_user(SUB, service_id(SUB), "alex@example.com", "Alex")
        self.assertEqual(SQLiteStore(self.path).find_user(SUB)["name"], "Alex")


class BrokenDirectory(InMemoryStore):
    def save_user(self, *_):
        raise StoreError("database unreachable")

    def delete_user(self, *_):
        raise StoreError("database unreachable")


class UserMessageTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryStore()

    def apply(self, key, raw, headers=None, store=None, max_retries=3):
        return process_user_message(store or self.store, key, raw, headers, max_retries)

    def test_created_updated_deleted(self):
        self.assertEqual(self.apply("user.created", user_msg()).action, ACK)
        self.assertEqual(self.store.find_user(SUB)["email"], "alex@example.com")
        self.apply("user.updated", user_msg(email="alex.tan@example.com", fullName="Alex T"))
        self.assertEqual(self.store.find_user(SUB)["name"], "Alex T")
        self.apply("user.deleted", json.dumps({"id": SUB}).encode())
        self.assertIsNone(self.store.find_user(SUB))

    def test_phone_number_is_not_stored(self):
        self.apply("user.created", user_msg())
        self.assertNotIn("+65 8123 4567", json.dumps(self.store.find_user(SUB)))

    def test_bad_messages_are_dropped(self):
        for key, raw in [("user.created", b"{nope"), ("user.created", user_msg(email="not-an-email")),
                         ("user.created", user_msg(id="")), ("user.renamed", user_msg()),
                         ("user.created", b"[1, 2]")]:
            with self.subTest(key=key, raw=raw):
                self.assertEqual(self.apply(key, raw).action, DISCARD)

    def test_database_down_retries_then_gives_up(self):
        broken = BrokenDirectory()
        first = self.apply("user.created", user_msg(), store=broken)
        self.assertEqual((first.action, first.retry_count), (RETRY, 1))
        last = self.apply("notifications.users", user_msg(), store=broken,
                          headers={RETRY_COUNT_HEADER: 3, ORIGINAL_ROUTING_KEY_HEADER: "user.created"})
        self.assertEqual(last.action, DISCARD)


class SendByUserIdTests(unittest.TestCase):
    def setUp(self):
        self.sender = DryRunSender()
        self.service = NotificationService(CONFIG, self.sender, InMemoryStore())
        self.service.store.save_user(SUB, service_id(SUB), "alex@example.com", "Alex Tan")

    def test_email_and_name_come_from_the_directory(self):
        self.assertEqual(self.service.notify("signup", SIGNUP_BY_ID).status, "sent")
        email = self.sender.sent[-1]
        self.assertEqual(email.to, "alex@example.com")
        self.assertIn("Alex Tan", email.html)
        self.assertEqual(self.service.store.find(email="alex@example.com")[0]["type"], "signup")

    def test_the_frontends_numeric_id_works_too(self):
        self.service.notify("signup", {**SIGNUP_BY_ID, "user_id": int(service_id(SUB))})
        self.assertEqual(self.sender.sent[-1].to, "alex@example.com")

    def test_a_name_in_the_message_wins(self):
        self.service.notify("signup", {**SIGNUP_BY_ID, "name": "Lex"})
        self.assertIn("Lex", self.sender.sent[-1].html)

    def test_an_email_in_the_message_still_works(self):
        self.service.notify("signup", {**SIGNUP_BY_ID, "user_id": None, "email": "old@example.com"})
        self.assertEqual(self.sender.sent[-1].to, "old@example.com")

    def test_unknown_user_is_retried_then_dropped(self):
        payload = json.dumps({**SIGNUP_BY_ID, "user_id": "someone-new"}).encode()
        with self.assertRaises(UnknownRecipient):
            self.service.notify("signup", json.loads(payload))
        first = process(self.service, "notify.signup", payload, {}, CONFIG.max_retries)
        self.assertEqual(first.action, RETRY)
        last = process(self.service, "notification_service", payload,
                       {RETRY_COUNT_HEADER: 2, ORIGINAL_ROUTING_KEY_HEADER: "notify.signup"}, CONFIG.max_retries)
        self.assertEqual(last.action, DISCARD)
        self.assertIn("not in the user directory", last.reason)

    def test_neither_email_nor_user_id_is_a_validation_error(self):
        payload = json.dumps({**SIGNUP_BY_ID, "user_id": None}).encode()
        decision = process(self.service, "notify.signup", payload, {}, CONFIG.max_retries)
        self.assertEqual(decision.action, DISCARD)
        self.assertIn("or send user_id", decision.reason)

    def test_discarded_row_shows_the_looked_up_recipient(self):
        body = json.dumps({**SIGNUP_BY_ID, "location": ""}).encode()  # invalid: location missing
        self.service.record_discarded("notify.signup", body, "validation failed")
        self.assertEqual(self.service.store.find(type_name="signup")[0]["recipient"], "alex@example.com")

    def test_contract_lists_user_id(self):
        signup = next(t for t in describe() if t["type"] == "signup")
        self.assertIn("user_id", signup["optional"])


class RedirectTests(unittest.TestCase):
    def test_every_email_goes_to_the_test_inbox(self):
        sender = DryRunSender()
        config = Config(enable_consumer=False, email_redirect_to="me@school.edu.sg")
        service = NotificationService(config, sender, InMemoryStore())
        service.store.save_user(SUB, service_id(SUB), "alex@example.com", "Alex")
        service.notify("signup", SIGNUP_BY_ID)
        email = sender.sent[-1]
        self.assertEqual(email.to, "me@school.edu.sg")
        self.assertTrue(email.subject.startswith("[to: alex@example.com] "))
        row = service.store.find()[0]
        self.assertEqual(row["recipient"], "alex@example.com")  # the log keeps the real recipient
        self.assertFalse(row["subject"].startswith("[to:"))

    def test_read_from_the_environment(self):
        self.assertEqual(Config.from_env({"EMAIL_REDIRECT_TO": " me@school.edu.sg "}).email_redirect_to,
                         "me@school.edu.sg")
        self.assertEqual(Config.from_env({}).email_redirect_to, "")


class HttpTests(unittest.TestCase):
    def test_unknown_recipient_is_422_and_retryable(self):
        service = NotificationService(CONFIG, DryRunSender(), InMemoryStore())
        client = create_app(CONFIG, service).test_client()
        response = client.post("/notify_signup", json={**SIGNUP_BY_ID, "user_id": "someone-new"})
        self.assertEqual(response.status_code, 422)
        self.assertTrue(response.get_json()["retryable"])


class ConsumerTests(unittest.TestCase):
    def deliver(self, consumer, channel, key, raw, headers=None):
        method = SimpleNamespace(routing_key=key, delivery_tag=1)
        props = SimpleNamespace(headers=headers, content_type="application/json", message_id=None)
        consumer.on_user_message(channel, method, props, raw)

    def test_user_message_is_applied_and_acked(self):
        service = NotificationService(CONFIG, DryRunSender(), InMemoryStore())
        channel = FakeChannel()
        self.deliver(NotificationConsumer(CONFIG, service), channel, "user.created", user_msg())
        self.assertEqual((channel.acks, channel.published), ([1], []))
        self.assertIsNotNone(service.store.find_user(SUB))

    def test_database_down_parks_a_copy_in_the_retry_queue(self):
        service = NotificationService(CONFIG, DryRunSender(), BrokenDirectory())
        channel = FakeChannel()
        self.deliver(NotificationConsumer(CONFIG, service), channel, "user.created", user_msg(),
                     headers={"traceparent": "00-abc-01"})
        msg = channel.published[-1]
        self.assertEqual(msg.routing_key, "notifications.users.retry.30s")
        self.assertEqual(msg.properties.headers[ORIGINAL_ROUTING_KEY_HEADER], "user.created")
        self.assertEqual(msg.properties.headers["traceparent"], "00-abc-01")
        self.assertEqual(channel.acks, [1])

    def test_topology_binds_user_events(self):
        channel = FakeChannel()
        NotificationConsumer(CONFIG, NotificationService(CONFIG, DryRunSender())).declare_topology(channel)
        binds = {(kw["exchange"], kw["routing_key"]) for kind, kw in channel.declared if kind == "bind"}
        self.assertIn(("user.events", "user.*"), binds)
        exchanges = {kw["exchange"]: kw for kind, kw in channel.declared if kind == "exchange"}
        self.assertEqual((exchanges["user.events"]["exchange_type"], exchanges["user.events"]["durable"]),
                         ("topic", True))

    def test_user_directory_can_be_switched_off(self):
        channel = FakeChannel()
        config = Config(enable_consumer=False, user_directory_enabled=False)
        NotificationConsumer(config, NotificationService(config, DryRunSender())).declare_topology(channel)
        self.assertNotIn("notifications.users", {kw.get("queue") for _, kw in channel.declared})


if __name__ == "__main__":
    unittest.main()
