"""Integration tests against a REAL RabbitMQ broker.

Skipped unless INTEGRATION_RABBITMQ_URL is set. The GitLab pipeline starts a
RabbitMQ container and sets it; locally you can run:

    docker run -d --rm -p 5672:5672 rabbitmq:3.13
    INTEGRATION_RABBITMQ_URL=amqp://guest:guest@localhost:5672/%2F python -m pytest tests/test_integration_rabbitmq.py

Each test uses its own uniquely named exchange and queues, and deletes them afterwards.
There is no dead-letter queue: messages that can never be delivered are logged,
recorded as 'failed' in the notification log, and dropped.
"""
import json
import os
import time
import unittest
import uuid

from app.config import Config
from app.email_sender import DryRunSender, EmailSendError
from app.samples import SAMPLE_PAYLOADS
from app.service import NotificationService

URL = os.environ.get("INTEGRATION_RABBITMQ_URL")


def wait_until(condition, timeout=30.0, interval=0.2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(interval)
    return False


class FlakySender(DryRunSender):
    def __init__(self, failures=0):
        super().__init__()
        self.failures, self.calls = failures, 0

    def send(self, email):
        self.calls += 1
        if self.calls <= self.failures:
            raise EmailSendError("Resend returned 503", permanent=False)
        return super().send(email)


@unittest.skipUnless(URL, "set INTEGRATION_RABBITMQ_URL to run against a real RabbitMQ")
class RealRabbitMQTests(unittest.TestCase):
    def setUp(self):
        import pika

        from app.consumer import NotificationConsumer

        self.pika = pika
        suffix = uuid.uuid4().hex[:8]
        self.config = Config(
            rabbitmq_url=URL,
            exchange=f"it-notifications-{suffix}",
            queue=f"it-notification-{suffix}",
            retry_delays_ms=(300, 600),  # 2 retries, each waiting longer, kept short for tests
            admin_email="admin@example.com",
        )
        self.service = NotificationService(self.config, FlakySender())
        self.consumer = NotificationConsumer(self.config, self.service)
        self.consumer.start()
        # The broker container may still be booting; the consumer keeps retrying.
        self.assertTrue(wait_until(lambda: self.consumer.state == "running", timeout=90),
                        f"consumer never connected (state={self.consumer.state})")
        self.connection = pika.BlockingConnection(pika.URLParameters(URL))
        self.channel = self.connection.channel()

    def tearDown(self):
        self.consumer.stop()
        self.consumer.join(timeout=10)
        for queue in [self.config.queue] + [name for name, _ in self.config.retry_queues]:
            self.channel.queue_delete(queue=queue)
        self.channel.exchange_delete(exchange=self.config.exchange)
        self.connection.close()

    # ---- helpers ----

    def publish(self, type_name, body):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.channel.basic_publish(
            exchange=self.config.exchange,
            routing_key=f"notify.{type_name}",
            body=body,
            properties=self.pika.BasicProperties(content_type="application/json"),
        )

    def queued(self, queue):
        return self.channel.queue_declare(queue=queue, passive=True).method.message_count

    def nothing_left_in_rabbitmq(self):
        queues = [self.config.queue] + [name for name, _ in self.config.retry_queues]
        return all(self.queued(q) == 0 for q in queues)

    def discarded(self):
        return [r for r in self.service.store.find() if (r["error"] or "").startswith("discarded")]

    # ---- tests ----

    def test_valid_message_is_emailed(self):
        self.publish("signup", SAMPLE_PAYLOADS["signup"])
        self.assertTrue(wait_until(lambda: len(self.service.sender.sent) == 1))
        self.assertEqual(self.service.sender.sent[0].to, "participant@example.com")

    def test_duplicate_message_is_emailed_once(self):
        self.publish("refund", SAMPLE_PAYLOADS["refund"])
        self.publish("refund", SAMPLE_PAYLOADS["refund"])
        self.assertTrue(wait_until(lambda: len(self.service.sender.sent) == 1))
        time.sleep(1.5)  # give the second copy time to be (not) sent
        self.assertEqual(len(self.service.sender.sent), 1)
        self.assertEqual(self.discarded(), [])

    def test_broken_message_is_logged_and_discarded(self):
        self.publish("signup", b"{not json")
        self.assertTrue(wait_until(lambda: len(self.discarded()) == 1))
        self.assertIn("not valid JSON", self.discarded()[0]["error"])
        self.assertTrue(wait_until(self.nothing_left_in_rabbitmq))  # not retried, not parked
        self.assertEqual(self.service.sender.sent, [])

    def test_temporary_failure_is_retried_then_sent(self):
        self.service.sender = FlakySender(failures=1)
        self.publish("refund", SAMPLE_PAYLOADS["refund"])
        self.assertTrue(wait_until(lambda: len(self.service.sender.sent) == 1, timeout=15))
        self.assertEqual(self.service.sender.calls, 2)
        self.assertEqual(self.discarded(), [])

    def test_gives_up_after_max_retries(self):
        self.service.sender = FlakySender(failures=99)
        self.publish("refund", SAMPLE_PAYLOADS["refund"])
        self.assertTrue(wait_until(lambda: len(self.discarded()) == 1, timeout=20))
        self.assertIn(f"gave up after {self.config.max_retries} retries", self.discarded()[0]["error"])
        # First attempt + each retry; no admin email, since this service has no DLQ.
        self.assertEqual(self.service.sender.calls, self.config.max_retries + 1)
        self.assertTrue(wait_until(self.nothing_left_in_rabbitmq))

if __name__ == "__main__":
    unittest.main()
