"""Tests for the RabbitMQ side, using a fake channel (no broker needed).

If the real `pika` package isn't installed, tests/__init__.py puts a tiny
stand-in in its place so the consumer's logic can still be tested.
"""
import json
import unittest
from types import SimpleNamespace

from pika.exceptions import UnroutableError

from app.config import Config
from app.consumer import NotificationConsumer
from app.email_sender import DryRunSender, EmailSendError
from app.messaging import (
    ACK,
    DISCARD,
    ORIGINAL_ROUTING_KEY_HEADER,
    RETRY,
    RETRY_COUNT_HEADER,
    process,
)
from app.samples import SAMPLE_PAYLOADS
from app.service import NotificationService

CONFIG = Config(admin_email="admin@example.com", retry_delays_ms=(1_000, 2_000))  # 2 retries


class FlakySender(DryRunSender):
    """Fails `failures` times with the given error type, then succeeds."""

    def __init__(self, failures, permanent=False):
        super().__init__()
        self.failures, self.permanent, self.calls = failures, permanent, 0

    def send(self, email):
        self.calls += 1
        if self.calls <= self.failures:
            raise EmailSendError("Resend returned 503", permanent=self.permanent)
        return super().send(email)


def body(name):
    return json.dumps(SAMPLE_PAYLOADS[name]).encode()


class ProcessTests(unittest.TestCase):
    def service(self, sender=None):
        return NotificationService(CONFIG, sender or DryRunSender())

    def test_success_acks(self):
        self.assertEqual(process(self.service(), "notify.refund", body("refund"), {}, 2).action, ACK)

    def test_duplicate_acks_without_resending(self):
        sender = DryRunSender()
        svc = self.service(sender)
        process(svc, "notify.refund", body("refund"), {}, 2)
        self.assertEqual(process(svc, "notify.refund", body("refund"), {}, 2).action, ACK)
        self.assertEqual(len(sender.sent), 1)

    def test_permanent_problems_are_discarded_without_retrying(self):
        cases = [
            ("notify.party", body("refund")),             # unknown type
            ("notify.refund", b"{not json"),              # bad JSON
            ("notify.refund", b'{"email": "a@b.com"}'),   # missing fields
        ]
        for key, raw in cases:
            with self.subTest(key=key, raw=raw):
                self.assertEqual(process(self.service(), key, raw, {}, 2).action, DISCARD)

    def test_rejected_email_is_discarded(self):
        decision = process(self.service(FlakySender(1, permanent=True)), "notify.refund", body("refund"), {}, 2)
        self.assertEqual(decision.action, DISCARD)

    def test_temporary_failure_retries_then_gives_up(self):
        svc = self.service(FlakySender(failures=99))
        first = process(svc, "notify.refund", body("refund"), {}, max_retries=2)
        self.assertEqual((first.action, first.retry_count), (RETRY, 1))
        last = process(svc, "notification_service", body("refund"),
                       {RETRY_COUNT_HEADER: 2, ORIGINAL_ROUTING_KEY_HEADER: "notify.refund"}, max_retries=2)
        self.assertEqual(last.action, DISCARD)
        self.assertIn("gave up after 2 retries", last.reason)

    def test_original_routing_key_header_wins(self):
        decision = process(self.service(), "notification_service", body("signup"),
                           {ORIGINAL_ROUTING_KEY_HEADER: b"notify.signup", RETRY_COUNT_HEADER: 1}, 2)
        self.assertEqual((decision.action, decision.routing_key), (ACK, "notify.signup"))

    def test_unexpected_exception_is_retried(self):
        class Broken(DryRunSender):
            def send(self, email):
                raise RuntimeError("bug")
        self.assertEqual(process(self.service(Broken()), "notify.refund", body("refund"), {}, 2).action, RETRY)


class FakeChannel:
    def __init__(self, publish_error=None):
        self.acks, self.nacks, self.published, self.declared = [], [], [], []
        self.publish_error = publish_error

    def exchange_declare(self, **kw):
        self.declared.append(("exchange", kw))

    def queue_declare(self, **kw):
        self.declared.append(("queue", kw))

    def queue_bind(self, **kw):
        self.declared.append(("bind", kw))

    def basic_ack(self, delivery_tag):
        self.acks.append(delivery_tag)

    def basic_nack(self, delivery_tag, requeue):
        self.nacks.append((delivery_tag, requeue))

    def basic_publish(self, exchange, routing_key, body, properties, mandatory=False):
        if self.publish_error:
            raise self.publish_error
        self.published.append(SimpleNamespace(exchange=exchange, routing_key=routing_key,
                                              body=body, properties=properties))


