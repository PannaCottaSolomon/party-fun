# Payment service

Source: [`payment/`](payment/) · Spring Boot 4.1 (Java 21) · MySQL · RabbitMQ · Stripe (sandbox only) · port 8080

## What it does

It is the only service that talks to **Stripe**.

- It charges a card for a booking and records whether Stripe really took the money.
- It refunds payments: one at a time on request, or every paid payment of an event when that event is cancelled.
- It retries failed refunds, and when a refund still fails it parks it in a dead-letter queue, alerts the admin by email, and gives the admin an API to recover it.
- It listens to Stripe webhooks so that payments and refunds Stripe finishes later are still recorded.

It keeps its own `booking` and `payment` tables. Its bookings are a payment-side copy (who, which event, how much); the events service owns the real ones.

## Where it sits

| Direction | Who | How |
|---|---|---|
| Called by | Events service | HTTP: `POST /bookings`, `POST /payments`, `GET /payments/booking/{id}`, `POST /payments/{id}/refund` |
| Called by | Frontend (card form, admin dashboard) | HTTP: `GET /payments/config`, `POST /payments/{id}/sync`, `/admin/refunds/**` |
| Called by | Stripe | HTTP webhook: `POST /payments/webhook/stripe` |
| Consumes | Events service | RabbitMQ `party.events` / `event.cancelled` |
| Publishes to | Notifications service | RabbitMQ `notifications` / `notify.refund`, `notify.admin_alert` |
| Calls | Stripe | Stripe Java SDK |
| Calls | RabbitMQ management API | HTTP, only for the DLQ's unacknowledged count |

## HTTP API

| Method and path | Auth | What it does |
|---|---|---|
| `POST /bookings` | none | Create a PENDING payment-side booking. 201 |
| `GET /bookings`, `GET /bookings/{id}`, `GET /bookings/user/{userId}` | none | Read bookings |
| `POST /payments` | none | Charge a PENDING booking. 201, also when the card is declined (`status: FAILED`) |
| `POST /payments/checkout` | none | Create-or-reuse a booking and charge it in one idempotent call. 201 new, 200 repeated |
| `GET /payments`, `GET /payments/{id}`, `GET /payments/booking/{bookingId}`, `GET /payments/user/{userId}` | none | Read payments |
| `POST /payments/{id}/sync` | none | Ask Stripe for the PaymentIntent's state and record it (only changes a PENDING payment) |
| `GET /payments/config` | none | The Stripe publishable key for the frontend's card form. 503 if not configured |
| `POST /payments/webhook/stripe` | Stripe signature | Stripe events |
| `POST /payments/{id}/refund` | Admin **or** service key | Refund one payment. 502 if it finally failed |
| `POST /payments/booking/{bookingId}/refund` | Admin | Refund every paid payment of a booking |
| `GET /admin/refunds/dlq/count` | Admin | How many failed refunds wait in the DLQ |
| `GET /admin/refunds/failed` | Admin | The REFUND_FAILED payments, with their DLQ message counts |
| `POST /admin/refunds/{paymentId}/retry` | Admin | Recover one failed refund now |
| `POST /payments/{paymentId}/retry-refund` | Admin | Deprecated alias of the line above |
| `GET /health`, `GET /actuator/health/**` | none | Health |

There is deliberately no PUT, PATCH or DELETE: payment state only changes through pay, refund and webhook.

### Who counts as an Admin

Decided by [`AuthConfig.AccessInterceptor`](payment/src/main/java/payment/config/AuthConfig.java), in this order:

1. A **Cognito access token** from the configured user pool whose `cognito:groups` contains `admin` (`CognitoVerifier`).
2. An **HS256 JWT** signed with the shared `auth_jwt_secret` and carrying `role = ADMIN` (`JwtVerifier`).
3. The **operator key** in the `X-Admin-Api-Key` header.

`X-Service-Api-Key` is a separate secret, accepted only on `POST /payments/{id}/refund`. The events service uses it for compensation refunds.

Missing or invalid credentials → 401. Valid credentials without the right role → 403. If no credential at all is configured for an endpoint group, it is disabled (403) rather than left open. The admin key and the service key must differ, or the service refuses to start.

## How the pieces fit

