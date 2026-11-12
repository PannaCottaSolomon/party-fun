"""Entry point: `python -m app.main`

Starts the RabbitMQ consumer thread (unless ENABLE_CONSUMER=false) and serves
the HTTP API with waitress, a production-ready pure-Python WSGI server.

Graceful shutdown: ECS and Kubernetes send SIGTERM before stopping a
container, then wait (30s by default) before killing it. On SIGTERM we stop
taking new messages, finish the one in progress, close the RabbitMQ
connection (unstarted messages go back to the queue for the next pod), then
stop the HTTP server.
"""
from __future__ import annotations

import logging
import signal

from . import create_app
from .config import Config
from .logging_setup import setup_logging
from .service import build_service

log = logging.getLogger(__name__)


def stop_consumer(consumer, timeout_s: float) -> bool:
    """Stop the consumer and wait for it to finish. Returns True if it stopped in time."""
    if consumer is None:
        return True
    consumer.stop()
    consumer.join(timeout=timeout_s)
    finished = not consumer.is_alive()
    if finished:
        log.info("consumer stopped cleanly")
    else:
        log.warning("consumer still busy after %ss; exiting anyway (its message will be redelivered)", timeout_s)
    return finished


def main() -> None:
    config = Config.from_env()
    setup_logging(config.log_level, config.log_format)
    service = build_service(config)
    app = create_app(config, service)

    consumer = None
    if config.enable_consumer:
        from .consumer import (
            NotificationConsumer,  # imported here so HTTP-only mode doesn't need pika
        )

        consumer = NotificationConsumer(config, service)
        app.extensions["notification_consumer"] = consumer
        consumer.start()

    from waitress import create_server

    server = create_server(app, host="0.0.0.0", port=config.port, threads=8)

    def shutdown(signum, _frame):
        log.info("received %s, shutting down", signal.Signals(signum).name)
        stop_consumer(consumer, config.shutdown_timeout_s)
        try:
            server.close()  # stop accepting new HTTP connections
        except OSError:  # already closing
            log.debug("HTTP server was already closed", exc_info=True)
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    log.info("HTTP API listening on port %s", config.port)
    server.run()


if __name__ == "__main__":
    main()
