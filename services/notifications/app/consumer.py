"""RabbitMQ consumer, running in a background thread next to the HTTP server.

Topology (all declared on startup, safe to run repeatedly):

  producers --notify.<type>--> [notifications] topic exchange
                                      | binding notify.#
                                      v
                           [notification_service] queue --> this consumer
                                      ^                        |        |
                 TTL expires          |      temporary failure |        | permanent failure
                                      |                        v        v
                    [notification_service.retry.30s]  retry 1   discarded: logged + recorded
                    [notification_service.retry.2m ]  retry 2   as 'failed' in the notification log
                    [notification_service.retry.10m]  retry 3

Every attempt adds a row to the notification log with its attempt number:
'retrying' when it failed and will be retried, then 'sent' or 'failed'.

A failed email waits in a retry queue, then flows back to the main queue. Each
retry waits longer than the last (RETRY_DELAYS_MS, default 30s, 2m, 10m), so a
provider outage of about 12 minutes is ridden out. After the last retry, or if
the message can never succeed, it is discarded. There is no
dead-letter queue here: the Payment service owns the team's DLQ, and alerts the
admin through this service's notify.admin_alert when something lands in it.
(The retry queue uses RabbitMQ's dead-letter *routing* to send messages back
after the delay, but nothing is ever parked there.)

It also keeps the user directory up to date (see users.py):

  Authentication --user.created|updated|deleted--> [user.events] topic exchange
                                      | binding user.*
                                      v
                           [notifications.users] queue --> this consumer --> users table
                                      ^                        |
                 TTL expires          |   database down        |
                    [notifications.users.retry.30s] <-----------

Each retry queue's name includes its delay, so changing RETRY_DELAYS_MS creates
new queues rather than clashing with existing ones (RabbitMQ refuses to change
a queue's settings). Retry queues that are no longer used can be deleted in the
RabbitMQ management UI once they are empty.
"""
from __future__ import annotations

import logging
import threading

import pika
from pika.exceptions import AMQPError, NackError, UnroutableError

from .config import Config, duration_label
from .messaging import (
    ACK,
    DISCARD,
    process,
    retry_headers,
)
from .service import NotificationService
from .tracing import annotate
from .users import RETRY as USER_RETRY
from .users import process_user_message
from .users import retry_headers as user_retry_headers

log = logging.getLogger(__name__)