```
controller/   BookingController ────────► BookingService
              PaymentController ────────► PaymentService, RefundService
              AdminRefundController ────► AdminRefundService
              StripeWebhookController ──► WebhookService
              StripeConfigController, HealthController

service/      PaymentService        create / checkout / sync payments
              RefundService         every refund path; the core of the service
              AdminRefundService    dashboard view and recovery of failed refunds
              WebhookService        applies Stripe events to local payments
              RefundRecoveryJob     @Scheduled: resumes abandoned refunds
              BookingService        booking create / read

stripe/       PaymentGateway (interface) ◄── StripePaymentGateway (the only Stripe SDK user)
              WebhookPayloadParser, Money, Gateway*Result records

messaging/    EventCancelledListener   consumes event.cancelled
              PaymentEventPublisher    notify.refund, notify.admin_alert, failed-refund messages
              RefundDeadLetterQueue    reads and settles payments.refunds.dlq

repository/   BookingRepository, PaymentRepository
entity/       Booking, BookingStatus, Payment, PaymentStatus
config/       AuthConfig, CognitoVerifier, JwtVerifier, RabbitConfig, AppConfig,
              PaymentProperties, StripeProperties, CorsConfig
exception/    GlobalExceptionHandler + one class per error kind
```

### Classes by package

**`controller`**: thin. Each method validates the body (`@Valid`) and delegates.

**`service`**
- [`PaymentService`](payment/src/main/java/payment/service/PaymentService.java): `create`, `checkout`, `syncWithStripe`, and the read queries. Its private `charge()` is the single place a PaymentIntent is created.
- [`RefundService`](payment/src/main/java/payment/service/RefundService.java): `refundPaymentManually`, `refundBooking`, `refundEvent`, `refundAndReport`, the admin-recovery steps, and the shared `completeRefund` / `failRefund` used by the webhook too.
- [`AdminRefundService`](payment/src/main/java/payment/service/AdminRefundService.java): `deadLetterCount`, `failedRefunds`, `retry`.
- [`WebhookService`](payment/src/main/java/payment/service/WebhookService.java): `handle(payload, signature)`.
- [`RefundRecoveryJob`](payment/src/main/java/payment/service/RefundRecoveryJob.java): runs every 5 minutes.
- [`BookingService`](payment/src/main/java/payment/service/BookingService.java): plain CRUD without update or delete.
- `RefundTrigger`: what started a refund (`EVENT_CANCELLED`, `MANUAL`, `ADMIN_RETRY`, `RECOVERY`, `WEBHOOK`). `EventRefundSummary`: counts for one cancelled event.

**`stripe`**
- [`PaymentGateway`](payment/src/main/java/payment/stripe/PaymentGateway.java): the interface the services depend on: `createPaymentIntent`, `retrievePaymentIntent`, `refund`, `listRefunds`, `parseWebhook`. Unit tests mock it.
- [`StripePaymentGateway`](payment/src/main/java/payment/stripe/StripePaymentGateway.java): the implementation. A declined card is a normal `FAILED` result; network and API errors become `PaymentGatewayException` with a `retryable` flag.
- [`WebhookPayloadParser`](payment/src/main/java/payment/stripe/WebhookPayloadParser.java): reads the few fields needed straight from the webhook JSON. Rejects live-mode events.
- `Money.toCents`: `40.00 → 4000`.

**`messaging`**
- [`EventCancelledListener`](payment/src/main/java/payment/messaging/EventCancelledListener.java): the only `@RabbitListener`.
- [`PaymentEventPublisher`](payment/src/main/java/payment/messaging/PaymentEventPublisher.java): everything this service publishes.
- [`RefundDeadLetterQueue`](payment/src/main/java/payment/messaging/RefundDeadLetterQueue.java): `stats`, `readyCount`, `peek`, `settle`.
- Records: `RefundRequestedMessage` (incoming), `RefundFailedEvent`, `FailedRefundMessage`, `RefundNotification`.

**`config`**
- [`RabbitConfig`](payment/src/main/java/payment/config/RabbitConfig.java): exchanges, queues, bindings, and the listener container factory for `event.cancelled`.
- [`AuthConfig`](payment/src/main/java/payment/config/AuthConfig.java), [`CognitoVerifier`](payment/src/main/java/payment/config/CognitoVerifier.java), [`JwtVerifier`](payment/src/main/java/payment/config/JwtVerifier.java): access control (above).
- [`StripeProperties`](payment/src/main/java/payment/config/StripeProperties.java): refuses to start with anything other than a sandbox key.
- [`PaymentProperties`](payment/src/main/java/payment/config/PaymentProperties.java): the `payment.*` settings as records.
- [`AppConfig`](payment/src/main/java/payment/config/AppConfig.java): the `Clock`, a `TransactionOperations` bean, and `@EnableScheduling`.

