"""Notification service: sends emails for sign-ups, events, refunds and admin alerts.

Triggered two ways, with the same payloads:
  * RabbitMQ: publish to the `notifications` topic exchange with routing key notify.<type>
  * HTTP:     POST /notify_<type>
"""
from __future__ import annotations

from flask import Flask

from .config import Config
from .service import NotificationService, build_service


def create_app(config: Config | None = None, service: NotificationService | None = None) -> Flask:
    config = config or Config.from_env()
    app = Flask(__name__)
    app.extensions["notification_config"] = config
    app.extensions["notification_service"] = service or build_service(config)

    from .routes import bp
    app.register_blueprint(bp)
    return app
