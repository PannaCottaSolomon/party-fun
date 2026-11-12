# Notifications service

Sends emails (through [Resend](https://resend.com)) for sign-ups, new accounts, event changes, refunds and admin alerts.

Other services trigger it in either of two ways, using the **same JSON payload**:

| Way | How | Use it when |
|---|---|---|
| **RabbitMQ** (main way) | Publish to the `notifications` topic exchange with routing key `notify.<type>` | Normal flow: fire and forget, retried automatically |
| **HTTP** | `POST /notify_<type>` | Quick testing with curl/Postman, or when a caller must know the email went out |

## Quick start

```bash
cp .env.example .env            # dry-run mode by default: emails are logged, not sent
docker compose up --build       # starts RabbitMQ + this service
```

Then, in another terminal:

```bash
# HTTP
curl -X POST localhost:5000/notify_refund -H 'Content-Type: application/json' -H 'X-API-Key: dev-api-key' -d '{
  "notification_id": "refund-reg-1001", "email": "you@example.com", "name": "Alex",
  "event_title": "Beach Day", "amount": 1500, "registration_id": 1001 }'

# RabbitMQ (needs `pip install pika`)
python scripts/publish_test_message.py refund
python scripts/publish_test_message.py signup --broken   # invalid -> logged and discarded

# Did it go out? (the notification log)
curl -H 'X-API-Key: dev-api-key' 'localhost:5000/notifications?email=you@example.com'
```

Watch the service logs for `[dry run] email to y***@example.com: ...`, and the queues at <http://localhost:15672> (guest / guest).

To send real emails, set `RESEND_API_KEY` and `EMAIL_DRY_RUN=false` in `.env`. Until you verify your own domain in Resend, the `onboarding@resend.dev` sender can generally only deliver to the email address on your Resend account, so test with that address.

### Testing with Bruno

Open the `bruno/` folder in [Bruno](https://www.usebruno.com) (**Open Collection**), pick the **Local** environment (top right), then send requests one by one or run them all with **Runner**. Each request has assertions, so the Runner shows pass/fail. Every run uses fresh `notification_id`s, except the two "Duplicate Check" requests, which deliberately reuse one.

The admin alert request needs `ADMIN_EMAIL` set, and the **Local** environment's `apiKey` must match the service's `API_KEY` (both are `dev-api-key` in `.env.example`). "Missing API Key" checks that requests without the key are refused.

## Message contract

The live version is always at `GET /notification-types`.

| Type | HTTP endpoint | Routing key | Required fields | Optional fields |
|---|---|---|---|---|
| signup | `POST /notify_signup` | `notify.signup` | email, event_title, event_date, location, registration_id | amount, currency |
| account_created | `POST /notify_account_created` | `notify.account_created` | email, role (`participant` / `organiser`) | |
| event_created | `POST /notify_create_event` | `notify.event_created` | email, event_title, event_date, location, event_id | |
| event_updated | `POST /notify_update_event` | `notify.event_updated` | email, event_title, event_id, changes | audience |
| event_deleted | `POST /notify_delete_event` | `notify.event_deleted` | email, event_title, event_id | reason, audience |
| refund | `POST /notify_refund` | `notify.refund` | email, event_title, amount, registration_id | currency, refund_id |
| admin_alert | `POST /notify_admin_alert` | `notify.admin_alert` | source, error | email, details |

Every type also accepts **`notification_id`** (strongly recommended), **`name`** (defaults to "there") and **`user_id`**.

**Who receives it: send `user_id` instead of `email`.** Wherever the table says `email`, you can send the user's id
instead, and this service looks up their email address and name in its own user directory (see
[User directory](#user-directory)). Events and Payment should send `user_id`, so email addresses stay with the
Authentication service and this one. `email` still works; if both are sent, `email` wins. A `user_id` that isn't in the
directory yet is retried like a failed email (the user's `user.created` may still be on its way), then dropped.

Field rules:

- **amount**: integer **in cents**, like Stripe (`1500` = SGD 15.00). **currency**: 3 letters, default `SGD`.
- **event_date**: ISO 8601 string, e.g. `2026-11-14T20:00:00+08:00` (shown as "Sat, 14 Nov 2026, 8:00 PM").
- **changes**: an object (`{"location": "New hall", "start time": "9 PM"}`) or a list of strings.
- **audience** (`event_updated`, `event_deleted`): `organiser` (default) gets a confirmation; `participant` gets a heads-up. The Event Service sends one `participant` message per registered user, plus one `organiser` message.
- **admin_alert** goes to `ADMIN_EMAIL` unless you pass `email`. The Payment service publishes one whenever a message lands in its dead-letter queue (e.g. a refund that failed after all retries), per the consultation feedback.
- Extra fields are ignored, so adding fields never breaks this service.

Full examples for every type are in [`app/samples.py`](app/samples.py).

### Publishing from other services

**Python (pika):**

```python
channel.basic_publish(
    exchange="notifications",
    routing_key="notify.refund",
    body=json.dumps({
        "notification_id": f"refund-reg-{registration_id}",
        "user_id": user_id,
        "event_title": event_title, "amount": amount_cents,
        "registration_id": registration_id, "refund_id": stripe_refund_id,
    }),
    properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
)
```

**Spring Boot (Spring AMQP):** register a JSON message converter once (`Jackson2JsonMessageConverter`, or `JacksonJsonMessageConverter` on Spring AMQP 4), then:

```java
rabbitTemplate.convertAndSend("notifications", "notify.signup", Map.of(
    "notification_id", "signup-booking-" + bookingId,
    "user_id", booking.getUserId(),
    "event_title", event.getTitle(),
    "event_date", event.getStartTime().toString(),
    "location", event.getLocation(),
    "registration_id", registrationId,
    "amount", amountCents));
```

## User directory

This service keeps its own copy of each user's email address and name, so other services only send a `user_id`.

```
Authentication --user.created / user.updated / user.deleted--> [user.events] topic exchange
                                         | binding user.*
                                         v
                              [notifications.users] queue --> users table (user id -> email, name)
```

- **created / updated:** saves the id, email address and full name. Nothing else from the message (e.g. phone
  number) is stored.
- **deleted:** removes the user, so their address doesn't stay behind.
- **Database down:** the message waits in `notifications.users.retry.30s` and is tried again, up to 20 times
  (about 10 minutes).
- **Two kinds of id.** A user can be named by their Cognito `sub` (what Authentication uses) or by the number the
  frontend derives from it (`toServiceId` in the frontend), which Events and Payment use for now. Drop the second
  once every service takes the user from the token.
- **Users who registered before this service first started listening** aren't in the directory. Re-register them,
  or have them update their profile (which sends `user.updated`).

Try it locally:

```bash
python scripts/publish_test_message.py --create-user u-123 --email you@example.com --name "Your Name"
python scripts/publish_test_message.py signup --user-id u-123 --fresh-id
```

**Testing with Resend's test sender.** `onboarding@resend.dev` only delivers to your Resend account's own address.
Set `EMAIL_REDIRECT_TO` to that address: every email then goes there, with the real recipient in the subject
(`[to: alex@example.com] You're in! ...`). The notification log still records the real recipient. Leave it empty in
production.

## Reliability

```
producers --notify.<type>--> [notifications exchange] --notify.#--> [notification_service queue] --> consumer
                                                                       ^                             |      |
                                                       after 30s / 2m / 10m       temporary failure  |      | permanent failure
                                                                       |                             v      v
                                                        [notification_service.retry.30s|2m|10m] logged, recorded as 'failed', discarded
```

| What happens | Result |
|---|---|
| Email sent | Message acknowledged |
| Same `notification_id` arrives again (RabbitMQ redelivery, producer retry) | Acknowledged, **no second email** |
| Resend down / rate-limited / network error | Retried 3 times, each waiting longer: after 30 s, 2 min, then 10 min (`RETRY_DELAYS_MS`). Still failing after about 12 minutes → discarded |
| Bad JSON, missing fields, unknown type, address rejected | Discarded straight away (retrying can't fix it) |
| Any discarded message | An error log line, and a `failed` row in the notification log with `error` starting `discarded:`, so `GET /notifications` shows what was lost and why. Each earlier attempt has its own `retrying` row |
| Notification log database unreachable | Retried later, since duplicates can't be checked |
| Container stopped (deploy, scale-in) | Finishes the current message; unstarted messages go back to RabbitMQ for the next container |

**Exactly-once emails:** before sending, the service checks the **notification log** for the same type + `notification_id` already marked `sent`. The log is a database, so the check is shared by every copy of the service and survives restarts (no Redis needed). The same id is also sent to Resend as an `Idempotency-Key`, which Resend honours for 24 hours: that covers the rare case of two copies handling the same message at the same moment. Use ids that are stable for the real-world action, e.g. `refund-reg-1001`, not a random id per publish.

**Graceful shutdown:** ECS and Kubernetes send `SIGTERM` and wait (30 s by default) before killing a container. The service then stops taking new messages, finishes the one in progress (up to `SHUTDOWN_TIMEOUT_S`), closes its RabbitMQ connection so unstarted messages return to the queue, reports `/ready` as `503`, and exits.

**No dead-letter queue, by design:** the team's DLQ lives in the Payment service, because a lost payment or refund is real money and someone must look at every one. A lost email is low-stakes and recoverable: temporary failures are retried, and anything given up on is in the notification log, where a human can find it and resend it. The trade-off: if Resend is down for longer than the retry window (about 12 minutes with the default `RETRY_DELAYS_MS`), those emails are dropped rather than parked, so set up an alarm on `discarding` log lines (see Logs). When Payment's DLQ catches something, Payment publishes `notify.admin_alert` and this service emails `ADMIN_EMAIL`.

(The retry queues use RabbitMQ's dead-letter *routing* to send messages back to the main queue after the delay; nothing is ever parked there.)

**Why several retry queues:** RabbitMQ gives every message in a queue the same wait (`x-message-ttl`), so each delay gets its own queue: `notification_service.retry.30s`, `.retry.2m`, `.retry.10m`. The delay is in the name, so changing `RETRY_DELAYS_MS` creates new queues instead of clashing with existing ones (RabbitMQ refuses to change a queue's settings). Once old retry queues are empty, delete them in the RabbitMQ UI; that includes `notification_service.retry` from earlier versions.

## HTTP API

| Endpoint | Response |
|---|---|
| `POST /notify_*` | `200 {"status": "sent" \| "duplicate", "email_id": ...}`<br>`400` invalid payload, with `details` per field<br>`502` email provider error, with `retryable`<br>`503` notification log unavailable (retry)<br>`401` missing or wrong API key |
| `GET /notifications` | The notification log, newest first: answers "did Alex get the email?" See [below](#get-notifications-did-alex-get-the-email) |
| `GET /notification-types` | The message contract as JSON |
| `GET /health` | Liveness: `200` while the process is up |
| `GET /ready` | Readiness: `503` until the RabbitMQ consumer is connected, and while shutting down |

### `GET /notifications`: did Alex get the email?

Searches the notification log. Requires the `X-API-Key` header. All query parameters are optional and can be combined; with none, it returns the 50 most recent rows.

| Query parameter | Example | Filters to |
|---|---|---|
| `email` | `alex@example.com` | Emails sent (or attempted) to this address. Not case-sensitive |
| `type` | `refund` | One notification type: `signup`, `account_created`, `event_created`, `event_updated`, `event_deleted`, `refund`, `admin_alert` |
| `notification_id` | `refund-reg-1001` | One specific notification, by the id its sender gave it |
| `limit` | `20` | How many rows to return, newest first. Default `50`, maximum `200` |

Example: did Alex get the refund email?

```bash
curl -H 'X-API-Key: dev-api-key' \
  'localhost:5000/notifications?email=alex@example.com&type=refund'
```

```json
{
  "count": 2,
  "notifications": [
    {"id": 42, "type": "refund", "notification_id": "refund-reg-1001", "recipient": "alex@example.com",
     "subject": "Refund issued: Beach Day", "status": "sent", "attempt": 2, "email_id": "4ef9...",
     "error": null, "created_at": "2026-10-19 08:15:32.114000"},
    {"id": 41, "type": "refund", "notification_id": "refund-reg-1001", "recipient": "alex@example.com",
     "subject": "Refund issued: Beach Day", "status": "retrying", "attempt": 1, "email_id": null,
     "error": "Resend returned 503: ...", "created_at": "2026-10-19 08:15:02.007000"}
  ]
}
```

Reading it: attempt 1 failed (Resend was down) and was retried; attempt 2, 30 seconds later, was `sent`, so **Alex got the email**. Every attempt adds a row, and the newest row for an email is its current state:

| `status` | Meaning |
|---|---|
| `sent` | The email went out on this attempt |
| `retrying` | This attempt failed with a temporary error; a later attempt will follow |
| `failed` | Final. From RabbitMQ, `error` starts with `discarded:` and the email never went out. From `POST /notify_*`, the caller got the error and can retry |

`attempt` is 1 for the first try, 2 for the first retry, and so on (up to 4 with the default `RETRY_DELAYS_MS`). `created_at` is in UTC.

Errors: `401` if the API key is missing or wrong, `400` if `limit` isn't a number, `503` if the notification log database is unreachable.

### Security

`POST /notify_*` and `GET /notifications` require an `X-API-Key` header matching `API_KEY` (compared in constant time). `/health`, `/ready` and `/notification-types` stay open for health checks. Two layers:

1. **Network:** don't route this service through the public API gateway. The frontend never calls it; other services reach it inside the VPC / cluster.
2. **API key:** stops anything else on the internal network from sending emails or reading the log. On ECS, put `API_KEY` in AWS Secrets Manager like the Resend key.

The notification log stores recipient email addresses, which is why reading it also needs the key.

### Logs

With `LOG_FORMAT=json` (the Docker image's default) each log line is one JSON object, e.g.

```json
{"time": "2026-10-06T10:15:32.114+00:00", "level": "INFO", "service": "notifications", "logger": "app.service",
 "message": "sent refund refund-reg-1001 to p***@example.com on attempt 2 (email id 4ef9...)", "trace_id": "5b8aa5a2d2c872e8321cf37308d69df2", "span_id": "051581bf3cb55c13"}
```

so CloudWatch Logs Insights can filter by `level` or `trace_id`. When tracing is on, `trace_id` links each line to its trace in Jaeger. Email addresses are masked in logs.

Each attempt to send an email logs one line, using the same attempt numbers as the notification log. Search for the `notification_id` to see one email's history:

| Level | Message | Meaning |
|---|---|---|
| `INFO` | `sent signup signup-reg-2002 to s***@example.com on attempt 2 (email id ...)` | The email went out |
| `WARNING` | `attempt 1 of 4 failed for notify.signup signup-reg-2002, retrying in 30s via notification_service.retry.30s: Resend returned 503: ...` | Temporary failure; a retry is queued |
| `ERROR` | `discarding notify.signup signup-reg-2002 after attempt 4 of 4: gave up after 3 retries: Resend returned 503: ...` | Dropped: no more retries, or it can never succeed (bad payload, rejected address) |

**Alarm on lost emails:** every discarded message logs an `ERROR` line containing `discarding`. In CloudWatch, add a metric filter on the log group for `{ $.level = "ERROR" && $.message = "*discarding*" }` and an alarm (count > 0 in 5 minutes) that emails the team through SNS. Nobody has to watch the logs.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672/%2F` | Broker connection. Or, when it's not set: `RABBITMQ_HOST`, `RABBITMQ_PORT` (5672), `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` (`/`) |
| `NOTIFICATION_EXCHANGE` | `notifications` | Topic exchange producers publish to |
| `NOTIFICATION_QUEUE` | `notification_service` | This service's queue (the `.retry.<delay>` queue names are derived from it) |
| `RETRY_DELAYS_MS` | `30000,120000,600000` | Wait before each retry, in ms. One retry per value (default: 3 retries over about 12 minutes), then the email is discarded |
| `ENABLE_CONSUMER` | `true` | `false` = HTTP only |
| `PREFETCH_COUNT` | `10` | Messages handled at once before acknowledging |
| `RESEND_API_KEY` | (empty) | Empty means dry run |
| `RESEND_TIMEOUT_S` | `10` | Timeout for each call to Resend |
| `EMAIL_FROM` | `Campus Events <onboarding@resend.dev>` | Sender |
| `EMAIL_DRY_RUN` | `false` | `true` logs emails instead of sending |
| `ADMIN_EMAIL` | (empty) | Recipient for `admin_alert` |
| `EMAIL_REDIRECT_TO` | (empty) | Testing: send every email here instead, real recipient in the subject |
| `USER_EXCHANGE` / `USER_QUEUE` | `user.events` / `notifications.users` | Where the user directory's messages come from |
| `USER_DIRECTORY_ENABLED` | `true` | `false` stops listening for user messages |
| `APP_NAME` | `Campus Events` | Name shown in emails |
| `PORT` | `5000` | HTTP port |
| `DATABASE_URL` | `sqlite:///data/notifications.db` | Notification log and user directory: `sqlite:///path`, `mysql://user:pass@host:3306/db` (RDS) or `memory`. Or, when it's not set: `DB_HOST`, `DB_PORT` (3306), `DB_NAME` (`notifications`), `DB_USERNAME`, `DB_PASSWORD` (MySQL) |
| `API_KEY` | (empty) | Required `X-API-Key` for `/notify_*` and `/notifications`; empty = no check |
| `SHUTDOWN_TIMEOUT_S` | `25` | Time to finish the current message on shutdown (keep below the platform's 30 s) |
| `LOG_FORMAT` | `text` (`json` in the Docker image) | `json` = one JSON object per line, with trace ids |
| `LOG_LEVEL` | `INFO` | Log level |
| `OTEL_ENABLED` | `false` | Turn on OpenTelemetry tracing |

## Tests

```bash
pip install -r requirements-dev.txt
python -m pytest                      # unit tests (no RabbitMQ or Resend needed)
ruff check app tests scripts          # code style (same as CI)
pylint app                            # code quality (CI needs a score >= 7)

# integration tests against a real RabbitMQ and MySQL
docker run -d --rm -p 5672:5672 rabbitmq:3.13
docker run -d --rm -p 3306:3306 -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=notifications mysql:8.4
export INTEGRATION_RABBITMQ_URL=amqp://guest:guest@localhost:5672/%2F
export INTEGRATION_MYSQL_URL=mysql://root:test@127.0.0.1:3306/notifications
python -m pytest
```

The unit tests use a fake channel that simulates the broker, including a full retry → discard cycle, and SQLite for the notification log. The integration tests (`tests/test_integration_rabbitmq.py`, `tests/test_integration_mysql.py`) are skipped unless their URL is set; the CI `test` job starts RabbitMQ and MySQL and runs them.

**On AWS:** the default SQLite file lives inside the container, so it's lost on every deploy and isn't shared between copies of the service. Point `DATABASE_URL` at MySQL (the team's MySQL server or RDS). Create the `notifications` database first (see the team's `production.sql`); the service creates its table automatically on first use. A table created before attempt numbers existed gets its `attempt` column added automatically on startup, which needs the database user to have `ALTER` permission; otherwise run `ALTER TABLE notifications ADD COLUMN attempt INT NULL AFTER status;` once.

## Running on AWS (ECS)

On AWS the service is one **ECS Fargate task** running the same image as locally, with its notification log and
user directory in its own **MySQL RDS** database. It is defined and deployed in the **infrastructure** repo:
`templates/notifications.yaml`, deployed by its `deploy-notifications` job. That repo's README lists the one-time
setup (a GitLab deploy token, the Resend key in Secrets Manager) and what the stack creates.

The service creates its exchanges, queues and tables itself on startup, so nothing has to be prepared on RabbitMQ or
MySQL. On AWS, ECS passes the broker and database logins from Secrets Manager as separate variables, so the service
also accepts them in parts instead of `RABBITMQ_URL` / `DATABASE_URL` (see [Configuration](#configuration)).

(Lambda was considered: its RabbitMQ trigger only works with Amazon MQ, not a self-hosted broker, so with RabbitMQ on
ECS an always-on consumer is the standard design.)

**Deploy:** run `deploy-notifications` in a `main` pipeline of the infrastructure repo, once this project's `main`
pipeline has released its image. ECS starts the new task, waits for its health check (`/ready`), then stops the old
one; that one gets `SIGTERM`, finishes the email in progress and hands its unstarted messages back to RabbitMQ.
**Roll back:** run `deploy-notifications` with `NOTIFICATIONS_IMAGE_TAG` set to an older commit.

## CI/CD (GitLab)

This service is its own GitLab project, so `.gitlab-ci.yml` sits at the repo root and runs on every push:

| Stage | Jobs | What they do |
|---|---|---|
| static_analysis | `ruff`, `pylint`, `dependency-scan`, `semgrep-sast`, `secret_detection` | Code style, code quality (pylint score ≥ 7), known-vulnerable dependencies (reports only), GitLab's security and secret scanners |
| test | `test` | Unit tests + integration tests against real RabbitMQ and MySQL service containers; test results and coverage show in the merge request |
| release | `release-image`, `container-scan` | Build and push the image to this project's Container Registry, generate an SBOM, Trivy scan (reports only) |
Deploying isn't part of this pipeline: the infrastructure repo's `deploy-notifications` job deploys the image this pipeline released, with the whole CloudFormation stack (so the task definition never drifts from the template). Runtime settings (`RESEND_API_KEY`, `ADMIN_EMAIL` and the rest) are set by that stack, not here.

## Tracing (OpenTelemetry + Jaeger)

```bash
# in .env: OTEL_ENABLED=true
docker compose --profile tracing up --build
```

Open Jaeger at <http://localhost:16686> and pick `notifications`. Flask requests, RabbitMQ messages and Resend calls are traced automatically. Trace headers are kept when a message is retried, so a refund's trace stays connected across retries. For traces to join up with the producing services, they need OpenTelemetry too (the pika and Spring AMQP instrumentations add the `traceparent` header for you).

Each message's consumer span also gets these attributes, so you can search for them in Jaeger (Tags box, e.g. `severity=error`):

| Attribute | Example | Meaning |
|---|---|---|
| `notification.type` | `refund` | Routing key without `notify.` |
| `notification.id` | `refund-reg-1001` | The message's id |
| `notification.attempt` | `2` | 1 = first try, up to 4 |
| `notification.retried` | `true` | Attempt is after a retry |
| `notification.outcome` | `sent` / `duplicate` / `retrying` / `discarded` | What happened |
| `notification.reason` | `Resend returned 503` | Only when retrying or discarded; email addresses are masked |
| `severity` | `info` / `warning` / `error` / `critical` | See below |

- **info**: sent on the first try, or a duplicate that was skipped.
- **warning**: will be retried, or sent after a retry.
- **error**: dropped (bad message, or last retry failed). The span is marked as an error.
- **critical**: dropped because Resend rejected our API key (401): every email will fail until it is fixed.

MySQL queries are traced too (pymysql). `GET /notifications`, `/health` and `/ready` are not traced (`OTEL_PYTHON_FLASK_EXCLUDED_URLS`), so recipients' email addresses in the log search never reach Jaeger, and health checks don't fill it with noise.

## Project layout

```
app/
  contracts.py      message contract and validation (start here)
  service.py        validate -> check log -> render -> send -> log
  store.py          notification log (SQLite / MySQL) and duplicate check
  logging_setup.py  text or JSON logs with trace ids
  main.py           startup and graceful shutdown
  messaging.py      ack / retry / discard decision for each message
  consumer.py       RabbitMQ wiring and retry queue
  routes.py         HTTP endpoints
  email_sender.py   Resend client (+ dry-run sender)
  templates.py      email rendering; HTML templates in email_templates/
  samples.py        example payload for every type
tests/              unit tests + integration tests (real RabbitMQ, real MySQL)
scripts/publish_test_message.py
bruno/              Bruno collection for the HTTP endpoints
.gitlab-ci.yml      CI/CD pipeline
```