## State machines

The entities enforce these themselves. `Payment` and `Booking` have no public status setter; a forbidden change throws `InvalidStateException` (409).

**Payment** ([`PaymentStatus`](payment/src/main/java/payment/entity/PaymentStatus.java))

```
PENDING ──► PAID ──► REFUND_PENDING ──► REFUNDED
   │         ▲           │    ▲             │
   ▼         │           ▼    │ retry       │ Stripe later reports that refund as failed
 FAILED ─────┘       REFUND_FAILED ◄────────┘
```

`FAILED → PAID` only happens through the `payment_intent.succeeded` webhook (the customer tried another card on the same PaymentIntent).

**Booking** ([`BookingStatus`](payment/src/main/java/payment/entity/BookingStatus.java))

```
PENDING ──paid──► CONFIRMED ──refunded──► REFUNDED
   │                  │                       ▲
   └──event cancelled─┴──► CANCELLED ──refunded┘
```

## A pattern used everywhere: no transaction across a Stripe call

Services use the `TransactionOperations` bean for short programmatic transactions instead of `@Transactional` on the whole method:

```
transaction 1   save or claim the row (status change under a row lock)
                call Stripe                    ← no database transaction open
transaction 2   record Stripe's answer
```

So a slow Stripe call never holds a database connection or lock.

## Request flows

### Paying: `POST /bookings` then `POST /payments`

This is the path the events service uses.

```
BookingController.create → BookingService.create          saves a PENDING Booking

PaymentController.create → PaymentService.create(request)
  tx 1:  load booking                      not found → 404
         booking must be PENDING           else 409
         no PENDING or PAID payment yet    else 409
         save Payment(PENDING, amount = booking.totalAmount)    amount never comes from the client
  charge(payment, paymentMethodId)
         PaymentGateway.createPaymentIntent(cents, paymentMethodId, idempotencyKey, metadata)
           Stripe error → mark the payment FAILED, rethrow (502)
  tx 2:  applyResult
           SUCCEEDED → payment.markPaid, booking → CONFIRMED
           FAILED    → payment.markFailed (declined card)
           PENDING   → remember the PaymentIntent id (awaits Stripe.js confirmation)
  ← 201 PaymentResponse (+ clientSecret while pending, failureReason when declined)
```

With a `paymentMethodId` the PaymentIntent is confirmed immediately. Without one, the frontend confirms it in the browser with the returned `clientSecret`, and either the webhook or `POST /payments/{id}/sync` records the result.

### Checkout: `POST /payments/checkout`

The same charge, but booking creation and payment are one call keyed by `eventBookingId`. `PaymentService.prepareCheckout` locks the booking with that id (`findByEventBookingIdForUpdate`), creates it if missing, and returns the existing payment if one is already PENDING or PAID, so repeating the request never charges twice. Reusing an `eventBookingId` with a different event, user, quantity or total is a 409.

### Stripe webhooks: `POST /payments/webhook/stripe`

```
StripeWebhookController.receive(rawBody, Stripe-Signature)
  → WebhookService.handle
      → PaymentGateway.parseWebhook       verify the signature, parse; bad → 400
      payment_intent.succeeded         PENDING or FAILED → PAID, booking CONFIRMED
      payment_intent.payment_failed    PENDING → FAILED
      refund events, status succeeded  REFUND_PENDING / REFUND_FAILED → REFUNDED, then notify.refund
      refund events, failed/canceled   REFUND_PENDING / REFUNDED → REFUND_FAILED, then alert + DLQ
      anything else                    IGNORED (still 200, so Stripe does not retry)
```

Webhooks are idempotent by state: an event only acts if the payment is still in the state the event moves it out of. Refund events are also matched against the refund id recorded on the payment, so late or out-of-order events about an older refund are ignored.

### Refunding one payment: `RefundService.attemptRefund`

Every refund path ends here.

```
tx:  claim(paymentId)                     row lock (SELECT ... FOR UPDATE)
       REFUNDED          → ALREADY_REFUNDED, nothing sent to Stripe
       PAID / REFUND_FAILED → REFUND_PENDING   (a retry of REFUND_FAILED starts a new refund round)
       REFUND_PENDING    → resume the interrupted attempt
       anything else     → 409

loop up to payment.refund.max-attempts (3), waiting backoff-ms × attempt between tries:
       PaymentGateway.refund(paymentIntentId, cents, idempotencyKey)
       permanent error (not retryable) → stop at once

Stripe says "succeeded"
  tx:  completeRefund → payment REFUNDED; booking REFUNDED once nothing on it is still paid
  PaymentEventPublisher.publishRefundNotification      notify.refund

Stripe says "pending"
  tx:  recordPending → stays REFUND_PENDING with the refund id; the webhook finishes it

every attempt failed
  tx:  failRefund → REFUND_FAILED with reason, attempt count and time
  PaymentEventPublisher.publishAdminAlert              notify.admin_alert
  (refundAndReport) sendToDeadLetterQueue              one message → payments.refunds.dlq
```

