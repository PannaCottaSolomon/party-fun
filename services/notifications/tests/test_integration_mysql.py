"""The notification log against a REAL MySQL database (as used on AWS RDS).

Skipped unless INTEGRATION_MYSQL_URL is set. The GitLab pipeline starts a
MySQL container and sets it; locally you can run:

    docker run -d --rm -p 3306:3306 -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=notifications mysql:8.4
    export INTEGRATION_MYSQL_URL=mysql://root:test@127.0.0.1:3306/notifications
    python -m pytest tests/test_integration_mysql.py
"""
import os
import time
import unittest

from app.store import MySQLStore, StoreError
from tests.test_reliability_and_ops import StoreContract

URL = os.environ.get("INTEGRATION_MYSQL_URL")


@unittest.skipUnless(URL, "set INTEGRATION_MYSQL_URL to run against a real MySQL")
class MySQLStoreTests(StoreContract, unittest.TestCase):
    def make_store(self):
        store = MySQLStore(URL)
        deadline = time.monotonic() + 120  # the MySQL container can take a while to boot
        while True:
            try:
                store.find(limit=1)  # also creates the table
                break
            except StoreError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(2)
        store._run("DELETE FROM notifications")
        return store


if __name__ == "__main__":
    unittest.main()