def deliver(consumer, channel, routing_key, raw, headers=None, tag=1):
    method = SimpleNamespace(routing_key=routing_key, delivery_tag=tag)
    props = SimpleNamespace(headers=headers, content_type="application/json", message_id=None)
    consumer.on_message(channel, method, props, raw)


class ConsumerTests(unittest.TestCase):
    def consumer(self, sender=None):
        return NotificationConsumer(CONFIG, NotificationService(CONFIG, sender or DryRunSender()))

    def test_topology(self):
        channel = FakeChannel()
        self.consumer().declare_topology(channel)
        queues = {kw["queue"]: kw for kind, kw in channel.declared if kind == "queue"}
        # One retry queue per delay, and no dead-letter queue (Payment owns the team's DLQ),
        # plus the user directory's queue and its retry queue.
        self.assertEqual(set(queues), {"notification_service", "notification_service.retry.1s",
                                       "notification_service.retry.2s",
                                       "notifications.users", "notifications.users.retry.30s"})
        for name, ttl in (("notification_service.retry.1s", 1_000), ("notification_service.retry.2s", 2_000)):
            args = queues[name]["arguments"]
            self.assertEqual(args["x-message-ttl"], ttl)
            self.assertEqual(args["x-dead-letter-routing-key"], "notification_service")
        binds = [kw for kind, kw in channel.declared if kind == "bind"]
        self.assertEqual(binds[0]["routing_key"], "notify.#")

    def test_success_is_acked(self):
        channel = FakeChannel()
        deliver(self.consumer(), channel, "notify.signup", body("signup"))
        self.assertEqual((channel.acks, channel.published), ([1], []))

    def test_full_retry_cycle_ends_with_the_message_discarded(self):
        """Simulates RabbitMQ: whatever goes to the retry queue comes back to the main queue."""
        sender = FlakySender(failures=99)
        consumer, channel = self.consumer(sender), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"), headers={"traceparent": "00-abc-01"})
        # Each retry waits longer: retry 1 -> 1s queue, retry 2 -> 2s queue.
        for expected_queue in ("notification_service.retry.1s", "notification_service.retry.2s"):
            msg = channel.published[-1]
            self.assertEqual(msg.routing_key, expected_queue)
            self.assertEqual(msg.properties.headers[ORIGINAL_ROUTING_KEY_HEADER], "notify.refund")
            self.assertEqual(msg.properties.headers["traceparent"], "00-abc-01")  # tracing survives
            deliver(consumer, channel, "notification_service", msg.body, msg.properties.headers)
        # After the last retry nothing more is published anywhere: the message is dropped.
        self.assertEqual(len(channel.published), CONFIG.max_retries)
        self.assertEqual(sender.calls, CONFIG.max_retries + 1)  # first attempt + each retry, no admin email
        self.assertEqual(len(channel.acks), CONFIG.max_retries + 1)

    def test_recovers_after_a_retry(self):
        sender = FlakySender(failures=1)
        consumer, channel = self.consumer(sender), FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"))
        retry = channel.published[-1]
        deliver(consumer, channel, "notification_service", retry.body, retry.properties.headers)
        self.assertEqual(len(sender.sent), 1)
        self.assertEqual(len(channel.published), 1)  # only the one retry

    def test_bad_message_is_discarded_straight_away(self):
        channel = FakeChannel()
        deliver(self.consumer(), channel, "notify.signup", b"{broken")
        self.assertEqual(channel.published, [])  # not retried, not parked anywhere
        self.assertEqual(channel.acks, [1])

    def test_if_moving_to_the_retry_queue_fails_it_is_requeued_not_lost(self):
        channel = FakeChannel(publish_error=UnroutableError([]))
        deliver(self.consumer(FlakySender(failures=1)), channel, "notify.refund", body("refund"))
        self.assertEqual(channel.acks, [])
        self.assertEqual(channel.nacks, [(1, True)])


if __name__ == "__main__":
    unittest.main()
