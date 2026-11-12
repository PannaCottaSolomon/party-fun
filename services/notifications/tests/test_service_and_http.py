import unittest

import requests

from app import create_app
from app.config import Config
from app.contracts import TYPES, ValidationError
from app.email_sender import DryRunSender, Email, EmailSendError, ResendSender
from app.samples import SAMPLE_PAYLOADS
from app.service import NotificationService

CONFIG = Config(enable_consumer=False, admin_email="admin@example.com")


class FailingSender:
    def __init__(self, permanent):
        self.permanent = permanent
        self.calls = 0

    def send(self, email):
        self.calls += 1
        raise EmailSendError("boom", permanent=self.permanent)


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.sender = DryRunSender()
        self.service = NotificationService(CONFIG, self.sender)

    def test_sends_email_with_idempotency_key(self):
        result = self.service.notify("refund", SAMPLE_PAYLOADS["refund"])
        self.assertEqual(result.status, "sent")
        self.assertEqual(len(self.sender.sent), 1)
        email = self.sender.sent[0]
        self.assertEqual(email.to, "participant@example.com")
        self.assertEqual(email.idempotency_key, "refund:refund-reg-1001")

    def test_duplicate_notification_id_sends_once(self):
        first = self.service.notify("signup", SAMPLE_PAYLOADS["signup"])
        second = self.service.notify("signup", SAMPLE_PAYLOADS["signup"])
        self.assertEqual((first.status, second.status), ("sent", "duplicate"))
        self.assertEqual(len(self.sender.sent), 1)

    def test_same_id_different_type_is_not_a_duplicate(self):
        self.service.notify("signup", dict(SAMPLE_PAYLOADS["signup"], notification_id="x"))
        self.service.notify("refund", dict(SAMPLE_PAYLOADS["refund"], notification_id="x"))
        self.assertEqual(len(self.sender.sent), 2)

    def test_without_notification_id_every_call_sends(self):
        payload = {k: v for k, v in SAMPLE_PAYLOADS["signup"].items() if k != "notification_id"}
        self.service.notify("signup", payload)
        self.service.notify("signup", payload)
        self.assertEqual(len(self.sender.sent), 2)

    def test_failed_send_is_not_remembered_as_sent(self):
        failing = NotificationService(CONFIG, FailingSender(permanent=False), store=self.service.store)
        with self.assertRaises(EmailSendError):
            failing.notify("refund", SAMPLE_PAYLOADS["refund"])
        # a later successful attempt must still go out
        self.assertEqual(self.service.notify("refund", SAMPLE_PAYLOADS["refund"]).status, "sent")

    def test_unknown_type(self):
        with self.assertRaises(ValidationError):
            self.service.notify("party", {})


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body
        self.text = str(body)

    def json(self):
        return self._body


class FakeSession:
    def __init__(self, response=None, exc=None):
        self.response, self.exc, self.calls = response, exc, []

    def post(self, url, json, headers, timeout):
        self.calls.append({"url": url, "json": json, "headers": headers})
        if self.exc:
            raise self.exc
        return self.response


class ResendSenderTests(unittest.TestCase):
    EMAIL = Email(to="a@b.com", subject="S", html="<p>H</p>", text="H", idempotency_key="refund:1")

    def sender(self, **kw):
        session = FakeSession(**kw)
        return ResendSender("re_test", "App <app@example.com>", session=session), session

    def test_success(self):
        sender, session = self.sender(response=FakeResponse(200, {"id": "email_123"}))
        self.assertEqual(sender.send(self.EMAIL), "email_123")
        call = session.calls[0]
        self.assertEqual(call["headers"]["Authorization"], "Bearer re_test")
        self.assertEqual(call["headers"]["Idempotency-Key"], "refund:1")
        self.assertEqual(call["json"]["to"], ["a@b.com"])

    def test_error_classification(self):
        cases = [
            (429, {"name": "rate_limit_exceeded"}, False),
            (500, {"name": "internal_server_error"}, False),
            (503, {}, False),
            (409, {"name": "concurrent_idempotent_requests"}, False),
            (409, {"name": "invalid_idempotent_request"}, True),
            (422, {"name": "validation_error"}, True),
            (403, {"name": "invalid_from_address"}, True),
        ]
        for status, body, permanent in cases:
            with self.subTest(status=status, body=body):
                sender, _ = self.sender(response=FakeResponse(status, body))
                with self.assertRaises(EmailSendError) as ctx:
                    sender.send(self.EMAIL)
                self.assertEqual(ctx.exception.permanent, permanent)

    def test_network_error_is_temporary(self):
        sender, _ = self.sender(exc=requests.ConnectionError("down"))
        with self.assertRaises(EmailSendError) as ctx:
            sender.send(self.EMAIL)
        self.assertFalse(ctx.exception.permanent)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.sender = DryRunSender()
        self.app = create_app(CONFIG, NotificationService(CONFIG, self.sender))
        self.client = self.app.test_client()

    def test_every_endpoint_accepts_its_sample(self):
        for name, payload in SAMPLE_PAYLOADS.items():
            with self.subTest(name):
                resp = self.client.post(TYPES[name].endpoint, json=payload)
                self.assertEqual(resp.status_code, 200, resp.get_json())
                self.assertEqual(resp.get_json()["status"], "sent")
        self.assertEqual(len(self.sender.sent), len(SAMPLE_PAYLOADS))

    def test_endpoint_names_match_the_spec(self):
        expected = {"/notify_signup", "/notify_account_created", "/notify_create_event",
                    "/notify_update_event", "/notify_delete_event", "/notify_refund"}
        routes = {r.rule for r in self.app.url_map.iter_rules()}
        self.assertTrue(expected <= routes)

    def test_duplicate_returns_200_duplicate(self):
        self.client.post("/notify_refund", json=SAMPLE_PAYLOADS["refund"])
        resp = self.client.post("/notify_refund", json=SAMPLE_PAYLOADS["refund"])
        self.assertEqual(resp.get_json()["status"], "duplicate")

    def test_validation_error_is_400_with_details(self):
        resp = self.client.post("/notify_signup", json={"email": "a@b.com"})
        self.assertEqual(resp.status_code, 400)
        self.assertIn("event_title", resp.get_json()["details"])

    def test_non_json_is_400(self):
        resp = self.client.post("/notify_signup", data="hello", content_type="text/plain")
        self.assertEqual(resp.status_code, 400)

    def test_provider_failure_is_502(self):
        app = create_app(CONFIG, NotificationService(CONFIG, FailingSender(permanent=False)))
        resp = app.test_client().post("/notify_refund", json=SAMPLE_PAYLOADS["refund"])
        self.assertEqual(resp.status_code, 502)
        self.assertTrue(resp.get_json()["retryable"])

    def test_health_ready_and_contract(self):
        self.assertEqual(self.client.get("/health").get_json()["consumer"], "disabled")
        self.assertEqual(self.client.get("/ready").status_code, 200)
        types = self.client.get("/notification-types").get_json()
        self.assertIn("notify.refund", {t["routing_key"] for t in types})

    def test_ready_is_503_until_consumer_connects(self):
        class Consumer:
            state = "connecting"
        self.app.extensions["notification_consumer"] = Consumer()
        self.assertEqual(self.client.get("/ready").status_code, 503)
        Consumer.state = "running"
        self.assertEqual(self.client.get("/ready").status_code, 200)


if __name__ == "__main__":
    unittest.main()
