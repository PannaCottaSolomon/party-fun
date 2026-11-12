-- Payment Service: refund round on `payment` (additive only: no drop, no data change).
--
-- refund_round is part of the Stripe idempotency key of a refund:
--   round 0   refund-{stripe_payment_intent_id}            (first refund attempt)
--   round n   refund-{stripe_payment_intent_id}-retry-{n}  (n-th retry of a REFUND_FAILED payment, e.g. Admin retry-refund)
-- Stripe replays the first answer for an idempotency key (failures included) for at least 24 hours, so a retry
-- needs a new key to really try again. Stripe never refunds more than the charge, so this cannot refund twice.
--
-- Apply once to an existing database (the dev profile only *validates* the schema and will refuse to start
-- without this column):
--   mysql -h localhost -u g4t2 -p payment < db/migrations/V3__payment_refund_round.sql
-- The Docker integration tests (test profile, create-drop) create it automatically.

ALTER TABLE payment
    ADD COLUMN refund_round INT NOT NULL DEFAULT 0 AFTER stripe_refund_id;
