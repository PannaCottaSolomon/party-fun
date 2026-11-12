"""Publish a sample notification to RabbitMQ, to try the service end to end.

Usage (with the service and RabbitMQ running, e.g. via docker compose):
    python scripts/publish_test_message.py refund
    python scripts/publish_test_message.py signup --email you@example.com
    python scripts/publish_test_message.py signup --broken      # is logged and discarded
    python scripts/publish_test_message.py --list

User directory (what the Authentication service sends), then a notification by user id:
    python scripts/publish_test_message.py --create-user u-123 --email you@example.com --name "Your Name"
    python scripts/publish_test_message.py signup --user-id u-123 --fresh-id
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pika

from app.samples import SAMPLE_PAYLOADS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("type", nargs="?", choices=sorted(SAMPLE_PAYLOADS), help="notification type")
    parser.add_argument("--email", help="override the recipient address")
    parser.add_argument("--user-id", help="send this user_id instead of an email address")
    parser.add_argument("--create-user", metavar="ID", help="publish user.created for this id (needs --email)")
    parser.add_argument("--name", help="full name for --create-user")
    parser.add_argument("--broken", action="store_true", help="send a payload with missing fields")
    parser.add_argument("--fresh-id", action="store_true", help="new notification_id so it isn't a duplicate")
    parser.add_argument("--list", action="store_true", help="print the sample payloads and exit")
    parser.add_argument("--url", default=os.environ.get("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/%2F"))
    parser.add_argument("--exchange", default=os.environ.get("NOTIFICATION_EXCHANGE", "notifications"))
    args = parser.parse_args()

    if args.create_user:
        if not args.email:
            parser.error("--create-user needs --email")
        user = {"id": args.create_user, "email": args.email, "fullName": args.name, "role": "PARTICIPANT"}
        publish(args.url, os.environ.get("USER_EXCHANGE", "user.events"), "user.created", user)
        return

    if args.list or not args.type:
        print(json.dumps(SAMPLE_PAYLOADS, indent=2))
        return

    payload = dict(SAMPLE_PAYLOADS[args.type])
    if args.email:
        payload["email"] = args.email
    if args.user_id:
        payload.pop("email", None)
        payload.pop("name", None)
        payload["user_id"] = args.user_id
    if args.fresh_id:
        payload["notification_id"] = f"{args.type}-{uuid.uuid4()}"
    if args.broken:
        payload = {"email": payload.get("email", "someone@example.com")}

    publish(args.url, args.exchange, f"notify.{args.type}", payload)


def publish(url: str, exchange: str, routing_key: str, payload: dict) -> None:
    connection = pika.BlockingConnection(pika.URLParameters(url))
    channel = connection.channel()
    channel.exchange_declare(exchange=exchange, exchange_type="topic", durable=True)
    channel.basic_publish(
        exchange=exchange,
        routing_key=routing_key,
        body=json.dumps(payload),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    connection.close()
    print(f"published {routing_key} on {exchange}:\n{json.dumps(payload, indent=2)}")


if __name__ == "__main__":
    main()
