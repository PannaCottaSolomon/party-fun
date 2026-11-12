import unittest
from urllib.parse import urlparse

from app.config import DEFAULT_RETRY_DELAYS_MS, Config, duration_label
from app.store import MySQLStore


class RetryConfigTests(unittest.TestCase):
    def test_default_is_30s_2m_10m(self):
        config = Config.from_env({})
        self.assertEqual(config.retry_delays_ms, DEFAULT_RETRY_DELAYS_MS)
        self.assertEqual(config.retry_delays_ms, (30_000, 120_000, 600_000))
        self.assertEqual(config.max_retries, 3)
        self.assertEqual([name for name, _ in config.retry_queues], [
            "notification_service.retry.30s",
            "notification_service.retry.2m",
            "notification_service.retry.10m",
        ])

    def test_custom_delays_from_env(self):
        config = Config.from_env({"RETRY_DELAYS_MS": "5000, 60000"})
        self.assertEqual(config.retry_delays_ms, (5_000, 60_000))
        self.assertEqual(config.max_retries, 2)

    def test_each_retry_uses_its_own_delay_and_extra_retries_reuse_the_last(self):
        config = Config(retry_delays_ms=(30_000, 120_000))
        self.assertEqual(config.retry_queue_for(1), "notification_service.retry.30s")
        self.assertEqual(config.retry_queue_for(2), "notification_service.retry.2m")
        self.assertEqual(config.retry_queue_for(5), "notification_service.retry.2m")

    def test_repeated_delays_share_one_queue(self):
        config = Config(retry_delays_ms=(10_000, 10_000, 10_000))
        self.assertEqual(config.max_retries, 3)
        self.assertEqual(config.retry_queues, [("notification_service.retry.10s", 10_000)])

    def test_invalid_delays_are_rejected_at_startup(self):
        for bad in ("abc", "0", "-5", "30000,,x", ","):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                Config.from_env({"RETRY_DELAYS_MS": bad})

    def test_old_settings_are_ignored_with_a_warning(self):
        with self.assertLogs("app.config", level="WARNING") as logs:
            config = Config.from_env({"RETRY_DELAY_MS": "10000", "MAX_RETRIES": "3"})
        self.assertEqual(config.retry_delays_ms, DEFAULT_RETRY_DELAYS_MS)
        self.assertTrue(any("RETRY_DELAYS_MS" in line for line in logs.output))

    def test_duration_labels(self):
        self.assertEqual(duration_label(600_000), "10m")
        self.assertEqual(duration_label(30_000), "30s")
        self.assertEqual(duration_label(1_500), "1500ms")


class ConnectionSettingsTests(unittest.TestCase):
    """On AWS, ECS passes the host, user and password separately (from Secrets Manager)."""

    def test_rabbitmq_from_parts(self):
        config = Config.from_env({"RABBITMQ_HOST": "rabbitmq.partyfun.local", "RABBITMQ_USERNAME": "partyfun",
                                  "RABBITMQ_PASSWORD": "p@ss:w/rd#1"})
        url = urlparse(config.rabbitmq_url)
        self.assertEqual((url.hostname, url.port, url.path), ("rabbitmq.partyfun.local", 5672, "/%2F"))
        self.assertEqual(url.password, "p%40ss%3Aw%2Frd%231")  # escaped, so the URL still parses

    def test_database_from_parts(self):
        config = Config.from_env({"DB_HOST": "db.example.rds.amazonaws.com", "DB_PORT": "3306",
                                  "DB_NAME": "notifications", "DB_USERNAME": "partyfun", "DB_PASSWORD": "a#b?c%d"})
        params = MySQLStore(config.database_url)._params
        self.assertEqual(params["host"], "db.example.rds.amazonaws.com")
        self.assertEqual(params["password"], "a#b?c%d")  # what pymysql receives, unescaped
        self.assertEqual(params["database"], "notifications")

    def test_full_urls_win_and_defaults_stay(self):
        config = Config.from_env({"RABBITMQ_URL": "amqp://x:y@h:1/%2F", "RABBITMQ_HOST": "ignored",
                                  "DATABASE_URL": "memory", "DB_HOST": "ignored"})
        self.assertEqual((config.rabbitmq_url, config.database_url), ("amqp://x:y@h:1/%2F", "memory"))
        empty = Config.from_env({})
        self.assertEqual((empty.rabbitmq_url, empty.database_url), (Config().rabbitmq_url, Config().database_url))
