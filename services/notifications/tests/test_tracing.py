"""Trace span details: type, id, attempt, outcome and severity on each message's span."""
import json
import unittest
from unittest import mock

from app import tracing
from app.config import Config
from app.consumer import NotificationConsumer
from app.email_sender import DryRunSender, EmailSendError
from app.messaging import process
from app.service import NotificationService
from app.store import InMemoryStore
from tests.test_messaging_and_consumer import FakeChannel, FlakySender, body, deliver

CONFIG = Config(enable_consumer=False, admin_email="admin@example.com", retry_delays_ms=(1_000,))

try:
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
        InMemorySpanExporter,
    )
    HAVE_SDK = True
except ImportError:  # only the API is installed locally; CI has the SDK (opentelemetry-distro)
    HAVE_SDK = False


class FakeSpan:
    def __init__(self):
        self.attributes, self.status = {}, None

    def is_recording(self):
        return True

    def set_attribute(self, key, value):
        self.attributes[key] = value

    def set_status(self, status):
        self.status = status


class Rejects(DryRunSender):
    def __init__(self, status):
        super().__init__()
        self.status = status

    def send(self, email):
        raise EmailSendError(f"Resend returned {self.status}: no (alex@example.com)", permanent=True, status=self.status)


def run(sender, headers=None, routing_key="notify.refund", raw=None):
    """Process one message, the way the consumer does, and return its span."""
    service = NotificationService(CONFIG, sender, InMemoryStore())
    span = FakeSpan()
    decision = process(service, routing_key, raw or body("refund"), headers, CONFIG.max_retries)
    tracing.annotate(decision, span)
    return span


class AnnotateTests(unittest.TestCase):
    def test_sent_first_time_is_info(self):
        span = run(DryRunSender())
        self.assertEqual(span.attributes, {
            "notification.type": "refund", "notification.id": "refund-reg-1001", "notification.attempt": 1,
            "notification.retried": False, "notification.outcome": "sent", "severity": "info",
        })
        self.assertIsNone(span.status)

    def test_retry_is_a_warning_and_not_marked_failed(self):
        span = run(FlakySender(failures=1))
        self.assertEqual(span.attributes["notification.outcome"], "retrying")
        self.assertEqual(span.attributes["severity"], "warning")
        self.assertEqual(span.attributes["notification.reason"], "Resend returned 503")
        self.assertIsNone(span.status)

    def test_sent_after_a_retry_is_a_warning(self):
        span = run(DryRunSender(), headers={"x-retry-count": 1})
        self.assertEqual((span.attributes["notification.attempt"], span.attributes["notification.retried"],
                          span.attributes["notification.outcome"], span.attributes["severity"]),
                         (2, True, "sent", "warning"))

    def test_discarded_is_an_error_and_marks_the_span_failed(self):
        span = run(FlakySender(failures=99), headers={"x-retry-count": 1})
        self.assertEqual(span.attributes["notification.outcome"], "discarded")
        self.assertEqual(span.attributes["severity"], "error")
        self.assertIsNotNone(span.status)

    def test_rejected_api_key_is_critical(self):
        self.assertEqual(run(Rejects(401)).attributes["severity"], "critical")
        self.assertEqual(run(Rejects(422)).attributes["severity"], "error")

    def test_no_email_address_reaches_the_span(self):
        span = run(Rejects(422))
        self.assertNotIn("alex@example.com", json.dumps(span.attributes))
        self.assertIn("***@example.com", span.attributes["notification.reason"])

    def test_duplicate_is_info(self):
        service = NotificationService(CONFIG, DryRunSender(), InMemoryStore())
        process(service, "notify.refund", body("refund"), None, 1)
        span = FakeSpan()
        tracing.annotate(process(service, "notify.refund", body("refund"), None, 1), span)
        self.assertEqual((span.attributes["notification.outcome"], span.attributes["severity"]), ("duplicate", "info"))

    def test_broken_message_still_gets_a_type(self):
        span = run(DryRunSender(), routing_key="notify.signup", raw=b"{broken")
        self.assertEqual((span.attributes["notification.type"], span.attributes["notification.outcome"]),
                         ("signup", "discarded"))
        self.assertNotIn("notification.id", span.attributes)

    def test_does_nothing_without_a_recording_span(self):
        class Off(FakeSpan):
            def is_recording(self):
                return False
        span = Off()
        tracing.annotate(process(NotificationService(CONFIG, DryRunSender(), InMemoryStore()), "notify.refund",
                                 body("refund"), None, 1), span)
        self.assertEqual(span.attributes, {})

    def test_consumer_annotates_the_active_span(self):
        span = FakeSpan()
        consumer = NotificationConsumer(CONFIG, NotificationService(CONFIG, DryRunSender(), InMemoryStore()))
        with mock.patch("app.tracing.current_span", return_value=span):
            deliver(consumer, FakeChannel(), "notify.refund", body("refund"))
        self.assertEqual(span.attributes["notification.outcome"], "sent")


@unittest.skipUnless(HAVE_SDK, "opentelemetry-sdk not installed")
class RealSpanTests(unittest.TestCase):
    """With the real OpenTelemetry SDK: what Jaeger would receive."""

    def test_span_attributes_and_status(self):
        exporter = InMemorySpanExporter()
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(exporter))
        tracer = provider.get_tracer("test")
        consumer = NotificationConsumer(CONFIG, NotificationService(CONFIG, FlakySender(failures=99), InMemoryStore()))
        with tracer.start_as_current_span("notification_service receive"):
            deliver(consumer, FakeChannel(), "notify.refund", body("refund"), headers={"x-retry-count": 1})
        (span,) = exporter.get_finished_spans()
        self.assertEqual(span.attributes["notification.outcome"], "discarded")
        self.assertEqual(span.attributes["severity"], "error")
        self.assertFalse(span.status.is_ok)
        self.assertEqual(span.status.description, "discarded")

    def test_retried_copy_keeps_the_trace(self):
        """The retry copy carries the original traceparent, so later attempts join the same trace."""
        consumer = NotificationConsumer(CONFIG, NotificationService(CONFIG, FlakySender(failures=1), InMemoryStore()))
        channel = FakeChannel()
        deliver(consumer, channel, "notify.refund", body("refund"),
                headers={"traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"})
        self.assertEqual(channel.published[-1].properties.headers["traceparent"],
                         "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")


if __name__ == "__main__":
    unittest.main()
