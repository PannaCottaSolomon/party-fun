"""Example payloads for every notification type.

Used by the tests, the test publisher script and as copy-paste examples for
teammates writing the producing services.
"""

SAMPLE_PAYLOADS = {
    "signup": {
        "notification_id": "signup-reg-1001",
        "email": "participant@example.com",
        "name": "Alex",
        "event_title": "CCA Welcome Party",
        "event_date": "2026-11-14T20:00:00+08:00",
        "location": "SMU Connexion, Level 3",
        "registration_id": 1001,
        "amount": 1500,
        "currency": "SGD",
    },
    "account_created": {
        "notification_id": "account-user-42",
        "email": "newuser@example.com",
        "name": "Jamie",
        "role": "participant",
    },
    "event_created": {
        "notification_id": "event-created-77",
        "email": "organiser@example.com",
        "name": "Priya",
        "event_title": "Off-campus Beach Day",
        "event_date": "2026-12-05T10:00:00+08:00",
        "location": "Sentosa, Siloso Beach",
        "event_id": 77,
    },
    "event_updated": {
        "notification_id": "event-updated-77-v2-user-42",
        "email": "participant@example.com",
        "name": "Alex",
        "event_title": "Off-campus Beach Day",
        "event_id": 77,
        "changes": {"location": "Sentosa, Palawan Beach", "start time": "11:00 AM"},
        "audience": "participant",
    },
    "event_deleted": {
        "notification_id": "event-deleted-77-user-42",
        "email": "participant@example.com",
        "name": "Alex",
        "event_title": "Off-campus Beach Day",
        "event_id": 77,
        "reason": "Not enough sign-ups by the deadline",
        "audience": "participant",
    },
    "refund": {
        "notification_id": "refund-reg-1001",
        "email": "participant@example.com",
        "name": "Alex",
        "event_title": "Off-campus Beach Day",
        "amount": 1500,
        "currency": "SGD",
        "registration_id": 1001,
        "refund_id": "re_3Pxyz",
    },
    "admin_alert": {
        "notification_id": "alert-refund-reg-1001",
        "source": "payment-service",
        "error": "Refund failed after 3 retries",
        "details": {"registration_id": 1001, "stripe_error": "charge_already_refunded"},
    },
}
