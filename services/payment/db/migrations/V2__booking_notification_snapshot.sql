-- Payment Service: checkout snapshot columns on `booking` (additive only: no drop, no data change).
--
-- POST /payments/checkout (the Event Service's synchronous payment call) stores:
--   event_booking_id   the Event Service's booking id; unique, so a repeated checkout never charges twice
--   participant_email  \
--   participant_name    > snapshot used only to send notify.refund to the participant after a refund succeeds
--   event_title        /
-- All columns are nullable: bookings created through POST /bookings simply leave them empty.
--
-- Apply once to an existing database (the dev profile only *validates* the schema and will refuse to start
-- without these columns):
--   mysql -h localhost -u g4t2 -p payment < db/migrations/V2__booking_notification_snapshot.sql
-- The Docker integration tests (test profile, create-drop) create them automatically.

ALTER TABLE booking
    ADD COLUMN event_booking_id  VARCHAR(64)  NULL AFTER total_amount,
    ADD COLUMN participant_email VARCHAR(255) NULL AFTER event_booking_id,
    ADD COLUMN participant_name  VARCHAR(255) NULL AFTER participant_email,
    ADD COLUMN event_title       VARCHAR(255) NULL AFTER participant_name,
    ADD CONSTRAINT uk_booking_event_booking_id UNIQUE (event_booking_id);