`POST /payments/{id}/refund` turns the last case into HTTP 502. `POST /payments/booking/{id}/refund` runs this for each payment of the booking; one failure does not block the others.

### Event cancelled (the saga step)

```
Events service ──event.cancelled──► [party.events] ──► [payments.refunds.requested]
  → EventCancelledListener.onEventCancelled(message)
       not JSON or no valid eventId → logged and dropped (acked)
  → RefundService.refundEvent(request)
       tx:  every PENDING/CONFIRMED booking of the event → CANCELLED
            collect the event's PAID and REFUND_PENDING payment ids
       for each payment: refundAndReport(paymentId, EVENT_CANCELLED, requestId)
  ← the message is acked only after every payment was handled
```

Each payment is refunded on its own, so one failing refund never fails the message; it goes to the DLQ individually. If the service stops half-way, RabbitMQ redelivers the message and the run resumes safely: REFUNDED payments are skipped, REFUND_FAILED ones already belong to the admin, and REFUND_PENDING ones reuse the same Stripe idempotency key.

An unexpected error (for example MySQL down) is retried twice with backoff and then **requeued**, never dropped (`RabbitConfig.eventCancelledContainerFactory`).

This is a retry saga, not a rollback saga: a cancelled event stays cancelled even if a refund fails. The failure goes to a human.

### Admin recovery: `POST /admin/refunds/{paymentId}/retry`

```
AdminRefundService.retry(paymentId)
  1. RefundService.claimForRecovery           REFUND_FAILED → REFUND_PENDING under a row lock
                                              a second admin gets 409
  2. RefundDeadLetterQueue.settle(paymentId, work)
       fetch DLQ messages unacknowledged; hold this payment's, put the others straight back
       work = recover(...):
         PaymentGateway.listRefunds(paymentIntentId)      what does Stripe actually have?
           a succeeded refund → RefundService.reconcileSucceededRefund   (no new refund)
           a pending refund   → RefundService.reconcilePendingRefund     (wait for the webhook)
           neither            → RefundService.refundForRecovery          (new round, new key)
       refunded or pending → ack (remove) the held messages
       still failing       → nack with requeue (keep them), payment back to REFUND_FAILED
  ← AdminRetryResponse (outcome, DLQ messages found / removed, messages left)
```

### Recovery job

`RefundRecoveryJob.resumeAbandonedRefunds` runs every 5 minutes. It finds payments that are REFUND_PENDING, have no Stripe refund id, and have not changed for 10 minutes (the service stopped mid-refund), and calls `refundAndReport` for each with trigger `RECOVERY`.

## RabbitMQ topology

Declared in `RabbitConfig`. This service owns two queues; the notification queues belong to the notifications service.

```
Events ──event.cancelled──► [party.events] (topic) ──► [payments.refunds.requested] ──► EventCancelledListener

Payment ──notify.refund──────► [notifications] (topic)      participant's refund email
Payment ──notify.admin_alert─► [notifications] (topic)      admin alert email
Payment ──payments.refunds.dlq──► [payments.refund.dlx] (direct) ──► [payments.refunds.dlq]
```

Failed refunds reach the DLQ by **application-managed routing**: the service publishes one message per failed payment itself and waits for the broker's publisher confirm. RabbitMQ does not dead-letter them on its own, because one `event.cancelled` message stands for many payments.

Nothing consumes `payments.refunds.dlq`. `RefundDeadLetterQueue` reads it on demand with `basicGet` and manual acknowledgement: ack removes a message, nack-with-requeue puts it back.

MySQL is the authoritative record of a failed refund (`REFUND_FAILED` with its reason). The DLQ message is the work item the dashboard counts. If publishing to the DLQ fails, the payment is still listed on the dashboard and can still be recovered.

## Idempotency

