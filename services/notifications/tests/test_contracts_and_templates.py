import copy
import unittest

from app.contracts import TYPES, ValidationError, describe, validate
from app.samples import SAMPLE_PAYLOADS
from app.templates import event_date, money, render


class ContractTests(unittest.TestCase):
    def test_every_sample_is_valid(self):
        for name, payload in SAMPLE_PAYLOADS.items():
            with self.subTest(name):
                validate(TYPES[name], payload, admin_email="admin@example.com")

    def test_every_type_has_a_sample(self):
        self.assertEqual(set(TYPES), set(SAMPLE_PAYLOADS))

    def test_missing_required_fields_are_all_reported(self):
        with self.assertRaises(ValidationError) as ctx:
            validate(TYPES["signup"], {"email": "a@b.com"})
        self.assertEqual(
            set(ctx.exception.errors),
            {"event_title", "event_date", "location", "registration_id"},
        )

    def test_defaults_are_filled_in(self):
        payload = {"email": "a@b.com", "event_title": "T", "event_date": "2026-01-01",
                   "location": "L", "registration_id": 1}
        data = validate(TYPES["signup"], payload)
        self.assertEqual(data["name"], "there")
        self.assertEqual(data["currency"], "SGD")
        self.assertIsNone(data["amount"])
        self.assertIsNone(data["notification_id"])

    def test_bad_values(self):
        cases = {
            "email": "not-an-email",
            "amount": 12.5,
            "registration_id": True,
            "currency": "dollars",
        }
        for field, bad in cases.items():
            with self.subTest(field):
                payload = copy.deepcopy(SAMPLE_PAYLOADS["signup"])
                payload[field] = bad
                with self.assertRaises(ValidationError) as ctx:
                    validate(TYPES["signup"], payload)
                self.assertIn(field, ctx.exception.errors)

    def test_negative_and_boolean_amounts_rejected(self):
        for bad in (-1, True):
            payload = dict(SAMPLE_PAYLOADS["refund"], amount=bad)
            with self.assertRaises(ValidationError):
                validate(TYPES["refund"], payload)

    def test_role_and_audience_are_restricted(self):
        with self.assertRaises(ValidationError):
            validate(TYPES["account_created"], dict(SAMPLE_PAYLOADS["account_created"], role="admin"))
        with self.assertRaises(ValidationError):
            validate(TYPES["event_deleted"], dict(SAMPLE_PAYLOADS["event_deleted"], audience="everyone"))

    def test_changes_must_be_object_or_list_of_strings(self):
        for bad in ({}, [], "venue changed", [1, 2]):
            with self.subTest(bad=bad), self.assertRaises(ValidationError):
                validate(TYPES["event_updated"], dict(SAMPLE_PAYLOADS["event_updated"], changes=bad))
        validate(TYPES["event_updated"], dict(SAMPLE_PAYLOADS["event_updated"], changes=["Venue moved"]))

    def test_unknown_fields_are_ignored(self):
        data = validate(TYPES["account_created"], dict(SAMPLE_PAYLOADS["account_created"], extra="x"))
        self.assertNotIn("extra", data)

    def test_admin_alert_goes_to_admin_email_by_default(self):
        data = validate(TYPES["admin_alert"], SAMPLE_PAYLOADS["admin_alert"], admin_email="ops@example.com")
        self.assertEqual(data["email"], "ops@example.com")

    def test_admin_alert_without_any_recipient_fails(self):
        with self.assertRaises(ValidationError) as ctx:
            validate(TYPES["admin_alert"], SAMPLE_PAYLOADS["admin_alert"], admin_email="")
        self.assertIn("email", ctx.exception.errors)

    def test_non_object_payload(self):
        with self.assertRaises(ValidationError):
            validate(TYPES["refund"], ["not", "an", "object"])

    def test_describe_lists_every_type(self):
        self.assertEqual({d["type"] for d in describe()}, set(TYPES))


class TemplateTests(unittest.TestCase):
    def test_every_type_renders(self):
        for name, payload in SAMPLE_PAYLOADS.items():
            with self.subTest(name):
                data = validate(TYPES[name], payload, admin_email="admin@example.com")
                subject, body_html, body_text = render(TYPES[name], data, "Campus Events")
                self.assertTrue(subject)
                self.assertIn("<html>", body_html)
                self.assertNotIn("<", body_text)
                self.assertNotIn("\n", subject)

    def test_html_is_escaped(self):
        payload = dict(SAMPLE_PAYLOADS["event_created"], event_title="<script>alert(1)</script>")
        data = validate(TYPES["event_created"], payload)
        subject, body_html, _ = render(TYPES["event_created"], data, "Campus Events")
        self.assertNotIn("<script>", body_html)
        self.assertIn("&lt;script&gt;", body_html)
        self.assertIn("<script>", subject)  # subjects are plain text, not HTML

    def test_participant_and_organiser_wording_differs(self):
        base = SAMPLE_PAYLOADS["event_deleted"]
        texts = {}
        for audience in ("participant", "organiser"):
            data = validate(TYPES["event_deleted"], dict(base, audience=audience))
            texts[audience] = render(TYPES["event_deleted"], data, "X")[2]
        self.assertIn("refund is being processed", texts["participant"])
        self.assertIn("All participants have been notified", texts["organiser"])

    def test_refund_amount_formatting(self):
        data = validate(TYPES["refund"], SAMPLE_PAYLOADS["refund"])
        self.assertIn("SGD 15.00", render(TYPES["refund"], data, "X")[2])

    def test_filters(self):
        self.assertEqual(money(123456, "sgd"), "SGD 1,234.56")
        self.assertEqual(event_date("2026-11-04T08:05:00+08:00"), "Wed, 4 Nov 2026, 8:05 AM")
        self.assertEqual(event_date("2026-11-14"), "Sat, 14 Nov 2026")
        self.assertEqual(event_date("next Friday"), "next Friday")


if __name__ == "__main__":
    unittest.main()
