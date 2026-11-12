"""Test package setup.

If the real `pika` package isn't installed (e.g. running the unit tests on a
laptop without the requirements), install a tiny stand-in before any test
imports app.consumer. CI and Docker have the real pika, so this does nothing there.
It lives here, rather than in a test module, so import sorting can't move it
below the imports that need it.
"""
import sys
import types

try:
    import pika
except ImportError:
    pika = types.ModuleType("pika")
    exc = types.ModuleType("pika.exceptions")

    class AMQPError(Exception):
        pass

    class UnroutableError(AMQPError):
        pass

    class NackError(AMQPError):
        pass

    exc.AMQPError, exc.UnroutableError, exc.NackError = AMQPError, UnroutableError, NackError

    class BasicProperties:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    pika.exceptions = exc
    pika.BasicProperties = BasicProperties
    pika.BlockingConnection = pika.URLParameters = None
    sys.modules["pika"], sys.modules["pika.exceptions"] = pika, exc
