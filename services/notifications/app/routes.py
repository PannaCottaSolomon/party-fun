"""HTTP endpoints: one POST endpoint per notification type, the notification log, and health checks."""
from __future__ import annotations

import hmac

from flask import Blueprint, current_app, jsonify, request

from .contracts import TYPES, UnknownRecipient, ValidationError, describe
from .email_sender import EmailSendError
from .store import MAX_LIMIT, StoreError

bp = Blueprint("notifications", __name__)

API_KEY_HEADER = "X-API-Key"
# Endpoints that send emails or reveal who was emailed need the API key.
_PROTECTED_PREFIXES = ("/notify_", "/notifications")


def _service():
    return current_app.extensions["notification_service"]


@bp.before_app_request
def require_api_key():
    expected = current_app.extensions["notification_config"].api_key
    if not expected or not request.path.startswith(_PROTECTED_PREFIXES):
        return None
    supplied = request.headers.get(API_KEY_HEADER, "")
    # compare_digest takes the same time whether the first or last character is wrong
    if not hmac.compare_digest(supplied.encode(), expected.encode()):
        return jsonify(error="unauthorized", message=f"Missing or wrong {API_KEY_HEADER} header"), 401
    return None


def _make_view(type_name: str):
    def view():
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify(error="invalid_json", message="Body must be a JSON object"), 400
        try:
            result = _service().notify(type_name, payload)
        except ValidationError as exc:
            return jsonify(error="validation_failed", details=exc.errors), 400
        except UnknownRecipient as exc:
            return jsonify(error="unknown_recipient", message=str(exc), retryable=True), 422
        except EmailSendError as exc:
            current_app.logger.warning("email provider error for %s: %s", type_name, exc)
            return jsonify(error="email_provider_error", retryable=not exc.permanent), 502
        except StoreError as exc:
            current_app.logger.error("notification log unavailable: %s", exc)
            return jsonify(error="notification_log_unavailable", retryable=True), 503
        return jsonify(status=result.status, type=result.type, email_id=result.email_id), 200

    view.__name__ = f"notify_{type_name}"
    return view


for _ntype in TYPES.values():
    bp.add_url_rule(_ntype.endpoint, view_func=_make_view(_ntype.name), methods=["POST"])


@bp.get("/notifications")
def list_notifications():
    """The notification log, newest first. e.g. /notifications?email=alex@example.com&type=refund"""
    try:
        limit = int(request.args.get("limit", 50))
    except ValueError:
        return jsonify(error="invalid_limit", message="limit must be a number"), 400
    try:
        rows = _service().store.find(
            email=request.args.get("email") or None,
            type_name=request.args.get("type") or None,
            notification_id=request.args.get("notification_id") or None,
            limit=max(1, min(limit, MAX_LIMIT)),
        )
    except StoreError as exc:
        current_app.logger.error("notification log unavailable: %s", exc)
        return jsonify(error="notification_log_unavailable"), 503
    return jsonify(notifications=rows, count=len(rows))


@bp.get("/notification-types")
def notification_types():
    """The message contract, so teammates can check field names without reading code."""
    return jsonify(describe())


@bp.get("/health")
def health():
    """Liveness: the process is up."""
    return jsonify(status="ok", consumer=_consumer_state())


@bp.get("/ready")
def ready():
    """Readiness: ready for traffic once the RabbitMQ consumer is connected (if enabled)."""
    state = _consumer_state()
    ok = state in ("running", "disabled")
    return jsonify(status="ready" if ok else "not_ready", consumer=state), (200 if ok else 503)


def _consumer_state() -> str:
    consumer = current_app.extensions.get("notification_consumer")
    return "disabled" if consumer is None else consumer.state