| Operation | Stripe idempotency key | Why |
|---|---|---|
| Create PaymentIntent | `payment-<paymentId>-create-<random UUID>` | One call per payment row; the random part keeps it unique across databases that share one Stripe sandbox |
| Refund, first round | `refund-<paymentIntentId>` | Every attempt of a round reuses it, so a lost response never creates a second refund |
| Refund, n-th admin retry | `refund-<paymentIntentId>-retry-<n>` | Stripe replays the first answer for a key for 24 hours (failures included), so a real retry needs a new key |

On top of that: REFUNDED payments are never sent to Stripe again, and the payment row is locked while a refund is claimed.

## Data model

MySQL, two tables. Hibernate only **validates** the schema (`ddl-auto: validate`); it never creates or alters tables outside the test profile.

**`booking`** ([`Booking`](payment/src/main/java/payment/entity/Booking.java)): `booking_id`, `user_id`, `event_id`, `quantity`, `total_amount`, `status`, plus `event_booking_id` (unique, checkout only), `participant_email`, `participant_name`, `event_title`.

**`payment`** ([`Payment`](payment/src/main/java/payment/entity/Payment.java)): `payment_id`, `booking_id` (the only real foreign key), `amount`, `status`, `stripe_payment_intent_id`, `stripe_refund_id`, `refund_round`, `payment_method`, `paid_at`, `refunded_at`, `refund_failure_reason`, `refund_attempts`, `refund_failed_at`.

All amounts are SGD; there is no currency column.

## Errors

Every error is a JSON `ErrorResponse` (`timestamp, status, error, message, path, fieldErrors`) from [`GlobalExceptionHandler`](payment/src/main/java/payment/exception/GlobalExceptionHandler.java).

| Exception | HTTP |
|---|---|
| Validation failures, `InvalidRequestException`, `InvalidWebhookException` | 400 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 |
| `InvalidStateException` | 409 |
| `PaymentGatewayException`, `RefundFailedException` | 502 |
| `ServiceNotConfiguredException`, database or broker unavailable | 503 |
| Anything else | 500 with a generic message |

## Configuration

Set in [`application.yml`](payment/src/main/resources/application.yml) from environment variables, or a local `dev.env` copied from [`dev.env.example`](payment/dev.env.example).

| Variable | Purpose |
|---|---|
| `stage` | Profile: `dev` (default) or `test` |
| `db_host`, `db_port`, `db_name`, `db_user`, `db_password` | MySQL |
| `rabbitmq_host`, `rabbitmq_port`, `rabbitmq_username`, `rabbitmq_password` | RabbitMQ |
| `stripe_secret_key`, `stripe_webhook_secret`, `stripe_publishable_key` | Stripe sandbox keys |
| `AWS_REGION`, `COGNITO_USER_POOL_ID` | Accept Cognito admin tokens |
| `auth_jwt_secret`, `auth_jwt_issuer` | Accept HS256 admin JWTs |
| `payment_admin_api_key`, `payment_service_api_key` | Operator key and service-to-service key |
| `refund_max_attempts`, `refund_backoff_ms` | Default 3 and 1000 |
| `admin_alert_enabled` | Default `true` |
| `rabbitmq_management_url` | For the DLQ's unacknowledged count |
| `cors_allowed_origins` | Default `http://localhost:5173` |

## Things worth knowing

- **The booking and payment endpoints are unauthenticated.** Only the refund and admin endpoints are checked. Anyone who can reach the port can create bookings, start payments and list every payment. `CognitoVerifier`'s comment notes the port is reachable without the gateway.
- **The schema must already exist.** `db/migrations/` holds V2 to V4 as plain SQL files to apply by hand; there is no V1 (the base tables) and no migration tool wired in. With a missing column the service refuses to start.
- **Sandbox only, enforced.** A secret key that is not `sk_test_` / `rk_test_` stops startup, and live-mode webhook events are rejected.
- **The events service uses `POST /bookings` + `POST /payments`, not `/payments/checkout`.** So `event_booking_id` stays null on its bookings, and the "Booking ref" in a refund email is this service's booking id, not the events service's booking id shown in the signup email.
- **A refund email is skipped when the booking has no event title.** `RefundNotification.hasRequiredFields` needs a user id and an event title; the refund itself still succeeds.
- **The HS256 JWT path looks like a leftover.** `dev.env.example` says to copy the authentication service's `AUTH_JWT_SECRET`, but that service now only works with Cognito tokens and has no such setting.
- **Refund retries sleep on the calling thread.** With the defaults, a refund that fails three times blocks the HTTP request (or the listener) for about 3 seconds of backoff plus the Stripe calls.