class NotificationConsumer(threading.Thread):
    def __init__(self, config: Config, service: NotificationService):
        super().__init__(name="rabbitmq-consumer", daemon=True)
        self.config = config
        self.service = service
        self.state = "starting"  # starting | connecting | running | disconnected | stopping | stopped
        self._stop_event = threading.Event()

    # ---------- setup ----------

    def declare_topology(self, channel) -> None:
        c = self.config
        channel.exchange_declare(exchange=c.exchange, exchange_type="topic", durable=True)
        channel.queue_declare(queue=c.queue, durable=True)
        channel.queue_bind(queue=c.queue, exchange=c.exchange, routing_key="notify.#")
        for name, delay_ms in c.retry_queues:
            channel.queue_declare(
                queue=name,
                durable=True,
                arguments={
                    "x-message-ttl": delay_ms,
                    # when the TTL expires, send the message back to the main queue
                    "x-dead-letter-exchange": "",
                    "x-dead-letter-routing-key": c.queue,
                },
            )
        if c.user_directory_enabled:
            # Same settings as the Authentication service's declaration (durable topic), so
            # whichever service starts first creates it and the other's declare is a no-op.
            channel.exchange_declare(exchange=c.user_exchange, exchange_type="topic", durable=True)
            channel.queue_declare(queue=c.user_queue, durable=True)
            channel.queue_bind(queue=c.user_queue, exchange=c.user_exchange, routing_key="user.*")
            channel.queue_declare(
                queue=c.user_retry_queue,
                durable=True,
                arguments={
                    "x-message-ttl": c.user_retry_delay_ms,
                    "x-dead-letter-exchange": "",
                    "x-dead-letter-routing-key": c.user_queue,
                },
            )

    # ---------- main loop ----------

    def run(self) -> None:
        backoff = 1
        while not self._stop_event.is_set():
            connection = None
            try:
                self.state = "connecting"
                connection = pika.BlockingConnection(pika.URLParameters(self.config.rabbitmq_url))
                channel = connection.channel()
                channel.confirm_delivery()  # basic_publish raises if the broker rejects it
                channel.basic_qos(prefetch_count=self.config.prefetch)
                self.declare_topology(channel)
                channel.basic_consume(queue=self.config.queue, on_message_callback=self.on_message)
                if self.config.user_directory_enabled:
                    channel.basic_consume(queue=self.config.user_queue, on_message_callback=self.on_user_message)
                self.state = "running"
                backoff = 1
                log.info("consuming from queue '%s'", self.config.queue)
                while not self._stop_event.is_set():
                    connection.process_data_events(time_limit=1)
            except (AMQPError, OSError) as exc:
                self.state = "disconnected"
                log.warning("RabbitMQ connection problem (%s); reconnecting in %ss", exc, backoff)
                self._stop_event.wait(backoff)
                backoff = min(backoff * 2, 30)
            finally:
                if connection is not None and connection.is_open:
                    try:
                        connection.close()
                    except (AMQPError, OSError):  # already broken; nothing useful to do
                        log.debug("RabbitMQ connection was already closed", exc_info=True)
        self.state = "stopped"

    def stop(self) -> None:
        """Ask the consumer to stop. The message being handled right now is finished
        first; messages not started yet go back to RabbitMQ for the next pod."""
        self.state = "stopping"
        self._stop_event.set()

    @property
    def stopping(self) -> bool:
        return self._stop_event.is_set()

    # ---------- per message ----------

    def on_message(self, channel, method, properties, body: bytes) -> None:
        if self._stop_event.is_set():
            # Shutting down: hand prefetched messages straight back to RabbitMQ
            # instead of starting work we may not have time to finish.
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
            return
        headers = dict(properties.headers or {})
        decision = process(self.service, method.routing_key, body, headers, self.config.max_retries)
        annotate(decision)  # type, id, attempt, outcome and severity on this message's trace span

        if decision.action == ACK:
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        if decision.action == DISCARD:
            # Already logged by process(); record it so GET /notifications shows what was lost.
            self._record_discarded(decision, body)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        # RETRY: park a copy in the retry queue for this attempt (each one waits longer).
        target = self.config.retry_queue_for(decision.retry_count)
        try:
            # Publish the copy first, then ack the original. If we crash in between,
            # the original is redelivered and the notification_id stops a double email.
            channel.basic_publish(
                exchange="",
                routing_key=target,
                body=body,
                properties=pika.BasicProperties(
                    headers=retry_headers(headers, decision),
                    content_type=properties.content_type or "application/json",
                    message_id=properties.message_id,
                    delivery_mode=2,  # persistent
                ),
                mandatory=True,
            )
        except (UnroutableError, NackError) as exc:
            log.error("could not move message to %s (%s); requeueing", target, exc)
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
            return
        channel.basic_ack(delivery_tag=method.delivery_tag)
        log.warning("attempt %d of %d failed for %s, retrying in %s via %s: %s",
                    decision.attempt, self.config.max_retries + 1, decision.label,
                    duration_label(self.config.retry_delay_for(decision.retry_count)), target,
                    decision.reason)
        # Recorded only once the retry is safely queued, so a redelivery can't log the attempt twice.
        self._record_retrying(decision, body)

    def on_user_message(self, channel, method, properties, body: bytes) -> None:
        """A user.created / user.updated / user.deleted message: update the user directory."""
        if self._stop_event.is_set():
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
            return
        headers = dict(properties.headers or {})
        decision = process_user_message(self.service.store, method.routing_key, body, headers,
                                        self.config.user_max_retries)
        if decision.action == USER_RETRY:
            try:
                channel.basic_publish(
                    exchange="",
                    routing_key=self.config.user_retry_queue,
                    body=body,
                    properties=pika.BasicProperties(
                        headers=user_retry_headers(headers, decision),
                        content_type=properties.content_type or "application/json",
                        message_id=properties.message_id,
                        delivery_mode=2,
                    ),
                    mandatory=True,
                )
            except (UnroutableError, NackError) as exc:
                log.error("could not move %s to %s (%s); requeueing", decision.routing_key,
                          self.config.user_retry_queue, exc)
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                return
        # ack: applied, retry: a copy is queued, discard: logged by process_user_message()
        channel.basic_ack(delivery_tag=method.delivery_tag)

    def _record_retrying(self, decision, body: bytes) -> None:
        """Best effort: a logging problem must never stop the consumer."""
        try:
            self.service.record_retrying(decision.routing_key, body, decision.reason, decision.attempt)
        except Exception:  # the warning log line from process() remains
            log.exception("could not record the failed attempt in the notification log")

    def _record_discarded(self, decision, body: bytes) -> None:
        """Best effort: a logging problem must never stop the consumer."""
        try:
            self.service.record_discarded(decision.routing_key, body, decision.reason, decision.attempt)
        except Exception:  # the error log line from process() remains
            log.exception("could not record the discarded message in the notification log")
