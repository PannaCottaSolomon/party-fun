# Payment Service

The party.fun Payment Service owns **bookings** and **payments**. The Event Service calls it synchronously to charge
a ticket through the real **Stripe** API, in a Stripe **sandbox** (no real money). When the Event Service cancels an
event (`event.cancelled` on **RabbitMQ**, a choreography-based Saga), Payment refunds every paid participant
**individually, inside the service**, and emails each of them through the Notification Service (`notify.refund`).
A refund that still fails is kept as one message in the **dead-letter queue** `payments.refunds.dlq`, the Admin is
alerted (`notify.admin_alert`), and an **Admin** (JWT role `ADMIN`) reviews and recovers it from the Admin dashboard
API; a recovered refund's DLQ message is acknowledged and removed.

Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL · Stripe Java SDK · Spring AMQP · Docker · GitLab CI

---

## Contents

- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration) (including [what you need from Stripe](#stripe-sandbox))
- [Running locally](#running-locally)
- [Tests](#tests)
- [Docker](#docker)
- [API specification](#api-specification) (request bodies, responses, [Bruno/Postman walkthrough](#testing-with-bruno-or-postman))
- [Database schema](#database-schema)
- [RabbitMQ](#rabbitmq) (exchanges, queues, bindings and the full communication table)
- [Refund Retry Saga](#refund-retry-saga) (how each refund is processed)
- [Dead-letter queue](#dead-letter-queue) and [Admin recovery](#admin-recovery-of-failed-refunds)
- [Differences from the previous architecture](#differences-from-the-previous-architecture) and
  [migrating an existing broker](#migrating-an-existing-rabbitmq-broker)
- [Notification Service compatibility](#notification-service-compatibility)
- [Changes needed outside the Payment Service](#changes-needed-outside-the-payment-service)
- [Diagrams](#diagrams)
- [GitLab CI/CD](#gitlab-cicd)

---

## Architecture

```
                 HTTP (sync)                              HTTPS (sync, Stripe Java SDK)
  Event Service ── POST /payments/checkout ───▶ Payment Service ─────────────▶ Stripe API (sandbox)
     (refund: X-Service-Api-Key)                │     ▲    │                       │
  Admin (frontend) ── Bearer JWT, role ADMIN ─▶ │     │    │   MySQL `payment`     │ signed webhooks
     /admin/refunds/... (count, list, retry)    │     │    └── (booking, payment)  ▼
                                                │     └─────────── POST /payments/webhook/stripe
                                                │
   Event Service ──event.cancelled──▶ [party.events] ──▶ payments.refunds.requested ──▶ Payment Service
                                      cancels the event's bookings, then refunds each paid payment itself
                                      (no per-payment queue; ≤ 3 Stripe attempts each):
                     refunded  ──notify.refund──────▶ [notifications] ──▶ notifications_service ──▶ participant
                     failed    ──payments.refunds.dlq──▶ [payments.refund.dlx] ──▶ payments.refunds.dlq
                               ──notify.admin_alert─▶ [notifications] ──▶ notifications_service ──▶ IT Support
   Admin recovery (HTTP): check Stripe, refund again if safe; on success the DLQ message is acked (removed)
```

| Package (`src/main/java/payment/…`) | Responsibility |
|---|---|
| `controller` | Thin REST controllers: validate input, call a service, return DTOs |
| `dto` | Request/response records (entities are never exposed directly) |
| `service` | Business logic: `BookingService`, `PaymentService`, `RefundService` (refunds and the Saga step), `AdminRefundService` (failed-refund dashboard and recovery), `RefundRecoveryJob`, `WebhookService` |
| `entity` | JPA entities `Booking`, `Payment` and their status state machines |
| `repository` | Spring Data repositories (persistence only) |
| `stripe` | `PaymentGateway` abstraction and its one implementation, `StripePaymentGateway` (real Stripe API, sandbox only) |
| `messaging` | RabbitMQ consumer (`EventCancelledListener`), producer (`PaymentEventPublisher`), DLQ access (`RefundDeadLetterQueue`), message records |
| `config` | Properties, RabbitMQ topology, CORS, role-based access (`AuthConfig`: Admin JWT, operator key, service key), clock/transaction beans |
| `exception` | Domain exceptions and the global JSON error handler |

Design rules:

- **Data ownership.** The service owns only `booking` and `payment`. `user_id` and `event_id` are IDs from
  other services and are **not** foreign keys. The service never queries another service's database. "Payments
  of a user" and "payments of an event" are joins inside this database.
- **Currency.** Every amount is **SGD**. There is no currency column, and Stripe calls pass `currency=sgd`.
- **Stripe isolation.** Only `StripePaymentGateway` touches the Stripe SDK. No database transaction stays
  open during a Stripe call.
- **Participant snapshot.** Payment may not query the User or Event Service, so the Event Service sends the
  participant's email and name and the event title with the booking (`POST /bookings` or the checkout call). Payment stores them on its own
  `booking` row and uses them only for the `notify.refund` email.

---

## Prerequisites

| Tool | Used for |
|---|---|
| JDK 21+ and Maven 3.9+ | build, run and unit-test the service |
| MySQL with the existing `payment` database | local development |
| RabbitMQ 3.13+ | local development (e.g. `docker run -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management`) |
| Docker + Docker Compose | image build and integration tests |
| Stripe account with a **sandbox** + [Stripe CLI](https://docs.stripe.com/stripe-cli) | payments and refunds (the running service always calls the Stripe sandbox; the tests use WireMock); the CLI forwards webhooks to your machine |

---

## Configuration

All configuration comes from environment variables named in the course `dev.env` style.

When the service starts from this folder, it also reads `./dev.env` (gitignored) automatically. Copy
[`dev.env.example`](dev.env.example) to get started. Real environment variables override the file.

| Variable | Default | Purpose |
|---|---|---|
| `stage` | `dev` | Spring profile: `dev` (local MySQL, schema **validate**) or `test` (Docker integration tests) |
| `db_host` / `db_port` / `db_name` | `localhost` / `3306` / `payment` | MySQL connection (`jdbc:mysql://db_host:db_port/db_name`) |
| `db_user` / `db_password` | – | MySQL credentials (local: `g4t2` / `g4t2`) |
| `rabbitmq_host` / `rabbitmq_port` | `localhost` / `5672` | RabbitMQ |
| `rabbitmq_username` / `rabbitmq_password` | `guest` / `guest` | RabbitMQ credentials |
| `stripe_secret_key` | – | Stripe **sandbox** secret key (`sk_test_…`, or a restricted `rk_test_…`). `STRIPE_SECRET_KEY` is accepted too. Any other key (e.g. a live `sk_live_…`) **stops the service from starting**, so no real payment can happen. Without a key the service starts, but payments and refunds return 502. Never commit it or log it |
| `stripe_webhook_secret` | – | Webhook signing secret (`whsec_…`). `STRIPE_WEBHOOK_SECRET` is accepted too. **Required**: without it every webhook is rejected (400) |
| `stripe_publishable_key` | – | Sandbox **publishable** key (`pk_test_…`, same API keys page). Public by design: `GET /payments/config` hands it to the frontend's Stripe card form. Anything else (a live `pk_live_…`, or a secret `sk_`/`rk_` key that would reach browsers) stops the service from starting. Empty = `GET /payments/config` answers 503 and the frontend can't show the card form |
| `auth_jwt_secret` | – | The JWT issuer's shared HS256 secret (same value as its `AUTH_JWT_SECRET`, which is accepted here too). Admin endpoints accept `Authorization: Bearer <token>` with `role: ADMIN`. At least 32 bytes. Empty = no JWT is accepted |
| `auth_jwt_issuer` | – | Optional: when set, a token's `iss` claim must equal it (today's tokens have none) |
| `COGNITO_USER_POOL_ID` / `AWS_REGION` | – / `ap-southeast-1` | The Amazon Cognito user pool. When set, Admin endpoints also accept an Admin's **Cognito access token** (a user in the `admin` group), which is what the frontend's Admin dashboard sends through the API Gateway. Empty = Cognito tokens are not accepted |
| `payment_admin_api_key` | – | **Operator fallback** for the Admin endpoints, sent as `X-Admin-Api-Key` (`PAYMENT_ADMIN_API_KEY` is accepted too), e.g. while the JWT issuer can't issue `ADMIN` tokens yet. Empty = off. With neither this nor `auth_jwt_secret`, Admin endpoints return 403 |
| `payment_service_api_key` | – | A **different** shared secret for internal service-to-service refunds, sent as `X-Service-Api-Key` (`PAYMENT_SERVICE_API_KEY` is accepted too). Only accepted on `POST /payments/{paymentId}/refund`. The service refuses to start if it equals the admin key |
| `rabbitmq_management_url` | `http://<rabbitmq_host>:15672` | RabbitMQ management HTTP API, used only for the Admin dashboard's DLQ counts (ready / unacknowledged). If unreachable, the count falls back to AMQP (ready only) |
| `cors_allowed_origins` | `http://localhost:5173` | Comma-separated origins allowed to call the API (frontend) |
| `refund_max_attempts` | `3` | Stripe refund attempts per payment **in total** (1 initial attempt + 2 retries) before it is `REFUND_FAILED` |
| `refund_recovery_stale_after` / `refund_recovery_interval` | `PT10M` / `PT5M` | `RefundRecoveryJob`: a `REFUND_PENDING` payment with no Stripe refund, unchanged for this long, was abandoned (e.g. the service stopped) and is resumed; how often it looks |
| `refund_backoff_ms` | `1000` | Base delay between attempts (attempt *n* waits *n* × this) |
| `admin_alert_enabled` | `true` | Publish `notify.admin_alert` when a refund finally fails |
| `notification_exchange` | `notifications` | Exchange of the existing Notification Service |
| `port` | `8080` | HTTP port |

### Local MySQL

The development database already exists: `localhost:3306`, database `payment`, user `g4t2`, password `g4t2`,
tables `booking` and `payment`.

The `dev` profile uses `spring.jpa.hibernate.ddl-auto=validate`. Hibernate checks that the entities match the
existing tables and **never creates, alters, drops or truncates anything**.

Only the `test` profile uses `create-drop`. It ignores `db_host` and always targets the throw-away Docker host
`mysql`, so it cannot reach your local database.

**Schema migration (once per database).** The checkout API added four nullable columns to `booking`
(`event_booking_id`, `participant_email`, `participant_name`, `event_title`). Because `validate` refuses to start
against a table without them, apply the additive script once:

```bash
mysql -h localhost -u g4t2 -p payment < db/migrations/V2__booking_notification_snapshot.sql
```

It only runs `ALTER TABLE booking ADD COLUMN …` plus a unique index on `event_booking_id`: no drop, no truncate,
existing rows unchanged. Apply the later additive scripts the same way, in order, once each:

```bash
mysql -h localhost -u g4t2 -p payment < db/migrations/V3__payment_refund_round.sql
mysql -h localhost -u g4t2 -p payment < db/migrations/V4__payment_refund_failure.sql
```

V4 adds `refund_failure_reason`, `refund_attempts` and `refund_failed_at` to `payment`: why a refund finally failed,
shown on the Admin dashboard.

Timestamps are in the service's local time zone, matching MySQL `CURRENT_TIMESTAMP`. That is UTC in containers.

### Stripe sandbox

Payment always calls the **real Stripe API** (`api.stripe.com`) through the Stripe Java SDK, in a Stripe
**sandbox**. A sandbox is an isolated Stripe test environment: payments use test cards, nothing goes to card
networks or banks, no real money moves and there are no fees. There is no fake or offline mode.

Safety: only sandbox keys are accepted (`sk_test_…` / `rk_test_…`); a live key stops the service at startup, and
webhook events marked `"livemode": true` are rejected.

**1. Get the secret key.** In the [Stripe Dashboard](https://dashboard.stripe.com), open the account picker →
**Sandboxes** → create or open a sandbox → **Developers → API keys** → copy the **Secret key** (`sk_test_…`). The
publishable key (`pk_test_…`) is only for a frontend using Stripe.js and is not used here.

**2. Forward webhooks with the [Stripe CLI](https://docs.stripe.com/stripe-cli).** Stripe can't reach
`localhost`, so the CLI receives the sandbox's events and forwards them:

```bash
npm install -g @stripe/cli            # or scoop / the zip from github.com/stripe/stripe-cli
stripe login                          # pick the same sandbox in the browser
stripe listen --forward-to localhost:8080/payments/webhook/stripe
# prints: Ready! Your webhook signing secret is whsec_...   <- copy it; keep this terminal open
```

**3. Configure.** Put the values in `services/payment/dev.env` (copy [`dev.env.example`](dev.env.example);
gitignored). Both `docker compose` and Maven read it. The frontend (`npm run dev` and its Docker nginx) reads
`payment_admin_api_key` / `payment_service_api_key` from `user-interfaces/frontend/.env`, so put the same two keys
there.

```properties
stripe_secret_key=sk_test_...
stripe_webhook_secret=whsec_...
payment_admin_api_key=<make one up, e.g. openssl rand -hex 32>
payment_service_api_key=<a different one>
```

Then restart Payment so it reads them: `docker compose up -d --build --force-recreate payment` (from
`services/payment`), or restart `mvn spring-boot:run`.

| Value | Where it comes from | Variable |
|---|---|---|
| Secret key `sk_test_…` | Sandbox → Developers → API keys | `stripe_secret_key` |
| Webhook signing secret `whsec_…` | printed by `stripe listen` (or Workbench → Webhooks → your endpoint, for a public URL) | `stripe_webhook_secret` |
| Publishable key `pk_test_…` | Sandbox → Developers → API keys (only needed for the frontend's card form) | `stripe_publishable_key` |
| Admin API key | not from Stripe: make one up | `payment_admin_api_key` |
| Service API key | not from Stripe: make up a second, different one | `payment_service_api_key` (and the same value in the Event Service) |

**Test cards** (Stripe test PaymentMethods; send them as `paymentMethodId`):

| `paymentMethodId` | Result |
|---|---|
| `pm_card_visa` | succeeds: `PAID`; refunds succeed |
| `pm_card_visa_chargeDeclined` | declined: `FAILED` with `failureReason` (`generic_decline`) |
| `pm_card_visa_chargeDeclinedInsufficientFunds` | declined: `FAILED` (`insufficient_funds`) |
| `pm_card_threeDSecure2Required` | needs 3D Secure: stays `PENDING` with a `clientSecret` (completed by Stripe.js in a frontend) |
| `pm_card_refundFail` | payment succeeds; a refund first succeeds, then Stripe sends `refund.failed`: `REFUNDED` → `REFUND_FAILED`, DLQ and admin alert |
| *(left out)* | `PENDING` with a `clientSecret`; confirm it with Stripe.js or `stripe payment_intents confirm <pi_id> -d payment_method=pm_card_visa` |

**Statuses are never reported early.** A payment is `PAID` only when Stripe says `succeeded`. A refund is
`REFUNDED` only when Stripe says `succeeded`; a `pending` refund stays `REFUND_PENDING` until the webhook
confirms it, and a refund Stripe reverses later becomes `REFUND_FAILED`.

No test calls Stripe: unit tests mock `PaymentGateway`, and the Stripe adapter tests and Docker integration tests
use WireMock standing in for api.stripe.com; see [Tests](#tests). The running service (local stack, AWS) always
calls the real Stripe sandbox.

---

## Running locally

```bash
cp dev.env.example dev.env        # then fill in stripe_secret_key (sk_test_...) and the other keys
mvn spring-boot:run               # or: mvn package && java -jar target/payment.jar
curl localhost:8080/health        # {"service":"payment","status":"UP"}
```

MySQL and RabbitMQ must be running. On startup the service declares its RabbitMQ exchanges and queues; these
declarations are idempotent.

---

## Tests

### Unit tests (JUnit 5 + Mockito)

```bash
mvn test                 # unit + @WebMvcTest controller tests
mvn verify               # same, plus JaCoCo coverage report in target/site/jacoco/
mvn checkstyle:check     # static analysis (ci/checkstyle.xml)
mvn compile spotbugs:check
```

The tests cover:
- booking creation, validation and lookup
- checkout: success, decline, pending (never `PAID`), validation, the SGD total in cents (`12.50 × 3 = 3750`),
  a repeated `eventBookingId` returning the same payment without calling Stripe again, and 409 on a mismatch
- payment creation: success, decline, pending, Stripe error; lookups by ID, booking and user
- refund success with `notify.refund`, fail-then-succeed retries, **exactly 3 attempts** for transient errors,
  a permanent Stripe error stopping after 1 attempt, then REFUND_FAILED (reason saved in MySQL), one DLQ message
  (published with a broker confirm) and `notify.admin_alert`
- a pending Stripe refund: kept `REFUND_PENDING`, completed or failed by webhook
- event cancellation refunding every payment individually inside the service: mixed results (2 refunded + 1
  failed → exactly 1 DLQ message, the others untouched), a duplicate `event.cancelled`, resuming after a crash
  with the same idempotency key, a DLQ publish failure not stopping the others, an unexpected error propagating
  so the message is redelivered, and the Event Service's exact cancellation message
- Admin recovery (`AdminRefundServiceTest`, `RefundDeadLetterQueueTest`): Stripe checked first (already succeeded
  → recorded, pending → left to the webhook, otherwise a new refund round), success acks **only** that payment's
  DLQ messages while unrelated ones are put back first, failure keeps the same message (no duplicate), a second
  Admin gets 409, a failure with no DLQ message gets one, DLQ counts from the management API or AMQP
- `RefundRecoveryJob` resuming abandoned refunds
- the `notify.refund` and `notify.admin_alert` payloads (required fields, cents, deterministic id, no secrets),
  and that nothing is sent when the email or event title is missing
- the RabbitMQ topology: exactly 3 exchanges and 2 queues with the exact names, types and bindings, no
  dead-letter arguments, none of the removed queues or routing keys
- role-based access: Admin JWT accepted, other roles 403, forged / expired / malformed tokens 401, optional issuer
  check, operator key fallback, service key only on the refund endpoint, nothing configured = disabled; webhook
  idempotency and signature checks
- `POST /bookings` storing the optional notification snapshot, and the Event Service's current `event.cancelled`
  message
- Stripe safety: only sandbox keys accepted (live and publishable keys refused without being logged), live-mode
  webhooks rejected
- refund rounds: a new Stripe idempotency key for each retry of a `REFUND_FAILED` payment, the same key when an
  interrupted round resumes
- refunds Stripe reverses later (`REFUNDED` → `REFUND_FAILED`), and webhooks matched by refund ID so late or
  out-of-order events about another refund change nothing
- status transitions, HTTP error mapping (400/401/403/404/405/409/500/502/503), and that no internals leak

### Stripe adapter tests (WireMock)

`StripePaymentGatewayWireMockTest` runs `StripePaymentGateway` against [WireMock](https://wiremock.org/) standing in
for `api.stripe.com` (a test-only `StripeClient` with WireMock's address; nothing else changes). They run with
`mvn test` and never reach Stripe. The three layers:

| Layer | Stripe is | Covers |
|---|---|---|
| Unit tests | a Mockito mock of `PaymentGateway` | the business logic (bookings, payments, refund Saga, DLQ, webhooks) |
| Adapter tests | WireMock | what `StripePaymentGateway` sends and how it reads Stripe's answers |
| Integration tests | a WireMock container (`ci/wiremock/`) | the whole service end to end (below) |

The adapter tests check:
- the requests: amount in cents, `currency=sgd`, metadata, `confirm` only with a payment method, the
  `Idempotency-Key` header on payments and refunds, the refund list query;
- the answers: succeeded / pending (with client secret) / canceled PaymentIntents, a declined card (402) kept as a
  failed payment with its PaymentIntent, pending and failed refunds;
- the failures the sandbox can't produce on demand, and whether the refund Saga may retry them: 429, 500 and 503,
  a response slower than the read timeout, and a dropped connection are retryable; `charge_already_refunded` and an
  authentication error are not (Stripe's code is kept); error messages never contain Stripe's text or the key.

### Black-box integration tests (Docker)

The unit tests mock the database, RabbitMQ and Stripe. The integration tests check the built image with real MySQL
and real RabbitMQ, and **WireMock standing in for Stripe** (no Stripe key, no internet).

They are JUnit classes named `*IT` in
[`src/test/java/payment/integration/`](src/test/java/payment/integration):

| Class | Covers |
|---|---|
| `BookingApiIT` | health, bookings, validation, 404 |
| `PaymentApiIT` | checkout (success, idempotent repeat, decline then retry, mismatch, validation), payments, 3D Secure stays pending, signed webhooks, unsigned/live-mode webhooks rejected, lookups, 405 for PUT/PATCH/DELETE |
| `RefundIT` | credentials required (the service key refunds one payment but gets 403 on Admin endpoints; PARTICIPANT/ORGANISER tokens get 403), the Event Service's two-step flow producing `notify.refund`, refunds, transient Stripe errors (500 twice) retried with the same idempotency key until the refund succeeds, Stripe refusing a refund (1 attempt → DLQ + alert), a refund Stripe reverses later (`REFUNDED` → `REFUND_FAILED` → DLQ), the Admin dashboard (DLQ count, failed list correlated with the DLQ), **Admin recovery** removing only that payment's DLQ message, reconciling with Stripe's real state without refunding twice, only the two Payment queues declared |
| `RefundSagaIT` | the event-cancellation Saga step (`event.cancelled`): 3 paid payments → 2 refunded + 1 failed with exactly one application-published DLQ message, a duplicate cancellation changing nothing, the Event Service's current message shape; old keys such as `refunds.requested` are no longer routed |

They are black-box tests: they never start Spring or touch the database. They use only HTTP: the REST API, the
RabbitMQ management API (to publish `event.cancelled` and check `notify.refund`, `notify.admin_alert` and the
DLQ), and WireMock's admin API to set up Stripe's side of a case and see what the service sent to Stripe.

- **Stripe = WireMock:** the Payment container sends its Stripe calls to the `wiremock` container
  (`stripe_api_base`, read **only** by the `test` profile; any other profile refuses to start with it). Default
  answers live in [`ci/wiremock/mappings/`](ci/wiremock/mappings): like Stripe's test PaymentMethods,
  `pm_card_visa` succeeds, `pm_card_visa_chargeDeclined` is declined (402), `pm_card_threeDSecure2Required` needs 3D
  Secure, no method stays pending; refunds succeed (one refund id per idempotency key).
- **Per-test Stripe state** (`IntegrationClient`): `refundAtStripe` (the charge was already refunded at Stripe, so
  the service's refund gets `charge_already_refunded` and Stripe's refund list shows that refund),
  `stripeShowsRefund`, `confirmAtStripe` (as if Stripe.js confirmed it), `stripeFailsRefundsTransiently` (500 a few
  times, then success), and `refundRequests` / `stripeRefundCount` from WireMock's request log.
- **Webhooks:** the tests send Stripe events themselves, signed exactly like Stripe does (HMAC-SHA256) with a
  throw-away signing secret shared with the Payment container.

Maven Failsafe runs them only with the `integration` profile, so `mvn test` and `mvn verify` stay unit-only and
need no infrastructure.

[`ci/compose.test.yaml`](ci/compose.test.yaml) starts five containers:
- **MySQL 8.4** (official image; `MYSQL_DATABASE=payment`, `MYSQL_USER=g4t2`, hostname `mysql`)
- **RabbitMQ**
- **WireMock** (`wiremock/wiremock`), standing in for api.stripe.com
- the **Payment Service** (`stage=test`), calling WireMock with a dummy `sk_test_wiremock` key
- a Maven test runner ([`ci/Dockerfile.test`](ci/Dockerfile.test)) that runs `mvn -P integration verify`

Nothing depends on your local MySQL, and no Stripe key or internet access is needed (locally or in CI).

```bash
docker compose -f ci/compose.test.yaml up --build --abort-on-container-exit --exit-code-from tests
docker compose -f ci/compose.test.yaml cp tests:/build/target/failsafe-reports ci/reports/   # optional: JUnit XML
docker compose -f ci/compose.test.yaml down -v --remove-orphans
```

The first command's exit code is the test runner's: non-zero when any integration test fails.

---

## Docker

```bash
docker build -t partyfun-payment .
docker run --rm -p 8080:8080 --env-file dev.env \
  -e db_host=host.docker.internal -e rabbitmq_host=host.docker.internal partyfun-payment
```

The build is multi-stage: Maven + JDK 21 builds the jar, and a JRE 21 Alpine image runs it as a non-root user.
No credentials are baked in. `.dockerignore` keeps `dev.env`, `.env` and other local files out of the build
context.

To run Payment together with RabbitMQ, use [`compose.yaml`](compose.yaml) in this folder:

```bash
docker compose up -d --build      # payment on :8080, RabbitMQ on :5672 (UI :15672)
docker compose down               # add -v to also delete the RabbitMQ volume
```

Its settings come from `dev.env` (the same file as for Maven); Compose only points `db_host` at the local MySQL
(`host.docker.internal`) and `rabbitmq_host` at its RabbitMQ container. MySQL itself is not started.

The image has a `HEALTHCHECK` on `/actuator/health/liveness`, so `docker ps` shows `healthy` once the service is up.

**Smoke test.** [`ci/smoke-test.sh`](ci/smoke-test.sh) boots an image against a throw-away MySQL and RabbitMQ on a
private Docker network and checks that it works, the same way the Authentication, Events and User services do. CI
runs it on every image before pushing it; you can run it locally too (about a minute, no secrets needed):

```bash
docker build -t payment-smoke:local . && sh ci/smoke-test.sh payment-smoke:local   # ends with SMOKE TEST PASSED
```

It checks:
1. the image's `HEALTHCHECK` becomes `healthy`;
2. `/actuator/health` and `/actuator/health/readiness` are `UP`, i.e. MySQL and RabbitMQ are reachable;
3. `GET /payments` and `GET /bookings` answer `[]` from the real database, and `GET /payments/config` answers 503
   (no publishable key is set there);
4. the shipped image still protects itself: refund without a key → `401`, unsigned webhook → `400`,
   `/actuator/env` → `404`.

It runs the image with `stage=test` because a fresh database has no tables for the default `validate`; it never
calls Stripe. On failure it prints the last 100 log lines, and it always removes its containers.

---

## API specification

### Conventions

| | |
|---|---|
| Base URL | `http://localhost:8080` (in Bruno/Postman, create an environment variable `baseUrl` with this value) |
| Request bodies | JSON. Send `Content-Type: application/json` (Bruno: **Body → JSON**; Postman: **Body → raw → JSON**) |
| IDs | Positive integers. A non-numeric ID in the path (e.g. `/payments/abc`) returns **400** |
| Amounts | SGD, as decimals with up to 2 places (`40.00`). The API never takes a currency |
| Timestamps | ISO-8601 local date-time, e.g. `2026-10-07T14:03:11` |
| Admin and refund endpoints | Send an Admin token (`Authorization: Bearer <JWT, role ADMIN>`, or the operator `X-Admin-Api-Key`) or, on `POST /payments/{paymentId}/refund` only, `X-Service-Api-Key` (another service). See [Authentication and roles](#authentication-and-roles) |

**Errors** all use one JSON shape. Stack traces and credentials never appear in responses.

```json
{
  "timestamp": "2026-10-07T06:03:11.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/bookings",
  "fieldErrors": { "quantity": "must be greater than 0" }
}
```

`fieldErrors` only appears for body-validation errors.

| Status | Meaning |
|---|---|
| 400 | Invalid body (missing or invalid fields, bad JSON), non-numeric path ID, or an invalid or unsigned webhook |
| 401 | Admin or refund endpoint called without valid credentials (Admin token or key) |
| 403 | The service key on an Admin-only endpoint, or the endpoint's key is not configured (endpoint disabled) |
| 404 | The booking or payment does not exist |
| 405 | Method not allowed, e.g. `PUT`, `PATCH` or `DELETE` on payments |
| 409 | The resource is in the wrong state for this operation (e.g. paying a booking that is not `PENDING`), or an `eventBookingId` reused with different details |
| 502 | Stripe returned an error, or a refund still failed after 3 attempts |
| 503 | MySQL or RabbitMQ is unavailable, or `GET /payments/config` without a configured publishable key |

### Endpoints

| Method | Endpoint | Purpose | Sync / Async | Auth | Request body | Success |
|---|---|---|---|---|---|---|
| GET | `/payments` | Get all payments (newest first) | Synchronous | – | – | 200 `Payment[]` |
| GET | `/payments/config` | Public Stripe settings for the frontend's card form | Synchronous | – | – | 200 `{"stripePublishableKey"}` / 503 |
| GET | `/payments/{paymentId}` | Get one payment | Synchronous | – | – | 200 `Payment` |
| GET | `/payments/booking/{bookingId}` | Get the payment(s) of a booking | Synchronous | – | – | 200 `Payment[]` |
| GET | `/payments/user/{userId}` | Get a user's payments | Synchronous | – | – | 200 `Payment[]` |
| POST | `/payments/checkout` | One-call ticket payment: booking + Stripe charge, idempotent per `eventBookingId` | Synchronous (calls Stripe) | – | **JSON** | 201 / 200 `Payment` |
| POST | `/payments` | Pay an existing booking created with `POST /bookings` (the Event Service's current flow) | Synchronous (calls Stripe) | – | **JSON** | 201 `Payment` |
| POST | `/payments/{paymentId}/sync` | Record the PaymentIntent's current state from Stripe (PAID once the browser confirmed the card), without waiting for the webhook | Synchronous (calls Stripe) | – | none | 200 `Payment` |
| POST | `/payments/{paymentId}/refund` | Refund a specific payment | Synchronous (calls Stripe) | **Admin or service** | none | 200 `RefundResult` |
| POST | `/payments/booking/{bookingId}/refund` | Refund the payment(s) of a booking | Synchronous (calls Stripe) | **Admin** | none | 200 `BookingRefund` |
| GET | `/admin/refunds/dlq/count` | Failed refunds waiting in `payments.refunds.dlq` (ready / unacknowledged / total) | Synchronous | **Admin** | – | 200 `DlqCount` |
| GET | `/admin/refunds/failed` | The individually failed refunds (MySQL), each with its DLQ message count | Synchronous | **Admin** | – | 200 `FailedRefunds` |
| POST | `/admin/refunds/{paymentId}/retry` | Recover one failed refund: check Stripe, refund again if needed, remove its DLQ message on success | Synchronous (calls Stripe) | **Admin** | none | 200 `AdminRetry` |
| POST | `/payments/{paymentId}/retry-refund` | Deprecated alias of `POST /admin/refunds/{paymentId}/retry` | Synchronous | **Admin** | none | 200 `AdminRetry` |
| POST | `/payments/webhook/stripe` | Receive a Stripe webhook event | Asynchronous callback | `Stripe-Signature` | **Stripe event JSON** | 200 `WebhookResult` |
| GET | `/bookings` | Get all bookings (newest first) | Synchronous | – | – | 200 `Booking[]` |
| GET | `/bookings/{bookingId}` | Get one booking | Synchronous | – | – | 200 `Booking` |
| GET | `/bookings/user/{userId}` | Get a user's bookings | Synchronous | – | – | 200 `Booking[]` |
| POST | `/bookings` | Create a booking | Synchronous | – | **JSON** | 201 `Booking` |
| GET | `/health` | Liveness check (used by Docker Compose and CI) | Synchronous | – | – | 200 |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Health with MySQL and RabbitMQ (smoke test, probes) | Synchronous | – | – | 200 / 503 |

The refund path for **cancelled events** is the RabbitMQ message `event.cancelled`, not an HTTP endpoint (see
[RabbitMQ](#rabbitmq)).

There is no `PUT`, `PATCH` or `DELETE` on payments; they return 405. Payment state only changes through the
operations above.

### Authentication and roles

party.fun has three user roles: `PARTICIPANT`, `ORGANISER` and `ADMIN`. The **Admin** is a human user, not a service.
Admin endpoints require a verified Admin; the frontend's role toggle or any other client-supplied role is never
trusted.

| Credential | How it is checked | Allowed on |
|---|---|---|
| `Authorization: Bearer <Cognito access token>` of a user in the `admin` group | RS256 signature against the user pool's public keys (its JWKS, fetched from Cognito once and again for an unknown key id), issuer = the user pool, `token_use = access`, not expired. On when `COGNITO_USER_POOL_ID` is set; another group is 403 | every Admin and refund endpoint |
| `Authorization: Bearer <JWT>` with `role: ADMIN` | HS256 signature with the shared `auth_jwt_secret` (the JWT issuer's `AUTH_JWT_SECRET`), not expired, `iss` if `auth_jwt_issuer` is set. Verified **locally**: Payment never calls the issuer | every Admin and refund endpoint |
| `X-Admin-Api-Key` (`payment_admin_api_key`) | operator fallback, e.g. while the issuer can't issue `ADMIN` tokens yet; off when empty | every Admin and refund endpoint |
| `X-Service-Api-Key` (`payment_service_api_key`) | internal service-to-service calls (the Event Service's compensation refund) | **only** `POST /payments/{paymentId}/refund` |

| Endpoint | Admin (JWT or operator key) | Service key | Other roles' JWT |
|---|---|---|---|
| `GET /admin/refunds/dlq/count`, `GET /admin/refunds/failed`, `POST /admin/refunds/{id}/retry` | allowed | **403** | **403** |
| `POST /payments/{paymentId}/retry-refund` (deprecated alias) | allowed | **403** | **403** |
| `POST /payments/booking/{bookingId}/refund` | allowed | **403** | **403** |
| `POST /payments/{paymentId}/refund` | allowed | allowed | **403** |

- Missing, forged, expired or malformed credentials → **401**. Valid credentials without the right role → **403**.
- An endpoint none of whose credentials is configured is disabled → **403** for every caller, never left open.
- Keys are compared in constant time; tokens and keys are never logged or returned. Each accepted call is logged
  with the kind of credential (and the Admin's `sub` for a JWT).
- The admin and service keys must differ; `auth_jwt_secret` must be at least 32 bytes. Otherwise the service
  refuses to start.
- CORS allows the `Authorization` and key headers.
- **Who issues tokens:** today the Authentication Service; later the combined Event/Authentication service. Payment
  depends only on the token (shared secret + `role` claim), so the merge needs no change here as long as the
  secret is shared. The issuer must add the `ADMIN` role (see
  [Changes needed outside the Payment Service](#changes-needed-outside-the-payment-service)).

### Response objects

**`Booking`**

```json
{
  "bookingId": 7,
  "eventBookingId": "evb-1001",
  "userId": 1,
  "eventId": 9001,
  "eventTitle": "Rooftop Party",
  "quantity": 2,
  "totalAmount": 40.00,
  "currency": "SGD",
  "status": "PENDING",
  "createdAt": "2026-10-07T14:03:11",
  "updatedAt": "2026-10-07T14:03:11"
}
```

`status` is one of `PENDING`, `CONFIRMED`, `CANCELLED` or `REFUNDED`. `eventBookingId` and `eventTitle` appear only
for bookings made through `POST /payments/checkout`. The participant's email is stored but never returned.

**`Payment`**

```json
{
  "paymentId": 5,
  "bookingId": 7,
  "eventBookingId": "evb-1001",
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3QxYz2AbCdEf1234",
  "paymentMethod": "card",
  "paidAt": "2026-10-07T14:04:02",
  "createdAt": "2026-10-07T14:04:02",
  "updatedAt": "2026-10-07T14:04:02"
}
```

- `status` is one of `PENDING`, `PAID`, `FAILED`, `REFUND_PENDING`, `REFUNDED` or `REFUND_FAILED`.
- Fields that are `null` are left out. For example, `stripeRefundId` and `refundedAt` appear only once the
  payment is refunded.
- `clientSecret` (pending payments) and `failureReason` (declined payments) appear **only** in the response of
  the `POST /payments` or `POST /payments/checkout` call that created the payment.

**`RefundResult`**

```json
{
  "paymentId": 5,
  "outcome": "REFUNDED",
  "paymentStatus": "REFUNDED",
  "stripeRefundId": "re_3QxYz2AbCdEf5678",
  "attempts": 1,
  "message": "Refunded"
}
```

| `outcome` | Meaning |
|---|---|
| `REFUNDED` | Stripe refunded the payment (`succeeded`) during this request. `attempts` is 1–3. `notify.refund` was sent |
| `PENDING` | Stripe accepted the refund but hasn't completed it (`pending`). The payment stays `REFUND_PENDING` with its `stripeRefundId`; a Stripe webhook finishes it |
| `ALREADY_REFUNDED` | It was refunded earlier. Nothing was sent to Stripe (`attempts` is 0) |
| `FAILED` | The refund failed: 3 attempts for transient errors, or 1 for a permanent Stripe error. The payment is `REFUND_FAILED` (reason saved in MySQL), the Admin was alerted, and one message for it went through `payments.refund.dlx` to `payments.refunds.dlq` |
| `SKIPPED` | The payment was never paid (`PENDING`/`FAILED`), so there is nothing to refund |

**`BookingRefund`**: `{"bookingId": 7, "results": [RefundResult, ...]}`, with one result per payment of the
booking.

**`DlqCount`**: `{"queue": "payments.refunds.dlq", "ready": 2, "unacknowledged": 0, "total": 2, "source":
"management-api"}`. `ready` is read over AMQP, so it is exact and drops as soon as a recovery removes a message.
`unacknowledged` counts messages held by an Admin recovery in progress; it comes from the management API, whose
statistics can lag a few seconds, and is `null` when only AMQP was reachable (`"source": "amqp"`).

**`FailedRefunds`**: `{"count": 1, "dlq": DlqCount, "failedRefunds": [FailedRefund, ...], "unmatchedDlqMessages":
[]}`, where each `FailedRefund` is

```json
{"paymentId": 101, "bookingId": 205, "eventId": 50, "userId": 7, "amount": 30.00, "amountCents": 3000,
 "currency": "SGD", "status": "REFUND_FAILED", "failureReason": "Stripe refund request failed (code=..., status=400)",
 "refundAttempts": 3, "failedAt": "2026-10-23T09:00:00", "stripePaymentIntentId": "pi_...",
 "stripeRefundId": null, "dlqMessages": 1}
```

`dlqMessages: 0` means MySQL has the failure but the DLQ has no message for it (e.g. publishing failed); it can be
recovered all the same. `unmatchedDlqMessages` lists payment IDs whose DLQ message is stale (the payment is no
longer `REFUND_FAILED`); recovering such a payment removes it.

**`AdminRetry`**: `{"paymentId": 101, "outcome": "REFUNDED", "paymentStatus": "REFUNDED", "stripeRefundId":
"re_...", "dlqMessagesFound": 1, "dlqMessagesRemoved": 1, "remainingInDlq": 1, "message": "..."}`. `outcome` is
`REFUNDED`, `ALREADY_REFUNDED`, `PENDING` (Stripe accepted it; its webhook finishes it) or `FAILED` (still failing:
the message stays in the DLQ, `dlqMessagesRemoved: 0`).

**`WebhookResult`**: `{"eventId": "evt_test_1", "type": "payment_intent.succeeded", "action": "PROCESSED"}`.
`action` is one of:
- `PROCESSED`: the payment was updated.
- `ALREADY_PROCESSED`: a repeat. The payment had already moved on, so nothing changed.
- `IGNORED`: an unhandled event type, or a PaymentIntent this service doesn't know.

---

Each endpoint below has a table in the same layout as the course's API template: parameters, then every response
code with an example body. Error bodies always have the shape described in [Conventions](#conventions) (no stack
traces, no credentials). Any endpoint can also answer `500` with `"An unexpected error occurred"` for an unexpected
failure; the tables list it where it is the only possible failure.

### GET `/payments`

<table>
<tr><th colspan="2" align="left">GET <b>/payments</b> – Get all payments (newest first)</th></tr>
<tr><td><b>Parameters</b></td><td>No parameters</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. An empty list <code>[]</code> when there are no payments<br><br>Example Value<pre>[
  {
    "paymentId": 25,
    "bookingId": 12,
    "eventBookingId": "b-lx3k9a2f",
    "userId": 1,
    "eventId": 9001,
    "amount": 40.00,
    "currency": "SGD",
    "status": "PAID",
    "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
    "paymentMethod": "card",
    "paidAt": "2026-10-23T16:15:30",
    "createdAt": "2026-10-23T16:15:28",
    "updatedAt": "2026-10-23T16:15:30"
  },
  ...
]</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments"
}</pre></td></tr>
<tr><td>Code – 500</td><td>Description – An unexpected error occurred (details are logged, never returned)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/payments"
}</pre></td></tr>
</table>

An empty result is `200 []`, not an error.

### GET `/payments/config`

<table>
<tr><th colspan="2" align="left">GET <b>/payments/config</b> – Get Stripe settings for the frontend's card form</th></tr>
<tr><td><b>Parameters</b></td><td>No parameters</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. The key is always a sandbox publishable key (<code>pk_test_...</code>)<br><br>Example Value<pre>{
  "stripePublishableKey": "pk_test_51US6gg..."
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – Card payments are not configured: <code>stripe_publishable_key</code> is not set<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "Stripe card payments are not configured: stripe_publishable_key is not set",
  "path": "/payments/config"
}</pre></td></tr>
<tr><td>Code – 500</td><td>Description – An unexpected error occurred (details are logged, never returned)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/payments/config"
}</pre></td></tr>
</table>

The frontend's Stripe.js Payment Element needs this key. A live (`pk_live_`) or secret key is refused at startup, so only a sandbox key can ever be returned.

### GET `/payments/{paymentId}`

<table>
<tr><th colspan="2" align="left">GET <b>/payments/{paymentId}</b> – Find payment by payment ID</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>paymentId<br>int<br><i>(path)</i></td><td>Unique ID of payment to return<br><br>Example Value<pre>25</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation<br><br>Example Value<pre>{
  "paymentId": 25,
  "bookingId": 12,
  "eventBookingId": "b-lx3k9a2f",
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
  "paymentMethod": "card",
  "paidAt": "2026-10-23T16:15:30",
  "createdAt": "2026-10-23T16:15:28",
  "updatedAt": "2026-10-23T16:15:30"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>paymentId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'paymentId'",
  "path": "/payments/abc"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Payment not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Payment 99 not found",
  "path": "/payments/99"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/25"
}</pre></td></tr>
</table>

### GET `/payments/booking/{bookingId}`

<table>
<tr><th colspan="2" align="left">GET <b>/payments/booking/{bookingId}</b> – Find the payments of a booking</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>bookingId<br>int<br><i>(path)</i></td><td>Unique ID of the booking<br><br>Example Value<pre>12</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation, oldest first. It can include a declined attempt and a later successful one; <code>[]</code> if the booking has no payments yet<br><br>Example Value<pre>[
  {
    "paymentId": 25,
    "bookingId": 12,
    "eventBookingId": "b-lx3k9a2f",
    "userId": 1,
    "eventId": 9001,
    "amount": 40.00,
    "currency": "SGD",
    "status": "PAID",
    "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
    "paymentMethod": "card",
    "paidAt": "2026-10-23T16:15:30",
    "createdAt": "2026-10-23T16:15:28",
    "updatedAt": "2026-10-23T16:15:30"
  },
  ...
]</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>bookingId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'bookingId'",
  "path": "/payments/booking/abc"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Booking not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Booking 99 not found",
  "path": "/payments/booking/99"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/booking/12"
}</pre></td></tr>
</table>

### GET `/payments/user/{userId}`

<table>
<tr><th colspan="2" align="left">GET <b>/payments/user/{userId}</b> – Find a user's payments</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>userId<br>int<br><i>(path)</i></td><td>ID of the user (from the User Service)<br><br>Example Value<pre>1</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. <code>[]</code> for a user with no payments<br><br>Example Value<pre>[
  {
    "paymentId": 25,
    "bookingId": 12,
    "eventBookingId": "b-lx3k9a2f",
    "userId": 1,
    "eventId": 9001,
    "amount": 40.00,
    "currency": "SGD",
    "status": "PAID",
    "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
    "paymentMethod": "card",
    "paidAt": "2026-10-23T16:15:30",
    "createdAt": "2026-10-23T16:15:28",
    "updatedAt": "2026-10-23T16:15:30"
  },
  ...
]</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>userId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'userId'",
  "path": "/payments/user/abc"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/user/1"
}</pre></td></tr>
</table>

A join of `payment` and `booking` inside this service's own database; the User Service is never called.

### POST `/payments/checkout`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/checkout</b> – Pay for tickets in one call (books and charges through Stripe)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>body<br>object<br><i>(body)</i></td><td>Ticket purchase. The total is <code>ticketPrice × quantity</code>, computed by the service. See the field rules below the table.<br><br>Example Value<pre>{
  "eventBookingId": "b-lx3k9a2f",
  "eventId": 9001,
  "userId": 1,
  "quantity": 2,
  "ticketPrice": 20.00,
  "paymentMethodId": "pm_card_visa",
  "participantEmail": "alice@example.com",
  "participantName": "Alice Tan",
  "eventTitle": "Rooftop Party"
}</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 201</td><td>Description – Payment created. <code>status</code> is <code>PAID</code>, <code>FAILED</code> (card declined, with <code>failureReason</code>) or <code>PENDING</code> (with <code>clientSecret</code>, when no card was given)<br><br>Example Value<pre>{
  "paymentId": 25,
  "bookingId": 12,
  "eventBookingId": "b-lx3k9a2f",
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
  "paymentMethod": "card",
  "paidAt": "2026-10-23T16:15:30",
  "createdAt": "2026-10-23T16:15:28",
  "updatedAt": "2026-10-23T16:15:30"
}</pre></td></tr>
<tr><td>Code – 200</td><td>Description – This <code>eventBookingId</code> already has a pending or paid payment. It is returned and Stripe is <b>not</b> charged again<br><br>Example Value<pre>{
  "paymentId": 25,
  "bookingId": 12,
  "eventBookingId": "b-lx3k9a2f",
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
  "paymentMethod": "card",
  "paidAt": "2026-10-23T16:15:30",
  "createdAt": "2026-10-23T16:15:28",
  "updatedAt": "2026-10-23T16:15:30"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – Invalid request body (missing or invalid fields, invalid JSON, or a total above 999999.99)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/payments/checkout",
  "fieldErrors": {
    "quantity": "must be greater than or equal to 1"
  }
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – <code>eventBookingId</code> already used with a different event, user, quantity or total, or its booking is no longer <code>PENDING</code><br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "eventBookingId b-lx3k9a2f was already used for a different event, user, quantity or total",
  "path": "/payments/checkout"
}</pre></td></tr>
<tr><td>Code – 502</td><td>Description – Stripe could not be reached or rejected the request (or no <code>stripe_secret_key</code> is configured). The payment is <code>FAILED</code>; send the request again to retry<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "Stripe payment request failed (...)",
  "path": "/payments/checkout"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/checkout"
}</pre></td></tr>
</table>

Request body fields:

| Field | Type | Required | Rules |
|---|---|---|---|
| `eventBookingId` | string | **yes** | ≤ 64 characters. The Event Service's own booking ID and the **idempotency key**: the same value never charges twice |
| `eventId` | integer | **yes** | > 0 |
| `userId` | integer | **yes** | > 0 |
| `quantity` | integer | **yes** | 1–20 |
| `ticketPrice` | number | **yes** | ≥ 0.01, at most 6 digits before and 2 after the decimal point, SGD. The total must be ≤ 999999.99 |
| `paymentMethodId` | string | no | A Stripe test PaymentMethod, e.g. `pm_card_visa`. Leave it out to get a `clientSecret` for Stripe.js |
| `participantEmail` | string | no | Stored but no longer used: the refund email goes to the booking's `userId` |
| `participantName` | string | no | Stored but no longer used: the Notification Service has the name |
| `eventTitle` | string | no* | Needed for the refund email |

\* Without `eventTitle` a later refund still works, but no `notify.refund` email can be sent.

`paymentMethodId` chooses the outcome:

| `paymentMethodId` | Payment `status` | Booking afterwards | Notes |
|---|---|---|---|
| `"pm_card_visa"` | `PAID` | `CONFIRMED` | Refunds succeed |
| `"pm_card_visa_chargeDeclined"` | `FAILED`, with `failureReason` | stays `PENDING` | Send the same `eventBookingId` again with another card to retry |
| `"pm_card_threeDSecure2Required"` | `PENDING`, with `clientSecret` | stays `PENDING` | Needs 3D Secure in Stripe.js |
| `"pm_card_refundFail"` | `PAID` | `CONFIRMED` | A refund first succeeds, then Stripe reverses it (`refund.failed`) → `REFUND_FAILED`, DLQ |
| *(left out)* | `PENDING`, with `clientSecret` | stays `PENDING` | Completed by Stripe.js + the `payment_intent.succeeded` webhook |

Idempotency per `eventBookingId`:

| The booking already has… | Result |
|---|---|
| nothing (first call) | `201`, a new payment (Stripe idempotency key `payment-{paymentId}-create-{random UUID}`) |
| a `PAID` or `PENDING` payment | `200` with that payment; Stripe is not called again |
| only `FAILED` payments (e.g. a declined card) | `201`, a new attempt on the same booking |
| a different `eventId`, `userId`, `quantity` or total | `409` |

A declined card is still a `201` with status `FAILED`: treat the ticket as paid **only** when `status` is `PAID`.
On a repeat (`200`), `clientSecret` and `failureReason` are not included.

### POST `/payments`

<table>
<tr><th colspan="2" align="left">POST <b>/payments</b> – Pay an existing booking</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>body<br>object<br><i>(body)</i></td><td>Booking to pay. The amount always comes from the booking; never send one.<br><br>Example Value<pre>{
  "bookingId": 7,
  "paymentMethodId": "pm_card_visa"
}</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 201</td><td>Description – Payment created: <code>PAID</code>, <code>FAILED</code> (with <code>failureReason</code>) or <code>PENDING</code> (with <code>clientSecret</code>), as for checkout<br><br>Example Value<pre>{
  "paymentId": 27,
  "bookingId": 7,
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3US9...",
  "paymentMethod": "card",
  "paidAt": "2026-10-23T16:25:02",
  "createdAt": "2026-10-23T16:25:01",
  "updatedAt": "2026-10-23T16:25:02"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – Invalid request body<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/payments",
  "fieldErrors": {
    "bookingId": "must not be null"
  }
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Booking not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Booking 7 not found",
  "path": "/payments"
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – The booking can't be paid: it isn't <code>PENDING</code>, or it already has a pending or paid payment<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Booking 7 already has a pending or completed payment",
  "path": "/payments"
}</pre></td></tr>
<tr><td>Code – 502</td><td>Description – Stripe could not be reached or rejected the request<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "Stripe payment request failed (...)",
  "path": "/payments"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments"
}</pre></td></tr>
</table>

Step 2 of the Event Service's current flow (step 1 is `POST /bookings`); [`POST /payments/checkout`](#post-paymentscheckout)
does both in one idempotent call.

| Field | Type | Required | Rules |
|---|---|---|---|
| `bookingId` | integer | **yes** | > 0; the booking must exist and be `PENDING` |
| `paymentMethodId` | string | no | ≤ 255 characters. A Stripe test PaymentMethod; the [checkout table](#post-paymentscheckout) applies here too. Leave it out to get a `clientSecret` |

A declined card is still a `201`: the payment was created, with status `FAILED`, and the booking can be paid again.

### POST `/payments/{paymentId}/sync`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/{paymentId}/sync</b> – Record the payment's current state from Stripe</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>paymentId<br>int<br><i>(path)</i></td><td>Unique ID of the payment to check at Stripe<br><br>Example Value<pre>25</pre></td></tr>
<tr><td>body<br><i>(body)</i></td><td>None. Send the POST with an empty body.</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. A <code>PENDING</code> payment whose PaymentIntent Stripe shows as <code>succeeded</code> is now <code>PAID</code> (booking <code>CONFIRMED</code>); <code>canceled</code> → <code>FAILED</code>; still processing → stays <code>PENDING</code>. Any other payment is returned unchanged, without calling Stripe<br><br>Example Value<pre>{
  "paymentId": 25,
  "bookingId": 12,
  "eventBookingId": "b-lx3k9a2f",
  "userId": 1,
  "eventId": 9001,
  "amount": 40.00,
  "currency": "SGD",
  "status": "PAID",
  "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
  "paymentMethod": "card",
  "paidAt": "2026-10-23T16:15:30",
  "createdAt": "2026-10-23T16:15:28",
  "updatedAt": "2026-10-23T16:15:30"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>paymentId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'paymentId'",
  "path": "/payments/abc/sync"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Payment not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Payment 99 not found",
  "path": "/payments/99/sync"
}</pre></td></tr>
<tr><td>Code – 502</td><td>Description – Stripe could not be reached; the payment is unchanged, so call it again<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "Stripe payment lookup failed (code=null, status=null)",
  "path": "/payments/25/sync"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/25/sync"
}</pre></td></tr>
</table>

When the browser confirms a card with Stripe.js, Stripe tells the Payment Service through the
`payment_intent.succeeded` webhook, which needs `stripe listen` locally. This endpoint lets the frontend ask instead:
the Payment Service retrieves the PaymentIntent from Stripe and applies the same transition as the webhook. Both are
idempotent with each other (the second one finds the payment already `PAID`). It only moves a payment forward, so it
needs no credentials, like `GET /payments/{paymentId}`.

### POST `/payments/{paymentId}/refund`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/{paymentId}/refund</b> – Refund one payment through Stripe</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>paymentId<br>int<br><i>(path)</i></td><td>Unique ID of the payment to refund<br><br>Example Value<pre>25</pre></td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td>X-Service-Api-Key<br>string<br><i>(header)</i></td><td>Internal service key (<code>payment_service_api_key</code>), e.g. the Event Service. Send <b>one</b> of these three headers.<br><br>Example Value<pre>&lt;service key&gt;</pre></td></tr>
<tr><td>body<br><i>(body)</i></td><td>None. Send the POST with an empty body.</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. <code>outcome</code> is <code>REFUNDED</code>, <code>ALREADY_REFUNDED</code> (nothing sent to Stripe again) or <code>PENDING</code> (Stripe hasn't finished; its webhook completes it)<br><br>Example Value<pre>{
  "paymentId": 25,
  "outcome": "REFUNDED",
  "paymentStatus": "REFUNDED",
  "stripeRefundId": "re_3US7Od6qXRb08W0n01Vj",
  "attempts": 1,
  "message": "Refunded"
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...) or X-Service-Api-Key",
  "path": "/payments/25/refund"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – A valid JWT without the <code>ADMIN</code> role, or no credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This endpoint requires the ADMIN role",
  "path": "/payments/25/refund"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Payment not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Payment 99 not found",
  "path": "/payments/99/refund"
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – The payment can't be refunded (it was never paid)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Payment 26 cannot change from FAILED to REFUND_PENDING",
  "path": "/payments/26/refund"
}</pre></td></tr>
<tr><td>Code – 502</td><td>Description – The refund failed after every attempt. The payment is <code>REFUND_FAILED</code>, one message for it went to <code>payments.refunds.dlq</code> and the Admin was alerted<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "Refund for payment 25 failed after 3 attempts; it is marked REFUND_FAILED and was sent to the refund dead-letter queue",
  "path": "/payments/25/refund"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/25/refund"
}</pre></td></tr>
</table>

Works on `PAID`, `REFUND_FAILED` (a new refund round) and `REFUND_PENDING` (resumes an interrupted refund). It runs
the [refund steps](#refund-retry-saga): up to 3 Stripe attempts in total with backoff for transient errors, and the
same idempotency key every time, so it can take a few seconds. On `REFUNDED` the booking becomes `REFUNDED` once
nothing on it is still paid, and `notify.refund` is sent.

### POST `/payments/booking/{bookingId}/refund`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/booking/{bookingId}/refund</b> – Refund the payment(s) of a booking (Admin)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>bookingId<br>int<br><i>(path)</i></td><td>Unique ID of the booking<br><br>Example Value<pre>12</pre></td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>). <code>X-Service-Api-Key</code> is <b>not</b> accepted here (403).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td>body<br><i>(body)</i></td><td>None. Send the POST with an empty body.</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation: one result per payment. Each <code>outcome</code> is <code>REFUNDED</code>, <code>ALREADY_REFUNDED</code>, <code>PENDING</code>, <code>FAILED</code> (that payment is <code>REFUND_FAILED</code> and in the DLQ) or <code>SKIPPED</code> (never paid). One failure doesn't stop the others or turn the response into a 502<br><br>Example Value<pre>{
  "bookingId": 12,
  "results": [
    {
      "paymentId": 25,
      "outcome": "REFUNDED",
      "paymentStatus": "REFUNDED",
      "stripeRefundId": "re_3US...",
      "attempts": 1,
      "message": "Refunded"
    }
  ]
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)",
  "path": "/payments/booking/12/refund"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – Valid credentials without the Admin role: the service key, a JWT with another role, or no Admin credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This is an Admin endpoint: X-Service-Api-Key is not accepted; send an Admin token (Authorization: Bearer ...)",
  "path": "/payments/booking/12/refund"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Booking not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Booking 99 not found",
  "path": "/payments/booking/99/refund"
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – Nothing to refund: the booking has no paid payments<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Booking 12 has no paid payments to refund",
  "path": "/payments/booking/12/refund"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/booking/12/refund"
}</pre></td></tr>
</table>

### POST `/payments/webhook/stripe`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/webhook/stripe</b> – Receive a Stripe webhook event</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>Stripe-Signature<br>string<br><i>(header)</i></td><td>Stripe's signature, verified with <code>stripe_webhook_secret</code>. <b>Required</b>: unsigned or forged events are rejected.<br><br>Example Value<pre>t=1761206130,v1=5257a8...</pre></td></tr>
<tr><td>body<br>object<br><i>(body)</i></td><td>A Stripe event, as sent by Stripe or <code>stripe listen</code>. Only <code>id</code>, <code>type</code>, <code>livemode</code> and <code>data.object</code> are read.<br><br>Example Value<pre>{
  "id": "evt_3US7...",
  "type": "payment_intent.succeeded",
  "livemode": false,
  "data": {
    "object": {
      "id": "pi_3US7Od6qXRb08W0n0abc",
      "object": "payment_intent"
    }
  }
}</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – Event accepted. <code>action</code> is <code>PROCESSED</code>, <code>ALREADY_PROCESSED</code> (a repeat; nothing changed) or <code>IGNORED</code> (not one of our payments, or not a relevant event)<br><br>Example Value<pre>{
  "eventId": "evt_3US7...",
  "type": "payment_intent.succeeded",
  "action": "PROCESSED"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – Missing or invalid signature, no signing secret configured, a body that isn't a Stripe event, or a live-mode event (<code>"livemode": true</code>)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid Stripe webhook signature",
  "path": "/payments/webhook/stripe"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable (Stripe retries the event later)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/webhook/stripe"
}</pre></td></tr>
</table>

Locally Stripe reaches it through `stripe listen` (see [Stripe sandbox](#stripe-sandbox)). Hand-made bodies in
Bruno/Postman are rejected because they aren't signed: use real events from `stripe listen` / `stripe trigger`.

| `type` | Fields read from `data.object` | Effect |
|---|---|---|
| `payment_intent.succeeded` | `id` = the payment's `stripePaymentIntentId`; optional `payment_method_types` | `PENDING` → `PAID`, booking → `CONFIRMED`. Also `FAILED` → `PAID`: a customer whose first card was declined can try another card on the same PaymentIntent |
| `payment_intent.payment_failed` | `id` | `PENDING` → `FAILED` |
| `refund.created` / `refund.updated` / `charge.refund.updated` with `status: "succeeded"` | `id` (refund ID), `payment_intent`, `status` | `REFUND_PENDING` (same refund, or none recorded yet) or `REFUND_FAILED` (any refund except the one Stripe reported as failed) → `REFUNDED`, sends `notify.refund` |
| `charge.refunded` | `payment_intent`, `refunded` must be `true` | Same as above, when no refund ID is recorded to compare with |
| `refund.failed`, or a refund event with `status: "failed"`/`"canceled"` | `id`, `payment_intent`, `status` | `REFUND_PENDING`, or a `REFUNDED` payment whose recorded refund is this one (Stripe reversed it, e.g. `pm_card_refundFail`) → `REFUND_FAILED`, sends `notify.admin_alert` and one message to `payments.refunds.dlq` (`trigger: "WEBHOOK"`) |
| a refund event about a different refund than the one recorded, or with `status: "pending"` | – | Nothing (`IGNORED`): webhooks can arrive late or out of order |
| anything else | – | Nothing (`IGNORED`) |

A refund event looks like `{"id": "evt_...", "type": "refund.updated", "data": {"object": {"id": "re_...",
"payment_intent": "pi_...", "status": "succeeded"}}}`. The signing secret is never echoed back.

### GET `/admin/refunds/dlq/count`

<table>
<tr><th colspan="2" align="left">GET <b>/admin/refunds/dlq/count</b> – Count the failed refunds waiting in the refund DLQ (Admin)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>). <code>X-Service-Api-Key</code> is <b>not</b> accepted here (403).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation, straight from RabbitMQ. <code>source</code> is <code>management-api</code>, or <code>amqp</code> as a fallback (ready count only; <code>unacknowledged</code> is then <code>null</code>)<br><br>Example Value<pre>{
  "queue": "payments.refunds.dlq",
  "ready": 1,
  "unacknowledged": 0,
  "total": 1,
  "source": "management-api"
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/dlq/count"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – Valid credentials without the Admin role: the service key, a JWT with another role, or no Admin credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This is an Admin endpoint: X-Service-Api-Key is not accepted; send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/dlq/count"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – RabbitMQ is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/admin/refunds/dlq/count"
}</pre></td></tr>
</table>

Show it as: `total > 0` → "Failed refunds: 1" with a warning and a link to the list; `total == 0` → "No failed
refunds require attention." The count only goes down when a recovery **acknowledges** a message; a message being
recovered right now is `unacknowledged`, not `ready`. Load it on page load and after every recovery. The frontend
never talks to RabbitMQ itself.

### GET `/admin/refunds/failed`

<table>
<tr><th colspan="2" align="left">GET <b>/admin/refunds/failed</b> – List the failed refunds (Admin)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>). <code>X-Service-Api-Key</code> is <b>not</b> accepted here (403).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation: the <code>REFUND_FAILED</code> payments from MySQL, oldest failure first, each with its DLQ message count<br><br>Example Value<pre>{
  "count": 1,
  "dlq": {
    "queue": "payments.refunds.dlq",
    "ready": 1,
    "unacknowledged": 0,
    "total": 1,
    "source": "management-api"
  },
  "failedRefunds": [
    {
      "paymentId": 25,
      "bookingId": 12,
      "eventId": 9001,
      "userId": 1,
      "amount": 40.00,
      "amountCents": 4000,
      "currency": "SGD",
      "status": "REFUND_FAILED",
      "failureReason": "Stripe refund request failed (code=charge_already_refunded, status=400)",
      "refundAttempts": 1,
      "failedAt": "2026-10-23T16:30:00",
      "stripePaymentIntentId": "pi_3US7Od6qXRb08W0n0abc",
      "stripeRefundId": null,
      "dlqMessages": 1
    }
  ],
  "unmatchedDlqMessages": []
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/failed"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – Valid credentials without the Admin role: the service key, a JWT with another role, or no Admin credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This is an Admin endpoint: X-Service-Api-Key is not accepted; send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/failed"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/admin/refunds/failed"
}</pre></td></tr>
</table>

Reading the list looks at the DLQ without removing anything. `dlqMessages: 0` means MySQL has the failure but the
DLQ has no message for it (e.g. publishing failed); it can be recovered all the same. `unmatchedDlqMessages` lists
payment IDs whose DLQ message is stale (the payment is no longer `REFUND_FAILED`); recovering such a payment removes
it. No card data or Stripe secrets are returned.

### POST `/admin/refunds/{paymentId}/retry`

<table>
<tr><th colspan="2" align="left">POST <b>/admin/refunds/{paymentId}/retry</b> – Retry one failed refund (Admin)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>paymentId<br>int<br><i>(path)</i></td><td>Unique ID of a <code>REFUND_FAILED</code> payment<br><br>Example Value<pre>25</pre></td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>). <code>X-Service-Api-Key</code> is <b>not</b> accepted here (403).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td>body<br><i>(body)</i></td><td>None. Send the POST with an empty body.</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. <code>outcome</code> is <code>REFUNDED</code> (refunded now, or Stripe already had a succeeded refund, which is recorded instead of refunding twice), <code>ALREADY_REFUNDED</code> (it was already <code>REFUNDED</code>; a stale DLQ message is removed), <code>PENDING</code> (Stripe accepted it; its webhook finishes it) or <code>FAILED</code> (still failing, including Stripe being unreachable: it stays <code>REFUND_FAILED</code>, the Admin is alerted again and the <b>same</b> DLQ message is kept, so <code>dlqMessagesRemoved</code> is 0)<br><br>Example Value<pre>{
  "paymentId": 25,
  "outcome": "REFUNDED",
  "paymentStatus": "REFUNDED",
  "stripeRefundId": "re_3US7Od6qXRb08W0n01Vj",
  "dlqMessagesFound": 1,
  "dlqMessagesRemoved": 1,
  "remainingInDlq": 0,
  "message": "Refunded"
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/25/retry"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – Valid credentials without the Admin role: the service key, a JWT with another role, or no Admin credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This is an Admin endpoint: X-Service-Api-Key is not accepted; send an Admin token (Authorization: Bearer ...)",
  "path": "/admin/refunds/25/retry"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Payment not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Payment 99 not found",
  "path": "/admin/refunds/99/retry"
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – The payment isn't <code>REFUND_FAILED</code>, or another Admin is recovering it right now<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Payment 25 is PAID; only REFUND_FAILED payments can be recovered",
  "path": "/admin/refunds/25/retry"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable (the payment stays <code>REFUND_FAILED</code>)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/admin/refunds/25/retry"
}</pre></td></tr>
</table>

Synchronous (Admin → frontend → Payment Service → Stripe; there is no recovery queue). It checks Stripe first,
refunds again with a new idempotency key only if no refund succeeded, and removes the payment's DLQ message only
when the refund succeeded. See [Admin recovery](#admin-recovery-of-failed-refunds) for the steps.

### POST `/payments/{paymentId}/retry-refund`

<table>
<tr><th colspan="2" align="left">POST <b>/payments/{paymentId}/retry-refund</b> – Retry a failed refund (deprecated alias, Admin)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>paymentId<br>int<br><i>(path)</i></td><td>Unique ID of a <code>REFUND_FAILED</code> payment<br><br>Example Value<pre>25</pre></td></tr>
<tr><td>Authorization<br>string<br><i>(header)</i></td><td>An Admin token: <code>Bearer &lt;JWT&gt;</code> with <code>role: ADMIN</code>. Send this or <code>X-Admin-Api-Key</code>.<br><br>Example Value<pre>Bearer eyJhbGciOiJIUzI1NiJ9...</pre></td></tr>
<tr><td>X-Admin-Api-Key<br>string<br><i>(header)</i></td><td>Operator fallback for Admins (<code>payment_admin_api_key</code>). <code>X-Service-Api-Key</code> is <b>not</b> accepted here (403).<br><br>Example Value<pre>&lt;admin key&gt;</pre></td></tr>
<tr><td>body<br><i>(body)</i></td><td>None. Send the POST with an empty body.</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. <code>outcome</code> is <code>REFUNDED</code> (refunded now, or Stripe already had a succeeded refund, which is recorded instead of refunding twice), <code>ALREADY_REFUNDED</code> (it was already <code>REFUNDED</code>; a stale DLQ message is removed), <code>PENDING</code> (Stripe accepted it; its webhook finishes it) or <code>FAILED</code> (still failing, including Stripe being unreachable: it stays <code>REFUND_FAILED</code>, the Admin is alerted again and the <b>same</b> DLQ message is kept, so <code>dlqMessagesRemoved</code> is 0)<br><br>Example Value<pre>{
  "paymentId": 25,
  "outcome": "REFUNDED",
  "paymentStatus": "REFUNDED",
  "stripeRefundId": "re_3US7Od6qXRb08W0n01Vj",
  "dlqMessagesFound": 1,
  "dlqMessagesRemoved": 1,
  "remainingInDlq": 0,
  "message": "Refunded"
}</pre></td></tr>
<tr><td>Code – 401</td><td>Description – Missing or invalid credentials<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)",
  "path": "/payments/25/retry-refund"
}</pre></td></tr>
<tr><td>Code – 403</td><td>Description – Valid credentials without the Admin role: the service key, a JWT with another role, or no Admin credential configured on the service<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 403,
  "error": "Forbidden",
  "message": "This is an Admin endpoint: X-Service-Api-Key is not accepted; send an Admin token (Authorization: Bearer ...)",
  "path": "/payments/25/retry-refund"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Payment not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Payment 99 not found",
  "path": "/payments/99/retry-refund"
}</pre></td></tr>
<tr><td>Code – 409</td><td>Description – The payment isn't <code>REFUND_FAILED</code>, or another Admin is recovering it right now<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Payment 25 is PAID; only REFUND_FAILED payments can be recovered",
  "path": "/payments/25/retry-refund"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL or RabbitMQ is unavailable (the payment stays <code>REFUND_FAILED</code>)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/payments/25/retry-refund"
}</pre></td></tr>
</table>

Kept so existing clients keep working; use `POST /admin/refunds/{paymentId}/retry`. It used to queue
`refund.reprocess.requested` and answer 202; that queue no longer exists and it now behaves exactly like the Admin
retry.

### GET `/bookings`

<table>
<tr><th colspan="2" align="left">GET <b>/bookings</b> – Get all bookings (newest first)</th></tr>
<tr><td><b>Parameters</b></td><td>No parameters</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. An empty list <code>[]</code> when there are no bookings<br><br>Example Value<pre>[
  {
    "bookingId": 12,
    "eventBookingId": "b-lx3k9a2f",
    "userId": 1,
    "eventId": 9001,
    "eventTitle": "Rooftop Party",
    "quantity": 2,
    "totalAmount": 40.00,
    "currency": "SGD",
    "status": "CONFIRMED",
    "createdAt": "2026-10-23T16:15:28",
    "updatedAt": "2026-10-23T16:15:30"
  },
  ...
]</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/bookings"
}</pre></td></tr>
<tr><td>Code – 500</td><td>Description – An unexpected error occurred (details are logged, never returned)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/bookings"
}</pre></td></tr>
</table>

An empty result is `200 []`, not an error. The participant's email is stored but never returned.

### GET `/bookings/{bookingId}`

<table>
<tr><th colspan="2" align="left">GET <b>/bookings/{bookingId}</b> – Find booking by booking ID</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>bookingId<br>int<br><i>(path)</i></td><td>Unique ID of booking to return<br><br>Example Value<pre>12</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation<br><br>Example Value<pre>{
  "bookingId": 12,
  "eventBookingId": "b-lx3k9a2f",
  "userId": 1,
  "eventId": 9001,
  "eventTitle": "Rooftop Party",
  "quantity": 2,
  "totalAmount": 40.00,
  "currency": "SGD",
  "status": "CONFIRMED",
  "createdAt": "2026-10-23T16:15:28",
  "updatedAt": "2026-10-23T16:15:30"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>bookingId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'bookingId'",
  "path": "/bookings/abc"
}</pre></td></tr>
<tr><td>Code – 404</td><td>Description – Booking not found<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Booking 99 not found",
  "path": "/bookings/99"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/bookings/12"
}</pre></td></tr>
</table>

### GET `/bookings/user/{userId}`

<table>
<tr><th colspan="2" align="left">GET <b>/bookings/user/{userId}</b> – Find a user's bookings (newest first)</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>userId<br>int<br><i>(path)</i></td><td>ID of the user (from the User Service)<br><br>Example Value<pre>1</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – successful operation. <code>[]</code> for a user with no bookings<br><br>Example Value<pre>[
  {
    "bookingId": 12,
    "eventBookingId": "b-lx3k9a2f",
    "userId": 1,
    "eventId": 9001,
    "eventTitle": "Rooftop Party",
    "quantity": 2,
    "totalAmount": 40.00,
    "currency": "SGD",
    "status": "CONFIRMED",
    "createdAt": "2026-10-23T16:15:28",
    "updatedAt": "2026-10-23T16:15:30"
  },
  ...
]</pre></td></tr>
<tr><td>Code – 400</td><td>Description – <code>userId</code> is not a number<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value for 'userId'",
  "path": "/bookings/user/abc"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/bookings/user/1"
}</pre></td></tr>
</table>

### POST `/bookings`

<table>
<tr><th colspan="2" align="left">POST <b>/bookings</b> – Create booking</th></tr>
<tr><td><b>Parameters</b></td><td></td></tr>
<tr><td>Name</td><td>Description</td></tr>
<tr><td>body<br>object<br><i>(body)</i></td><td>Booking to create. <code>userId</code>, <code>eventId</code>, <code>quantity</code> and <code>totalAmount</code> are required; see the field rules below the table.<br><br>Example Value<pre>{
  "userId": 1,
  "eventId": 9001,
  "quantity": 2,
  "totalAmount": 40.00,
  "participantEmail": "alice@example.com",
  "participantName": "Alice Tan",
  "eventTitle": "Rooftop Party"
}</pre></td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 201</td><td>Description – successful operation. The booking starts as <code>PENDING</code>; pay it with <code>POST /payments</code><br><br>Example Value<pre>{
  "bookingId": 14,
  "userId": 1,
  "eventId": 9001,
  "eventTitle": "Rooftop Party",
  "quantity": 2,
  "totalAmount": 40.00,
  "currency": "SGD",
  "status": "PENDING",
  "createdAt": "2026-10-23T16:40:00",
  "updatedAt": "2026-10-23T16:40:00"
}</pre></td></tr>
<tr><td>Code – 400</td><td>Description – Invalid request body<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/bookings",
  "fieldErrors": {
    "quantity": "must be greater than 0"
  }
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – MySQL is unavailable<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A required service (database or message broker) is unavailable; please retry later",
  "path": "/bookings"
}</pre></td></tr>
</table>

| Field | Type | Required | Rules |
|---|---|---|---|
| `userId` | integer | **yes** | > 0. ID from the User Service (not checked against it) |
| `eventId` | integer | **yes** | > 0. ID from the Event Service (not checked against it) |
| `quantity` | integer | **yes** | > 0 |
| `totalAmount` | number | **yes** | ≥ 0.01, at most 8 digits before and 2 after the decimal point, SGD |
| `participantEmail` | string | no | Stored but no longer used: the refund email goes to the booking's `userId` |
| `participantName` | string | no | Stored but no longer used: the Notification Service has the name |
| `eventTitle` | string | no* | Needed for the refund email |

\* Without `eventTitle`, refunds still work but no `notify.refund` email can be sent.
`userId` is part of the body for now; once the Authentication Service exists it should come from the caller's JWT
(only `BookingController` needs to change).

### GET `/health`

<table>
<tr><th colspan="2" align="left">GET <b>/health</b> – Service health check</th></tr>
<tr><td><b>Parameters</b></td><td>No parameters</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – The service process is running<br><br>Example Value<pre>{
  "service": "payment",
  "status": "UP"
}</pre></td></tr>
<tr><td>Code – 500</td><td>Description – An unexpected error occurred (details are logged, never returned)<br><br>Example Value<pre>{
  "timestamp": "2026-10-23T08:15:30.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/health"
}</pre></td></tr>
</table>

`/health` deliberately checks only that the process answers (Docker Compose and CI use it), so it doesn't fail when
MySQL or RabbitMQ is down. If the service itself is down there is no HTTP response at all. For a check that includes
the database and the broker, use `/actuator/health/readiness` below.

### GET `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`

<table>
<tr><th colspan="2" align="left">GET <b>/actuator/health, /actuator/health/liveness, /actuator/health/readiness</b> – Detailed health checks</th></tr>
<tr><td><b>Parameters</b></td><td>No parameters</td></tr>
<tr><td><b>Responses</b></td><td>Content type – application/json</td></tr>
<tr><td>Code – 200</td><td>Description – <code>UP</code>. <code>/actuator/health</code> and <code>/readiness</code> also check MySQL and RabbitMQ; <code>/liveness</code> only checks that the process is serving<br><br>Example Value<pre>{
  "status": "UP"
}</pre></td></tr>
<tr><td>Code – 503</td><td>Description – A dependency (MySQL or RabbitMQ) is down<br><br>Example Value<pre>{
  "status": "DOWN"
}</pre></td></tr>
</table>

| Endpoint | `UP` when | Used by |
|---|---|---|
| `GET /actuator/health` | MySQL and RabbitMQ are reachable | CI smoke test |
| `GET /actuator/health/liveness` | the process is serving | Docker `HEALTHCHECK`, ECS / orchestrator liveness checks |
| `GET /actuator/health/readiness` | MySQL and RabbitMQ are reachable (Stripe is not checked) | CI smoke test, orchestrator readiness checks |

Only the health endpoint is exposed, with no details; nothing else under `/actuator` is reachable (404).

---

### Testing from the frontend

The party.fun frontend (`user-interfaces/frontend`) pays and refunds through this service for real, in the Stripe
sandbox. Its README ("Payments") has the full click path; in short:

1. Set `stripe_publishable_key` (`pk_test_…`) next to the other Stripe values and recreate Payment. `stripe listen`
   is optional for checkout.
2. Checkout calls `GET /payments/config` (publishable key), then `POST /payments/checkout` **without**
   `paymentMethodId`. Stripe's card form in the browser confirms the PaymentIntent with the `clientSecret`, then the
   page calls [`POST /payments/{paymentId}/sync`](#post-paymentspaymentidsync), which records it as `PAID` (the
   `payment_intent.succeeded` webhook does the same when `stripe listen` runs).
3. Refunds: cancelling an event (as the organiser) calls `POST /payments/{paymentId}/refund` for each Stripe-paid
   booking with `X-Service-Api-Key`, the role the Event Service will play.
4. Admin dashboard (Admin login, `/admin/refunds`): `GET /admin/refunds/dlq/count`, `GET /admin/refunds/failed` and
   `POST /admin/refunds/{paymentId}/retry` with `X-Admin-Api-Key` (see [Admin recovery](#admin-recovery-of-failed-refunds)).

The browser never holds either key: the frontend's own server (nginx in Docker, the Vite dev server) forwards exactly
those calls and adds the key from its configuration (`user-interfaces/frontend/.env`). That is a stand-in until the Authentication Service issues `ADMIN` JWTs.

A declined card can be retried with another card on the same PaymentIntent; that's why `payment_intent.succeeded`
can turn a `FAILED` payment into `PAID` (see [Status transitions](#status-transitions)).

### Testing with Bruno or Postman

These flows run against your **Stripe sandbox**: set it up first ([Stripe sandbox](#stripe-sandbox)), keep
`stripe listen --forward-to localhost:8080/payments/webhook/stripe` running, and watch its terminal: every
webhook shows up there with Payment's answer (`[200]`). Every payment and refund also appears in the Stripe
Dashboard of that sandbox (**Payments**, **Transactions**).

Create an environment with `baseUrl = http://localhost:8080`, `adminToken = <an ADMIN JWT from the issuer>`,
`adminKey = <your payment_admin_api_key>` and `serviceKey = <your payment_service_api_key>`.

The refund and Admin endpoints need credentials (Bruno: **Headers** tab; Postman: **Headers**). The flows below say
"Admin header": `Authorization: Bearer {{adminToken}}`, or, while the issuer can't issue `ADMIN` tokens yet, the
operator fallback `X-Admin-Api-Key: {{adminKey}}`. To act as the Event Service instead, send
`X-Service-Api-Key: {{serviceKey}}` on `POST /payments/<paymentId>/refund`; on the Admin endpoints that gives
`403`. Without credentials you get `401`; a PARTICIPANT/ORGANISER token gets `403`.

Each flow needs a **new** `eventBookingId`, because a booking can only be paid once. Where a table lists only
some fields, take the body of flow 1 and change just those.

**1. Happy path: checkout, refund**

Body for request 1:

```json
{"eventBookingId": "evb-1001", "eventId": 9001, "userId": 1, "quantity": 2, "ticketPrice": 20.00,
 "paymentMethodId": "pm_card_visa", "participantEmail": "alice@example.com", "participantName": "Alice Tan",
 "eventTitle": "Rooftop Party"}
```

| # | Request | Body | Expect |
|---|---|---|---|
| 1 | `POST {{baseUrl}}/payments/checkout` | above | `201`, `status: "PAID"`, `amount: 40.00`, a real `stripePaymentIntentId` (`pi_…`). Dashboard → **Payments** shows S$40.00 *Succeeded* |
| 2 | Repeat request 1 | same | `200`, the **same** `paymentId`. Stripe was not called again (still one payment in the Dashboard) |
| 3 | `GET {{baseUrl}}/bookings/<bookingId>` | – | `status: "CONFIRMED"`, `eventBookingId: "evb-1001"` |
| 4 | `POST {{baseUrl}}/payments/<paymentId>/refund` + Admin header | none | `200`, `outcome: "REFUNDED"`, a real `stripeRefundId` (`re_…`). The Dashboard shows the payment *Refunded*; the Notification Service's logs show the `notify.refund` email to alice@example.com |
| 5 | Repeat request 4 | none | `200`, `outcome: "ALREADY_REFUNDED"` (idempotent; no second refund, no second email) |
| 6 | `GET {{baseUrl}}/bookings/<bookingId>` | – | `status: "REFUNDED"` |

The `stripe listen` terminal shows events such as `payment_intent.succeeded`, `charge.refunded` and
`refund.created`, each answered `[200]`: the service had already recorded them, so they change nothing.

**2. Declined card, then retry**

| # | Request | Body | Expect |
|---|---|---|---|
| 1 | `POST {{baseUrl}}/payments/checkout` | as above with `"eventBookingId": "evb-1002"`, `"paymentMethodId": "pm_card_visa_chargeDeclined"` | `201`, `status: "FAILED"` and a `failureReason`. The Dashboard shows a *Failed* payment |
| 2 | Same body with `"paymentMethodId": "pm_card_visa"` | | `201`, a new `paymentId`, `status: "PAID"`, same `bookingId` |
| 3 | Same body with `"quantity": 3` | | `409`: the `eventBookingId` was used with different details |

**3. Dead-letter queue: Stripe reverses a refund**

| # | Request | Body | Expect |
|---|---|---|---|
| 1 | `POST {{baseUrl}}/payments/checkout` | `"eventBookingId": "evb-1003"`, `"eventId": 9002`, `"paymentMethodId": "pm_card_refundFail"` | `201`, `status: "PAID"` |
| 2 | `POST {{baseUrl}}/payments/<paymentId>/refund` + Admin header | none | `200`, `outcome: "REFUNDED"`: Stripe first reports the refund as succeeded |
| 3 | Wait until the `stripe listen` terminal shows `refund.failed` (usually within a minute) | | Payment answered `[200]` |
| 4 | `GET {{baseUrl}}/payments/<paymentId>` | – | `status: "REFUND_FAILED"` |

Then look at it as the Admin would:

| # | Request | Body | Expect |
|---|---|---|---|
| 5 | `GET {{baseUrl}}/admin/refunds/dlq/count` + Admin header | – | `total` has gone up by 1 (`ready: 1` more) |
| 6 | `GET {{baseUrl}}/admin/refunds/failed` + Admin header | – | the payment, `status: "REFUND_FAILED"`, its `failureReason`, `refundAttempts`, `failedAt` and `dlqMessages: 1` |
| 7 | `POST {{baseUrl}}/admin/refunds/<paymentId>/retry` + Admin header | none | `200`. Stripe's refund for this card failed, so a **new** refund is made (idempotency key `refund-<stripePaymentIntentId>-retry-1`): `outcome: "REFUNDED"`, `dlqMessagesRemoved: 1` |
| 8 | `GET {{baseUrl}}/admin/refunds/dlq/count` + Admin header | – | `total` is back down by 1: the message was acknowledged and removed |

This test card reverses every refund, so `refund.failed` arrives again a little later: the payment becomes
`REFUND_FAILED` again and one new message lands in the DLQ.

You can also watch the queue in the RabbitMQ management UI (<http://localhost:15672>, `guest`/`guest`) → **Queues and
Streams** → `payments.refunds.dlq`. To look at a message there, use **Get messages** with Ack mode **Nack message
requeue true**; never **Automatic ack**, which removes it outside the Admin flow.

*For an immediate failure instead:* refund the payment by hand in the Dashboard first, then call
`POST /payments/<paymentId>/refund`. Stripe answers `charge_already_refunded`, a permanent error: `502` after
**1** attempt, `REFUND_FAILED` and a DLQ message with `trigger: "MANUAL"`. Recovering it then finds the refund you
made in the Dashboard (Stripe already shows it as succeeded), records it as `REFUNDED` without refunding again, and
removes the DLQ message.

**4. Pending payment finished by a webhook**

| # | Request | Body | Expect |
|---|---|---|---|
| 1 | `POST {{baseUrl}}/payments/checkout` | `"eventBookingId": "evb-1004"`, **no** `paymentMethodId` | `201`, `status: "PENDING"` with a `clientSecret`. Copy `stripePaymentIntentId` |
| 2 | In a terminal: `stripe payment_intents confirm <stripePaymentIntentId> -d payment_method=pm_card_visa` | | Stripe confirms it and sends `payment_intent.succeeded`; the listen terminal shows `[200]` |
| 3 | `GET {{baseUrl}}/payments/<paymentId>` | – | `status: "PAID"`, booking `CONFIRMED` |

With `"paymentMethodId": "pm_card_threeDSecure2Required"` the checkout also stays `PENDING`: 3D Secure has to be
completed in a browser with Stripe.js, and the service never reports it as paid before that.

**5. Webhook security**

| # | Request | Body | Expect |
|---|---|---|---|
| 1 | `POST {{baseUrl}}/payments/webhook/stripe` | the `payment_intent.succeeded` example under [POST `/payments/webhook/stripe`](#post-paymentswebhookstripe) | `400`: no valid `Stripe-Signature`, so a forged "payment succeeded" is refused |
| 2 | In a terminal: `stripe trigger payment_intent.succeeded` | | Stripe creates its own test payment; the listen terminal shows `[200]` and Payment answers `IGNORED` (not one of its payments) |

**6. Event cancellation (RabbitMQ, not HTTP)**

Pay one or more checkouts for the same `eventId`. Then in the RabbitMQ UI go to **Exchanges** → `party.events` →
**Publish message**:
- **Routing key:** `event.cancelled`
- **Payload:** `{"eventId": 9001, "requestId": "manual-test-1"}`

Its open bookings become `CANCELLED` (then `REFUNDED` once refunded), each paid payment is refunded individually at
Stripe (visible in the Dashboard), and each participant gets a `notify.refund` email. Check with
`GET /payments/user/1`. A payment whose refund fails (e.g. refunded by hand in the Dashboard first) ends in
`REFUND_FAILED` with one message in the DLQ and an admin alert; the others are unaffected.

---

## Database schema

The tables are mapped exactly as they exist. Both IDs are `INT AUTO_INCREMENT`, mapped with `GenerationType.IDENTITY`.

### `booking`

| Column | Type | Notes |
|---|---|---|
| `booking_id` | INT PK AUTO_INCREMENT | |
| `user_id` | INT NOT NULL | User Service ID, **not a foreign key** |
| `event_id` | INT NOT NULL | Event Service ID, **not a foreign key** |
| `quantity` | INT NOT NULL | > 0 |
| `total_amount` | DECIMAL(10,2) NOT NULL | SGD, > 0 |
| `event_booking_id` | VARCHAR(64) NULL, UNIQUE | the Event Service's booking ID from checkout (idempotency key). Not a foreign key |
| `participant_email` | VARCHAR(255) NULL | checkout snapshot, used only for `notify.refund` |
| `participant_name` | VARCHAR(255) NULL | checkout snapshot |
| `event_title` | VARCHAR(255) NULL | checkout snapshot |
| `status` | VARCHAR(20) NOT NULL | `PENDING`, `CONFIRMED`, `CANCELLED`, `REFUNDED` |
| `created_at`, `updated_at` | TIMESTAMP | |

The four nullable columns come from
[`db/migrations/V2__booking_notification_snapshot.sql`](db/migrations/V2__booking_notification_snapshot.sql)
(see [Local MySQL](#local-mysql)). Bookings created through `POST /bookings` leave them empty.

### `payment`

| Column | Type | Notes |
|---|---|---|
| `payment_id` | INT PK AUTO_INCREMENT | |
| `booking_id` | INT NOT NULL | **FK → `booking.booking_id`**, the only foreign key |
| `stripe_payment_intent_id` | VARCHAR(255) NULL | set once the PaymentIntent exists |
| `stripe_refund_id` | VARCHAR(255) NULL | set when Stripe accepts the refund (already while `REFUND_PENDING` if Stripe answers `pending`) |
| `refund_round` | INT NOT NULL DEFAULT 0 | 0 for the first refund; +1 for each retry of a `REFUND_FAILED` payment. Part of the Stripe idempotency key (see [Refund Retry Saga](#refund-retry-saga)). Added by [`db/migrations/V3__payment_refund_round.sql`](db/migrations/V3__payment_refund_round.sql) |
| `refund_failure_reason` | VARCHAR(500) NULL | why the last refund round finally failed (Stripe message / error code; never card data). Cleared when a refund succeeds. Added by [`V4`](db/migrations/V4__payment_refund_failure.sql) |
| `refund_attempts` | INT NOT NULL DEFAULT 0 | Stripe attempts made in that failed round (1 initial attempt + retries) |
| `refund_failed_at` | TIMESTAMP NULL | when the round finally failed |
| `amount` | DECIMAL(10,2) NOT NULL | SGD, copied from the booking |
| `status` | VARCHAR(30) NOT NULL | see below |
| `payment_method` | VARCHAR(50) NULL | e.g. `card` |
| `paid_at`, `refunded_at` | TIMESTAMP NULL | |
| `created_at`, `updated_at` | TIMESTAMP | |

### Status transitions

The code enforces these transitions. Anything else is rejected with 409.

```
payment:  PENDING ─▶ PAID ─▶ REFUND_PENDING ─▶ REFUNDED
             │        ▲           │     ▲            │
             ▼        │           ▼     │ retry      │ Stripe reverses the refund later
           FAILED ────┘      REFUND_FAILED ◀─────────┘ (webhook; e.g. pm_card_refundFail)
           (FAILED → PAID: a later card succeeded on the same PaymentIntent; webhook only)

booking:  PENDING ─▶ CONFIRMED ─▶ REFUNDED
             │           │            ▲
             └───────────┴─▶ CANCELLED┘      (CANCELLED never goes back to PENDING/CONFIRMED)
```

---

## RabbitMQ

The party.fun architecture has **exactly 4 exchanges** (counting RabbitMQ's built-in default exchange) and
**exactly 6 queues**. The Payment Service declares 3 of the exchanges (never the default one) and only its own 2
queues; the 4 notification queues belong to the Notification Service.

### Exchanges

| # | Exchange | Type | Declared by Payment | Used for |
|---|---|---|---|---|
| 1 | default (`""`) | direct | no (built in, never declared) | the Notification Service's delayed retries (routing key = queue name) |
| 2 | `notifications` | topic | yes, with the Notification Service's settings | `notify.*` commands to the Notification Service |
| 3 | `party.events` | topic | yes | domain events; `event.cancelled` from the Event Service |
| 4 | `payments.refund.dlx` | direct | yes | individually failed refunds, published by Payment |

### Queues and bindings

| # | Queue | Owner | Binding | Purpose |
|---|---|---|---|---|
| 1 | `notifications_service` | Notification Service | `notifications` / `notify.#` | main notification delivery; may have 3 competing consumers (replicas) on this **one** queue |
| 2 | `notifications_service.retry.30s` | Notification Service | default exchange, TTL 30 s → back to `notifications_service` | 1st delayed retry |
| 3 | `notifications_service.retry.2m` | Notification Service | default exchange, TTL 2 min → back | 2nd delayed retry |
| 4 | `notifications_service.retry.10m` | Notification Service | default exchange, TTL 10 min → back | 3rd delayed retry, then the failure is logged (no notification DLQ) |
| 5 | `payments.refunds.requested` | **Payment** | `party.events` / `event.cancelled` | one message per cancelled event; triggers refund processing |
| 6 | `payments.refunds.dlq` | **Payment** | `payments.refund.dlx` / `payments.refunds.dlq` | individually failed refunds awaiting Admin recovery; nothing consumes it automatically |

Payment's two queues are durable, without TTL, max-length or dead-letter arguments. Payment never declares the
notification queues; they are listed here so the whole topology is in one place.

### RabbitMQ communication table

| Publisher | Exchange | Routing key | Queue | Consumer | Purpose |
|---|---|---|---|---|---|
| Authentication / User Service | `notifications` | `notify.account_created` | `notifications_service` | Notification Service | account created email |
| Event Service | `notifications` | `notify.signup` | `notifications_service` | Notification Service | booking confirmation after a **confirmed** payment (Scenario 1) |
| Event Service | `notifications` | `notify.event_created` / `notify.event_updated` / `notify.event_deleted` | `notifications_service` | Notification Service | event CRUD emails (Scenario 2) |
| Event Service | `party.events` | `event.cancelled` | `payments.refunds.requested` | **Payment Service** | refund every paid booking of a cancelled event (Scenario 3) |
| **Payment Service** | `notifications` | `notify.refund` | `notifications_service` | Notification Service | participant refund confirmation, after Stripe confirmed the refund |
| **Payment Service** | `notifications` | `notify.admin_alert` | `notifications_service` | Notification Service | IT Support / Admin alert when a refund finally fails |
| **Payment Service** | `payments.refund.dlx` | `payments.refunds.dlq` | `payments.refunds.dlq` | Admin, through Payment's HTTP API | keep each failed refund until an Admin recovers it |
| Notification Service | default (`""`) | `notifications_service.retry.30s` / `.2m` / `.10m` | the same-named retry queue | (expires back to `notifications_service`) | delayed email retries |

The Scenario 1 payment itself (Event Service → Payment Service → Stripe) is synchronous HTTP, so it is not in this
table. There is **no** `payments.refunds.process`, **no** `payments.refunds.reprocess` / `refund.reprocess.requested`,
and **no** `payments.refunded` → Event Service → `notify.refund` round trip.

### Incoming: `event.cancelled`

The Event Service publishes this on `party.events` after it cancels an event: **one message for the whole event**.
Only `eventId` is read (a number or a numeric string); `requestId`/`request_id` is optional and defaults to
`cancel-event-{eventId}`. Other fields and the `__TypeId__` header are ignored. Both of these work:

```json
{"eventId": 42, "requestId": "cancel-42"}
```

```json
{"eventId": "42", "requestId": "cancel-42", "timestamp": "2026-10-18T14:25:30.123Z", "reason": "Event cancelled"}
```

Payment finds the affected payments in its own database through `booking.event_id` and refunds them one by one
(see [Refund Retry Saga](#refund-retry-saga)). The message is acknowledged only **after** every payment was
handled. A malformed message (invalid JSON, no `eventId`) is logged and dropped; it is never sent to the refund
DLQ. An unexpected error (e.g. MySQL down) is retried with backoff and then **requeued**, never dropped, so a
cancellation is never lost.

### Outgoing: individually failed refund → `payments.refunds.dlq`

RabbitMQ cannot split one event-cancellation message into per-payment messages, and Payment no longer uses a
per-payment processing queue. So when one payment's refund is exhausted, **Payment itself publishes one message for
that payment** to `payments.refund.dlx` with routing key `payments.refunds.dlq` (**application-managed routing of
failed individual refunds to the DLQ**; RabbitMQ does not dead-letter them automatically). Payment waits for the
broker's **publisher confirm**; if the publish fails, the failure is still recorded in MySQL and shown on the Admin
dashboard.

```json
{"paymentId": 101, "bookingId": 205, "eventId": 50, "userId": 7, "refundAmountCents": 3000, "currency": "SGD",
 "stripePaymentIntentId": "pi_...", "stripeRefundId": null, "retryCount": 3,
 "failureReason": "Stripe refund request failed (code=..., status=...)", "errorCode": "rate_limit",
 "trigger": "EVENT_CANCELLED", "failedAt": "2026-10-23T09:00:00Z"}
```

- **Header** `payment-id: 101`, so recovery can find a payment's message without parsing every body.
- **`trigger`:** `EVENT_CANCELLED`, `MANUAL` (REST refund endpoints), `ADMIN_RETRY` (only if the payment had no DLQ
  message yet), `RECOVERY` (`RefundRecoveryJob`) or `WEBHOOK` (Stripe reported the refund failed).
- **`retryCount`:** Stripe attempts made in total (1 initial attempt + retries).
- 20 eligible refunds with 18 successes and 2 failures give 18 `REFUNDED` payments and exactly **2** DLQ messages.

### Outgoing: `notify.refund`

Published by Payment directly on `notifications` with routing key `notify.refund` when a refund **succeeds** (Stripe
`succeeded`, either in the refund call or later by webhook). It follows the Notification Service's `refund`
contract:

```json
{
  "notification_id": "refund-payment-5",
  "user_id": 42,
  "event_title": "Rooftop Party",
  "amount": 4000,
  "currency": "SGD",
  "registration_id": "evb-1001",
  "refund_id": "re_3Q..."
}
```

| Field | Notes |
|---|---|
| `user_id` | **required**; the booking's `userId`. The Notification Service looks up the participant's email address and name in its user directory, so Payment never sends them |
| `event_title` | **required**; the `eventTitle` sent with the booking |
| `amount` | **required**; integer **cents** (4000 = SGD 40.00) |
| `registration_id` | **required**; the Event Service's `eventBookingId`, or Payment's `bookingId` for bookings made with `POST /bookings` |
| `notification_id` | deterministic per payment, so a repeated publish is dropped as a duplicate by the Notification Service |
| `refund_id` | left out when unknown |
| `currency` | always `SGD` |

If the booking has no event title, Payment logs a warning and sends **nothing**; it never publishes an
invalid `notify.refund`. Sending is best effort: if RabbitMQ refuses it, the refund stays `REFUNDED`.

### Outgoing: `notify.admin_alert`

Published on `notifications` with routing key `notify.admin_alert` when a refund **finally fails** (attempts
exhausted, a permanent Stripe error, or a webhook reporting a failed refund). The Notification Service emails it to
IT Support / `ADMIN_EMAIL`. The DLQ itself emails nobody, and the dashboard shows outstanding failures even if this
email is lost.

```json
{
  "notification_id": "refund-failed-payment-5-1760774400000",
  "source": "payment-service",
  "error": "Refund failed after 3 attempts for payment 5",
  "details": {
    "payment_id": 5, "booking_id": 7, "event_id": 42, "user_id": 1,
    "amount": 4000, "currency": "SGD", "stripe_payment_intent_id": "pi_...",
    "reason": "…", "stripe_error_code": "rate_limit", "retryable": true, "retries": 3,
    "trigger": "EVENT_CANCELLED",
    "dead_letter_exchange": "payments.refund.dlx", "dead_letter_queue": "payments.refunds.dlq",
    "admin_action": "Review and retry on the Admin dashboard (POST /admin/refunds/5/retry)"
  }
}
```

It never contains Stripe keys, card data or the participant's email. `notification_id` is unique per failure, so a
later failure of the same payment alerts the Admin again.

---

## Refund Retry Saga

The cancellation flow is a **choreography-based Saga** (no orchestrator): the Event Service cancels the event and
publishes `event.cancelled`; the Payment Service reacts by refunding. It is a **retry** Saga, not a rollback Saga: a
cancelled event stays `CANCELLED` whatever happens to its refunds, nothing is compensated, and no booking or event is
reactivated. Failures are retried and then handed to a human (the Admin). Payment never updates the Event
Service's event status.

**Event cancellation** (`event.cancelled`, one message per event):
1. The event's `PENDING` and `CONFIRMED` bookings become `CANCELLED`, in one short transaction.
2. Payment loads the event's eligible payments from **its own** database: `PAID`, plus `REFUND_PENDING` ones left by
   an interrupted earlier run. `REFUND_FAILED` ones are already in the DLQ and belong to the Admin; `REFUNDED` ones
   are done.
3. It refunds them **one by one, inside the service**, each with the steps below. One failure never affects the
   others.
4. Only then is the message acknowledged. If Payment stops mid-way, RabbitMQ redelivers it, and the steps resume
   idempotently. A duplicate `event.cancelled` is handled the same way: no refund twice, no second DLQ message.

**Steps for one payment** (also used by `POST /payments/{id}/refund`, `POST /payments/booking/{id}/refund` and the
Admin recovery):

1. **Claim.** In a short transaction holding a row lock, `PAID` becomes `REFUND_PENDING`.
   - `REFUNDED` means it is already done: Stripe is not called (idempotent).
   - `REFUND_PENDING` (an interrupted earlier attempt) is resumed with the **same** key.
2. **Refund.** Call Stripe with the round's idempotency key, the same on every attempt of the round, so even a
   duplicate request or a timed-out call cannot create a second refund. The key is `refund-{paymentIntentId}`
   for the first round and `refund-{paymentIntentId}-retry-{n}` for the n-th retry of a `REFUND_FAILED`
   payment (`refund_round`). A retry needs a new key because Stripe replays the first answer for a key, failures
   included, for at least 24 hours. A new key can't refund twice: Stripe never refunds more than the charge.
   - **Why the PaymentIntent id, not the payment id:** payment ids restart at 1 in every database that shares the
     Stripe sandbox (each CI run, each developer's stack). A key built from them would be reused by another
     environment within those 24 hours, and Stripe would then reject the refund or replay the other environment's
     one. PaymentIntent ids are unique across Stripe. For the same reason, the PaymentIntent creation key has a
     random part (`payment-{paymentId}-create-{random UUID}`); creation is sent only once per payment row.
   - **"3 retries" means 3 attempts in total:** 1 initial attempt + up to 2 retries (`refund_max_attempts=3`), with
     backoff. Only transient errors are retried: network errors and timeouts, rate limiting (429), Stripe 5xx.
   - **Permanent errors stop at once** (1 attempt), because repeating them can't succeed: an invalid request
     such as `charge_already_refunded`, a disputed charge, authentication or permission errors, a missing
     PaymentIntent, or Stripe reporting the refund `failed`/`canceled`.
3. **Succeeded.** Stripe says `succeeded`: mark `REFUNDED` and store `stripe_refund_id` and `refunded_at`. Set the
   booking to `REFUNDED` once nothing on it is still paid. Publish `notify.refund` to the participant.
4. **Pending.** Stripe accepted the refund but says `pending`: the payment **stays `REFUND_PENDING`** with
   `stripe_refund_id` stored. It is never reported as refunded early. The Stripe webhook completes it later:
   `succeeded` → step 3, `failed`/`canceled` → step 5.
5. **Failed.** Mark `REFUND_FAILED` and record the reason, attempt count and time in MySQL. Publish
   `notify.admin_alert`, then publish **one** message for this payment to `payments.refund.dlx` →
   `payments.refunds.dlq` (with a publisher confirm).

**Abandoned refunds.** `RefundRecoveryJob` (every 5 min) resumes `REFUND_PENDING` payments that have no Stripe refund
yet and haven't changed for 10 min, e.g. a REST refund interrupted by a restart, with the same idempotency key.

**Stripe webhooks** are idempotent by state.
- `payment_intent.payment_failed` only acts on a `PENDING` payment. `payment_intent.succeeded` acts on a `PENDING`
  or `FAILED` payment (another card succeeded on the same PaymentIntent); a replay finds it `PAID` and changes
  nothing.
- Refund events are matched by refund ID, because webhooks can arrive late and out of order.
- A refund `succeeded` event acts on `REFUND_PENDING` (the same refund) or `REFUND_FAILED` (a refund other than the
  one Stripe reported as failed). It reconciles a refund that Stripe completed but whose result we hadn't recorded.
- A refund `failed`/`canceled` event acts on `REFUND_PENDING`, and on a `REFUNDED` payment whose recorded refund it
  is: Stripe reversed a refund it had first reported as succeeded (`REFUNDED` → `REFUND_FAILED`, admin alert, one
  DLQ message).
- Replays get `ALREADY_PROCESSED`.

---

## Dead-letter queue

`payments.refunds.dlq` holds **only individually failed refunds**, one message per failed payment (see
[the message format](#outgoing-individually-failed-refund--paymentsrefundsdlq)). It never holds notification
failures, payment failures or malformed messages. RabbitMQ owns the queue; there is no DLQ table in MySQL. MySQL
stays authoritative for every payment's status, and also stores the failure reason, attempt count and time.

Messages stay until an Admin's recovery succeeds: nothing consumes the queue automatically, and it has no TTL or
length limit. Don't purge it or remove messages with **Automatic ack** in the RabbitMQ UI; that skips the recovery.

### Admin recovery of failed refunds

```
Admin -> Frontend -> Payment Service : GET  /admin/refunds/dlq/count   (Bearer JWT, role ADMIN)
Admin -> Frontend -> Payment Service : GET  /admin/refunds/failed
Admin -> Frontend -> Payment Service : POST /admin/refunds/{paymentId}/retry
Payment Service -> MySQL             : claim the payment (row lock): REFUND_FAILED -> REFUND_PENDING
Payment Service -> RabbitMQ          : basicGet (manual ack) until this payment's message(s) are held;
                                       unrelated messages are put back at once (never acknowledged)
Payment Service -> Stripe            : list the PaymentIntent's refunds
  a refund succeeded       -> record REFUNDED (no new refund), notify.refund, ACK (remove) the message
  a refund still pending   -> REFUND_PENDING, its webhook finishes it, ACK the message
  otherwise                -> refund again (new round: refund-{paymentIntentId}-retry-{n})
       succeeded          -> REFUNDED, notify.refund ->> Notification Service, ACK the message
       still failing      -> REFUND_FAILED + new reason, notify.admin_alert, NACK + requeue (same message kept)
Payment Service --> Frontend         : AdminRetry (outcome, dlqMessagesRemoved, remainingInDlq)
```

- **Removal is real.** RabbitMQ can't delete a message by ID. The only way a message leaves the DLQ is to be fetched
  with manual acknowledgement and then **acknowledged**, which recovery does only after the refund succeeded. The
  count in [`GET /admin/refunds/dlq/count`](#get-adminrefundsdlqcount) therefore drops by exactly the removed
  messages (e.g. 2 → 1 → 0 as payments #101 and #102 are recovered); no counter is decremented by hand.
- **Unrelated messages are never acknowledged.** While looking for one payment's message, every other fetched
  message is negatively acknowledged with requeue before Stripe is called. If Payment stops while holding messages,
  the broker returns them by itself.
- **One Admin at a time per payment.** The MySQL claim (row lock, `REFUND_FAILED` → `REFUND_PENDING`) lets only one
  recovery of a payment run; a second gets 409.
- **No duplicate refunds.** Stripe's current state is checked first, and every attempt uses an idempotency key.
- **No duplicate DLQ entries.** A failed recovery puts the same message back. Only a `REFUND_FAILED` payment that
  had no DLQ message at all (its original publish failed) gets one.
- **Duplicates of one payment** (possible from older versions) are all removed when that payment is recovered.
- **Reconciliation.** [`GET /admin/refunds/failed`](#get-adminrefundsfailed) shows each failed payment's DLQ message
  count and lists stale messages (payment no longer `REFUND_FAILED`); recovering such a payment removes them.

### Seeding failed refunds for the dashboard

```bash
python scripts/seed_failed_refunds.py        # from services/payment; --count 1..5, default 3
```

[`scripts/seed_failed_refunds.py`](scripts/seed_failed_refunds.py) (Python standard library only) creates real
failed refunds against the running local Payment Service, in the Stripe **sandbox**. For each, it:

1. pays for a demo event's tickets with `pm_card_visa` (`POST /payments/checkout`);
2. refunds that PaymentIntent directly at Stripe, standing in for a refund that went through while Payment's own
   call failed;
3. asks Payment to refund it (`POST /payments/{paymentId}/refund`). Stripe answers `charge_already_refunded`, so the
   payment becomes `REFUND_FAILED` with one message in `payments.refunds.dlq`.

Retrying one (`POST /admin/refunds/{paymentId}/retry`) then succeeds: Stripe already shows the refund, so Payment
records `REFUNDED` without refunding twice and removes the message. The script reads `dev.env` (or `--env-file`), refuses any key that
isn't `sk_test_`/`rk_test_`, never prints keys, and adds new ones on every run.

**Limitations.** Finding one payment's message is a bounded scan of the queue (up to its depth at the start), not a
database-style lookup; it is fine for the small number of failed refunds expected here. During the scan the other
messages briefly show as `unacknowledged`. If two Admins recover different payments at the same instant, one scan
can miss its message; the response then says `dlqMessagesFound: 0` and the Admin simply retries.

---

## Differences from the previous architecture

| Before | Now |
|---|---|
| `payment.refunds.requested`, `payment.refunds.process`, `payment.refunds.reprocess`, `payment.refunds.dlq`, exchange `payment.refunds.dlx` | **`payments.refunds.requested`**, **`payments.refunds.dlq`**, exchange **`payments.refund.dlx`**; no process/reprocess queues |
| `event.cancelled` split into one RabbitMQ message per payment (`payment.refunds.process`) | each payment refunded **inside Payment**, the cancellation message acked once all are handled |
| failed refunds dead-lettered by RabbitMQ (rejected message, `x-death` header) | **application-managed routing**: Payment publishes one message per failed refund to the DLX, with a publisher confirm |
| failure reason only in the admin alert | failure reason, attempts and time **also stored in MySQL** (V4) |
| Admin retry `POST /payments/{id}/retry-refund` → `refund.reprocess.requested` (async, 202); DLQ messages never removed | **`POST /admin/refunds/{id}/retry`** (sync): Stripe checked first; on success the DLQ message is **acknowledged and removed** |
| no way to count or list the DLQ | `GET /admin/refunds/dlq/count` (ready / unacknowledged / total) and `GET /admin/refunds/failed` |
| Admin = `X-Admin-Api-Key` | Admin = **JWT with role `ADMIN`** (the key remains as an operator fallback) |
| `event.cancelled` dropped after 3 failed deliveries | retried with backoff and then **requeued**, never dropped |
| — | `RefundRecoveryJob` resumes refunds abandoned by a restart |
| `payments.refunded` → Event Service → `notify.refund` | already removed earlier: Payment publishes `notify.refund` itself |

### Migrating an existing RabbitMQ broker

The new version declares the new names but never deletes anything, and **nothing is purged automatically**. On a
broker that ran the previous version (e.g. an older local RabbitMQ volume), the old queues and exchange stay, and the old
`payment.refunds.requested` is still bound to `event.cancelled`, so it keeps collecting copies of every cancellation
that nobody consumes. Clean up by hand, after the new version is running:

1. **Look first.** `docker exec project-rabbitmq-1 rabbitmqctl list_queues name messages` (or the management UI).
2. **`payment.refunds.dlq` has messages?** They are real failed refunds. Move them into `payments.refunds.dlq` so
   the Admin dashboard sees them: management UI → Queues and Streams → `payment.refunds.dlq` → **Move messages**
   (needs the `rabbitmq_shovel` and `rabbitmq_shovel_management` plugins:
   `docker exec project-rabbitmq-1 rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management`), destination
   `payments.refunds.dlq`. Recovery reads the old message format too (it only needs `paymentId`). Alternatively,
   leave them: every such payment is `REFUND_FAILED` in MySQL and listed on the dashboard, and recovering it with no
   DLQ message works the same.
3. **`payment.refunds.process` / `payment.refunds.reprocess` have messages?** Those were refunds in progress under
   the old version. Their payments are `PAID`/`REFUND_PENDING`/`REFUND_FAILED` in MySQL: `PAID` ones of a cancelled
   event are refunded again if that event's `event.cancelled` is re-published, `REFUND_PENDING` ones are resumed by
   `RefundRecoveryJob`, `REFUND_FAILED` ones are on the dashboard. Check them in MySQL before deleting the queue.
4. **`payment.refunds.requested` has messages?** They are copies of cancellations the new queue also received;
   they can be deleted once the new queue's messages are processed (check `payments.refunds.requested` is 0).
5. **Then delete** `payment.refunds.requested`, `payment.refunds.process`, `payment.refunds.reprocess`,
   `payment.refunds.dlq` and the exchange `payment.refunds.dlx` (management UI → **Delete**, or
   `rabbitmqctl delete_queue <name>`; exchanges via the UI).

CI and the Docker integration tests always start a fresh broker, so they need none of this.

---

## Notification Service compatibility

The Payment Service sends no emails or SMS itself and never declares a Notification Service queue. It publishes
only `notify.*` commands on the `notifications` topic exchange, which the Notification Service consumes from
`notifications_service` (bound with `notify.#`).

- **Participant refund emails (`notify.refund`):** published directly by Payment after a refund succeeds, using the
  email, name and event title the Event Service passed with the booking.
- **Admin alerts (`notify.admin_alert`):** published when a refund finally fails, with `source="payment-service"`.
- **Signup confirmation emails come from the Event Service** (`notify.signup`) after Payment confirmed the payment.
  Payment publishes nothing for a successful payment.
- **Delayed retries and failure logging are the Notification Service's**, with its three retry queues and no DLQ.
  Payment's only DLQ is `payments.refunds.dlq`, for refunds.

---

## Changes needed outside the Payment Service

This work changed only `services/payment/`. The other services must do the following to match the target
architecture (checked against their current code):

**Authentication Service (later combined with the Event Service)**
1. **Add the `ADMIN` role.** `Credential.Role` has only `PARTICIPANT` and `ORGANISER`, so no Admin token can be
   issued yet. Until it is, use the operator key `payment_admin_api_key`.
2. **Share the secret.** Payment verifies tokens with the same HS256 secret: set Payment's `auth_jwt_secret` to the
   issuer's `AUTH_JWT_SECRET`. After the Event/Authentication merge, the combined service keeps issuing the same
   tokens; if it adds an `iss` claim, set `auth_jwt_issuer` too. Nothing else changes in Payment.

**Event Service (later combined with the Authentication Service)**
1. **Keep publishing `event.cancelled`** on `party.events`; Payment's queue is now `payments.refunds.requested`
   (Payment owns and binds it, so the publisher needs no change).
2. **Remove `event.payments.refunded` / `payments.refunded`.** Payment never publishes `payments.refunded`;
   `PaymentRefundedListener` and its queue declaration/binding should go. For a cancelled event its bookings are
   already `CANCELLED` by its own `cancel()`; refund status is available from `GET /payments/booking/{id}`.
3. **Publish `notify.signup` only after a confirmed payment** (Scenario 1), never for a `PENDING` one.
4. **Service key on the compensation refund:** `PaymentClient.refund()` must send
   `X-Service-Api-Key: <payment_service_api_key>` from an environment variable.
5. **Send `eventTitle` with the booking** (or use `POST /payments/checkout`), so Payment can send `notify.refund`.
   No email address is needed: the refund email goes to the booking's `userId`.
6. **Payment's URL:** `events.payment.base-url` defaults to the User Service's port 8081; set
   `EVENTS_PAYMENT_BASE_URL` (e.g. `http://payment:8080`).

**Notification Service**
- Consume from **`notifications_service`** (bound to `notifications` with `notify.#`), with up to 3 competing
  consumers on that one queue. Implement the delayed retries with `notifications_service.retry.30s` / `.2m` /
  `.10m` (default exchange, TTL 30000 / 120000 / 600000 ms, dead-lettering back to `notifications_service`), log the
  final failure, and remove its old DLQ and old retry queue names. Payment depends only on the `notifications`
  exchange and the `refund` / `admin_alert` contracts, which are unchanged.

**User Service**
- No change needed for Payment. Open question for the team: it uses UUID user IDs, while Payment's
  `booking.user_id` is an integer.

**Frontend (Admin dashboard)**
- An `ADMIN` login, then a dashboard that calls `GET /admin/refunds/dlq/count` on load and after each action
  ("Failed Refunds: N" when N > 0, otherwise "No failed refunds require attention"), lists
  `GET /admin/refunds/failed` in a table, and calls `POST /admin/refunds/{paymentId}/retry` per row (disable the
  button while it runs; show the outcome or error). Send `Authorization: Bearer <token>`. The current testing page
  still calls the deprecated `retry-refund` alias with the operator key, which keeps working.

**`services/payment/dev.env`**
- Add `auth_jwt_secret=<the issuer's AUTH_JWT_SECRET>`. Optionally `rabbitmq_management_url` (the default,
  `http://<rabbitmq_host>:15672`, works with this folder's `compose.yaml`).
- `payment_admin_api_key`, `payment_service_api_key`, `stripe_secret_key`, `stripe_webhook_secret` and
  `stripe_publishable_key` as before.

**Each developer's local MySQL**
- Apply `db/migrations/V4__payment_refund_failure.sql` once (and V2/V3 if not done yet; see
  [Local MySQL](#local-mysql)).

**Existing RabbitMQ brokers**
- See [Migrating an existing RabbitMQ broker](#migrating-an-existing-rabbitmq-broker).

---

## Diagrams

PlantUML sequence diagrams in [`docs/`](docs). `->` is a synchronous call, `-->` a synchronous response and
`->>` an asynchronous message.

| File | Scenario |
|---|---|
| [`docs/scenario1-payment.puml`](docs/scenario1-payment.puml) | Ticket payment: Event Service → Payment → Stripe (synchronous), with the pending and webhook path |
| [`docs/scenario3-refunds.puml`](docs/scenario3-refunds.puml) | Event cancellation refunds: `event.cancelled`, each refund processed inside Payment, `notify.refund`, failed refunds published to `payments.refund.dlx` → `payments.refunds.dlq` with `notify.admin_alert`, and the Admin's recovery over HTTP (ack on success) |

Render them with the VS Code PlantUML extension, <https://www.plantuml.com/plantuml>, or
`java -jar plantuml.jar docs/*.puml`.

---

## GitLab CI/CD

[`.gitlab-ci.yml`](.gitlab-ci.yml) has three stages. Release means publishing the image only; deploying is done
by hand from the infrastructure project (see [Deploying to AWS ECS](#deploying-to-aws-ecs)).

**Stripe in CI.** No job calls Stripe and no Stripe key is needed: the integration tests use WireMock. (A
`stripe_secret_key` CI/CD variable left over from earlier is no longer used and can be deleted.)

| Stage | Jobs |
|---|---|
| `static_analysis` | `compile` (Maven: compiles main and test sources and packages `payment.jar`), `checkstyle` (report artifact), `spotbugs` (report artifact), `dependency-scan` (Trivy on the libraries bundled in `payment.jar`, HIGH/CRITICAL, non-blocking; it doesn't scan `pom.xml`, because that makes Trivy hit Maven Central, which rate-limits shared runners), `semgrep-sast` (GitLab SAST), `secret_detection` (GitLab Secret Detection) |
| `test` | `unit-tests` (`mvn verify`, JUnit report + JaCoCo coverage), `integration-tests` (Docker Compose + `mvn -P integration verify`, with WireMock as Stripe; the job fails if any `*IT` fails; logs and Failsafe JUnit reports are saved; the environment is always torn down) |
| `release` | `release-image` (build, **smoke test** the image with [`ci/smoke-test.sh`](ci/smoke-test.sh) against a throw-away MySQL and RabbitMQ (see [Docker](#docker)), and only if it passes push `$CI_REGISTRY_IMAGE/$CI_COMMIT_REF_SLUG:$CI_COMMIT_SHA` to the GitLab Container Registry; Syft SBOM → `sbom.json` artifact), `container-scan` (Trivy, non-blocking) |

### Deploying to AWS ECS

The AWS side (ECS service, MySQL RDS database, secrets) is defined in the **infrastructure** project
(`cs302-2026/g4-team2/infrastructure`, `templates/payment.yaml`, CloudFormation stack `partyfun-payment`); see its
README. This project only releases the image; nothing deploys automatically:

1. A push to `main` passes the tests, and `release-image` pushes
   `registry.gitlab.com/cs302-2026/g4-team2/services/payment/main:<commit sha>`.
2. In the infrastructure project, run ▶ `deploy-payment` in a `main` pipeline. It deploys this project's newest
   `main` commit (or the commit in the CI/CD variable `PAYMENT_IMAGE_TAG`, e.g. to roll back), and waits until the
   new task is healthy (otherwise ECS rolls back to the previous version).

No AWS credentials or `CI_AWS_ECS_*` variables are needed in this project. The infrastructure project needs
`PAYMENT_REGISTRY_USERNAME` / `PAYMENT_REGISTRY_TOKEN` (a deploy token of this project with `read_registry`), this
project's **Job token permissions** to allow it, and the Stripe sandbox keys in Secrets Manager (`partyfun/payment`).

**Settings on AWS** are the same lowercase variables as locally (`db_host`, `stripe_secret_key`, ...): plain ones on
the task definition, secrets from AWS Secrets Manager. Stripe stays sandbox-only (`sk_test_`).

## Tracing (OpenTelemetry + Jaeger)

Every HTTP request and RabbitMQ message this service handles becomes a span, sent to Jaeger with Spring Boot's
OpenTelemetry starter (`spring-boot-starter-opentelemetry`). Trace context travels in the W3C `traceparent` header,
over HTTP and inside RabbitMQ messages, so one user action shows as one trace across all the team's services.
Log lines also carry the trace id.

| Setting | Default | Meaning |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Jaeger's OTLP/HTTP address; on AWS `http://jaeger.<namespace>:4318` |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Share of traces kept (Spring's own default would be 10%) |

Try it locally:

```bash
docker run --rm -p 16686:16686 -p 4318:4318 jaegertracing/jaeger:latest
```

Then start the service, make a request, and open Jaeger at <http://localhost:16686> (service `payment`). If Jaeger
isn't running, the service works normally and only logs that it couldn't export spans.
