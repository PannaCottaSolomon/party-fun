-- Payment Service: why a refund finally failed, on `payment` (additive only: no drop, no data change).
--
-- When a payment's refund attempts are exhausted it becomes REFUND_FAILED and Payment publishes one message for it
-- to payments.refunds.dlq. MySQL stays the authoritative record of the failure; these columns are what the Admin
-- dashboard shows (GET /admin/refunds/failed). They are cleared once a refund of the payment succeeds.
--   refund_failure_reason  last failure reason (Stripe's message / error code; never card data or keys)
--   refund_attempts        Stripe refund attempts made in that round (1 initial attempt + retries)
--   refund_failed_at       when the round finally failed
--
-- Apply once to an existing database (the dev profile only *validates* the schema and will refuse to start
-- without these columns):
--   mysql -h localhost -u g4t2 -p payment < db/migrations/V4__payment_refund_failure.sql
-- The Docker integration tests (test profile, create-drop) create them automatically.

ALTER TABLE payment
    ADD COLUMN refund_failure_reason VARCHAR(500) NULL AFTER refund_round,
    ADD COLUMN refund_attempts INT NOT NULL DEFAULT 0 AFTER refund_failure_reason,
    ADD COLUMN refund_failed_at TIMESTAMP NULL AFTER refund_attempts;
