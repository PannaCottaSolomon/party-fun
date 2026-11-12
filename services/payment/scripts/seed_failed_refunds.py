"""Seeds failed refunds into a running local Payment Service, for the Admin "Failed refunds" dashboard.

Each seeded refund is real, in the Stripe sandbox (no real money):

1. a ticket is paid with the test card pm_card_visa (POST /payments/checkout);
2. its PaymentIntent is refunded directly at Stripe, standing in for a refund that went through while the Payment
   Service's own call failed (for example a lost response);
3. the Payment Service is asked to refund it (POST /payments/{id}/refund). Stripe answers charge_already_refunded,
   so the payment becomes REFUND_FAILED and one message for it goes to payments.refunds.dlq.

Retrying one on the dashboard (POST /admin/refunds/{id}/retry) then finds the refund at Stripe, records the payment
as REFUNDED without refunding twice, and removes its DLQ message.

Usage, from services/payment, with the service running (docker compose up -d):

    python scripts/seed_failed_refunds.py
    python scripts/seed_failed_refunds.py --count 5 --env-file dev.env --payment-url http://localhost:8080

Every run adds new ones. Only the Python standard library is used; keys are read from services/payment/dev.env (or
the file given with --env-file) and never printed.
"""

import argparse
import base64
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# (participant id, name, email, event id, event title, quantity, ticket price) from the frontend's demo data, so the
# dashboard shows the same users and events as the rest of the app.
DEMO_PURCHASES = [
    ("u-participant", "participant", "participant@partyfun.sg", "e-neon", "Neon Glow Rooftop Party", 2, 25.00),
    ("u-participant", "participant", "participant@partyfun.sg", "e-silent", "Silent Disco Night", 1, 18.00),
    ("u-participant", "participant", "participant@partyfun.sg", "e-hackathon", "CS302 Hackathon Afterparty", 3, 12.00),
    ("u-participant", "participant", "participant@partyfun.sg", "e-karaoke", "K-Pop Karaoke Battle", 2, 15.00),
    ("u-participant", "participant", "participant@partyfun.sg", "e-acoustic", "Acoustic Night on the Green", 1, 20.00),
]


def service_id(value):
    """The frontend's toServiceId (src/api/serviceIds.ts): FNV-1a, shifted into 1 .. 2^31-1."""
    h = 0x811C9DC5
    for ch in value:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return (h >> 1) or 1


def read_env(path):
    values = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def call(method, url, body=None, headers=None, form=False):
    data = None
    headers = dict(headers or {})
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        text = error.read()
        try:
            return error.code, json.loads(text or b"null")
        except ValueError:
            return error.code, {"message": text.decode(errors="replace")}


def seed_one(index, purchase, payment_url, stripe_key, admin_key):
    user, name, email, event, title, quantity, price = purchase
    booking_ref = f"seed-dlq-{int(time.time())}-{index}"

    status, payment = call("POST", f"{payment_url}/payments/checkout", {
        "eventBookingId": booking_ref,
        "eventId": service_id(event),
        "userId": service_id(user),
        "quantity": quantity,
        "ticketPrice": price,
        "paymentMethodId": "pm_card_visa",
        "participantEmail": email,
        "participantName": name,
        "eventTitle": title,
    })
    if status != 201 or payment.get("status") != "PAID":
        raise SystemExit(f"Checkout for {title} failed: HTTP {status} {payment.get('message', '')}")
    payment_id, intent = payment["paymentId"], payment["stripePaymentIntentId"]

    auth = "Basic " + base64.b64encode(f"{stripe_key}:".encode()).decode()
    status, refund = call("POST", "https://api.stripe.com/v1/refunds", {"payment_intent": intent},
                          {"Authorization": auth, "Idempotency-Key": f"seed-direct-refund-{intent}"}, form=True)
    if status != 200:
        raise SystemExit(f"Direct Stripe refund of {intent} failed: HTTP {status}")

    status, failed = call("POST", f"{payment_url}/payments/{payment_id}/refund", headers={"X-Admin-Api-Key": admin_key})
    if status != 502:
        raise SystemExit(f"Expected the Payment Service's refund of payment {payment_id} to fail (502), got {status}: "
                         f"{failed.get('message', '')}")
    print(f"  payment {payment_id}: {title}, {quantity} x S${price:.2f} -> REFUND_FAILED, in the DLQ")


def main():
    service_dir = pathlib.Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Seed failed refunds (Stripe sandbox) for the Admin dashboard.")
    parser.add_argument("--count", type=int, default=3, help=f"how many (1-{len(DEMO_PURCHASES)}, default 3)")
    parser.add_argument("--env-file", default=str(service_dir / "dev.env"),
                        help="file with stripe_secret_key and payment_admin_api_key (default: services/payment/dev.env)")
    parser.add_argument("--payment-url", default="http://localhost:8080", help="Payment Service base URL")
    args = parser.parse_args()

    env = read_env(args.env_file)
    stripe_key = env.get("stripe_secret_key", "")
    admin_key = env.get("payment_admin_api_key", "")
    if not stripe_key.startswith(("sk_test_", "rk_test_")):
        raise SystemExit(f"stripe_secret_key in {args.env_file} must be a Stripe sandbox key (sk_test_... or rk_test_...)")
    if not admin_key:
        raise SystemExit(f"payment_admin_api_key is not set in {args.env_file}")
    if not 1 <= args.count <= len(DEMO_PURCHASES):
        raise SystemExit(f"--count must be between 1 and {len(DEMO_PURCHASES)}")

    payment_url = args.payment_url.rstrip("/")
    status, _ = call("GET", f"{payment_url}/health")
    if status != 200:
        raise SystemExit(f"The Payment Service at {payment_url} is not answering (start it: docker compose up -d)")

    print(f"Seeding {args.count} failed refund(s) through {payment_url} (Stripe sandbox):")
    for index, purchase in enumerate(DEMO_PURCHASES[:args.count]):
        seed_one(index, purchase, payment_url, stripe_key, admin_key)

    time.sleep(1)
    status, count = call("GET", f"{payment_url}/admin/refunds/dlq/count", headers={"X-Admin-Api-Key": admin_key})
    if status == 200:
        print(f"Refund dead-letter queue now holds {count['total']} message(s).")


if __name__ == "__main__":
    sys.exit(main())
