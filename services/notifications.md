# Notifications service

Source: [`notifications/`](notifications/) · Python 3.14 · Flask served by waitress · RabbitMQ (pika) · Resend for email · SQLite or MySQL · port 5000

## What it does

It turns "something happened" messages from the other services into **emails**.

- It accepts a notification two ways with the same payload: a RabbitMQ message (`notify.<type>`) or an HTTP call (`POST /notify_<type>`).
- It validates the payload, renders an HTML and plain-text email from a template, and sends it through Resend.
- It retries temporary failures with growing delays, and never sends the same notification twice.
- It keeps a **notification log** (one row per send attempt) that answers "did this person get that email?".
- It keeps a **user directory** (user id → email and name) built from the authentication service's `user.*` messages, so the other services only ever send a `user_id`.

## Where it sits

| Direction | Who | How |
|---|---|---|
| Consumes | Events service | `notifications` exchange: `notify.signup`, `notify.event_created`, `notify.event_updated`, `notify.event_deleted` |
| Consumes | Payment service | `notifications` exchange: `notify.refund`, `notify.admin_alert` |
| Consumes | Authentication service | `user.events` exchange: `user.created`, `user.updated`, `user.deleted` |
| Called by | Anyone with the API key | HTTP `POST /notify_*`, `GET /notifications` |
| Calls | Resend | `POST https://api.resend.com/emails` |

## Notification types

Defined once in [`contracts.py`](notifications/app/contracts.py). The same table drives validation, the HTTP routes and the routing keys.

| Type | Routing key | HTTP endpoint | Required fields | Optional |
|---|---|---|---|---|
| `signup` | `notify.signup` | `POST /notify_signup` | `email`, `event_title`, `event_date`, `location`, `registration_id` | `amount`, `currency` |
| `account_created` | `notify.account_created` | `POST /notify_account_created` | `email`, `role` | |
| `event_created` | `notify.event_created` | `POST /notify_create_event` | `email`, `event_title`, `event_date`, `location`, `event_id` | |
| `event_updated` | `notify.event_updated` | `POST /notify_update_event` | `email`, `event_title`, `event_id`, `changes` | `audience` |
| `event_deleted` | `notify.event_deleted` | `POST /notify_delete_event` | `email`, `event_title`, `event_id` | `reason`, `audience` |
| `refund` | `notify.refund` | `POST /notify_refund` | `email`, `event_title`, `amount`, `registration_id` | `currency`, `refund_id` |
| `admin_alert` | `notify.admin_alert` | `POST /notify_admin_alert` | `source`, `error` | `email`, `details` |

Every type also accepts `notification_id`, `name` and `user_id`.

- **Recipient:** send `email` or `user_id`. With a `user_id`, the address and name come from the user directory. `admin_alert` falls back to `ADMIN_EMAIL`.
- **Amounts** are integers in cents.
- **Unknown extra fields are ignored**, so producers can add fields without breaking this service.

## Other HTTP endpoints

| Endpoint | Purpose |
|---|---|
| `GET /notifications?email=&type=&notification_id=&limit=` | The notification log, newest first (max 200) |
| `GET /notification-types` | The contract above, as JSON |
| `GET /health` | Liveness: the process is up |
| `GET /ready` | Readiness: 200 once the RabbitMQ consumer is connected (or disabled), else 503 |

Paths starting with `/notify_` or `/notifications` need the `X-API-Key` header when `API_KEY` is set. The other three are open.

## How the pieces fit

```
main.py                      builds everything, starts the consumer thread and the HTTP server

RabbitMQ ─► consumer.py      NotificationConsumer (thread): topology, ack / retry / discard
               ├─ messaging.py   process()               decides ACK / RETRY / DISCARD for notify.*
               └─ users.py       process_user_message()  applies user.* to the user directory

HTTP ─────► routes.py        Flask blueprint: one POST per type, the log, health checks

both ─────► service.py       NotificationService.notify()   the shared core
               ├─ contracts.py     validate()           payload rules
               ├─ store.py         already_sent / record / find_user   log + user directory
               ├─ templates.py     render()             subject, HTML, plain text
               └─ email_sender.py  ResendSender / DryRunSender

config.py                    Config dataclass read from environment variables
tracing.py, logging_setup.py OpenTelemetry span attributes and JSON logs
```

### Modules

- [`main.py`](notifications/app/main.py): entry point (`python -m app.main`). Builds the `Config`, the service and the Flask app, starts `NotificationConsumer` unless `ENABLE_CONSUMER=false`, and serves HTTP with waitress (8 threads). On SIGTERM it stops the consumer first (letting the current message finish), then the HTTP server.
- [`__init__.py`](notifications/app/__init__.py): `create_app()`, the Flask application factory. It stores the config and the service in `app.extensions`.
- [`config.py`](notifications/app/config.py): `Config`, a frozen dataclass. It also works out the retry queue names from the delays.
- [`service.py`](notifications/app/service.py): `NotificationService` and `build_service()`. This is the one place an email is validated, de-duplicated, rendered, sent and logged.
- [`contracts.py`](notifications/app/contracts.py): `NotificationType`, the `TYPES` table, the per-field checks, `validate()`, and the two exceptions `ValidationError` (never retry) and `UnknownRecipient` (retry later).
- [`routes.py`](notifications/app/routes.py): the Flask blueprint. `_make_view()` generates one view per notification type.
- [`consumer.py`](notifications/app/consumer.py): `NotificationConsumer`, a `threading.Thread` that owns the RabbitMQ connection.
- [`messaging.py`](notifications/app/messaging.py): `process()` and the `Decision` dataclass. No RabbitMQ code, so it is easy to test.
- [`users.py`](notifications/app/users.py): `process_user_message()`, `UserDecision`, and `service_id()`.
- [`store.py`](notifications/app/store.py): the `NotificationStore` protocol with three implementations: `InMemoryStore`, `SQLiteStore`, `MySQLStore`. `open_store(url)` picks one.
- [`email_sender.py`](notifications/app/email_sender.py): `ResendSender` (real), `DryRunSender` (logs only), and `EmailSendError` with its `permanent` flag.
- [`templates.py`](notifications/app/templates.py): Jinja2 rendering, the `money`, `event_date` and `pretty` filters, and `html_to_text()`.
- [`email_templates/`](notifications/app/email_templates/): `base.html` plus one template per type.
- [`tracing.py`](notifications/app/tracing.py): adds type, id, attempt, outcome and severity to the active trace span. Does nothing when tracing is off.
- [`logging_setup.py`](notifications/app/logging_setup.py): text logs locally, one JSON object per line in containers, with trace ids.
- [`samples.py`](notifications/app/samples.py): an example payload for every type.

## The core: `NotificationService.notify()`

Both entry points end up here.

```
notify(type_name, payload, attempt=1, record_failures=True)
  1. look up the type in TYPES                       unknown → ValidationError
  2. _with_recipient(payload)
       has email → use it
       only user_id → store.find_user(user_id)       matches user_id OR service_id
            not found → UnknownRecipient             database down → StoreError
  3. contracts.validate(type, payload, admin_email)  bad payload → ValidationError
  4. store.already_sent(type, notification_id)       yes → return status "duplicate"
  5. templates.render(type, data, app_name)          → subject, HTML, text
  6. EMAIL_REDIRECT_TO set → send to that inbox, real recipient goes in the subject
  7. sender.send(Email(..., idempotency_key="<type>:<notification_id>"))
       fails → EmailSendError (record a 'failed' row if record_failures)
  8. store.record(LogEntry(... status "sent" ...))
  ← NotifyResult(type, status="sent", email_id)
```

Duplicates are stopped in two places: the notification log (step 4) and Resend's own `Idempotency-Key` header (step 7, remembered by Resend for 24 hours).

A failure to write the log row is swallowed and logged. The email has already gone out by then, and turning that into an error would cause a retry and a second email.

## Flow 1: a RabbitMQ notification

```
NotificationConsumer.on_message(channel, method, properties, body)
  → messaging.process(service, routing_key, body, headers, max_retries)
       routing key not notify.<known type>         → DISCARD
       body is not JSON                            → DISCARD
       service.notify(..., record_failures=False)
           ok or duplicate                         → ACK
           ValidationError                         → DISCARD
           EmailSendError, permanent               → DISCARD   (401 from Resend is flagged critical)
           EmailSendError (temporary), UnknownRecipient, StoreError, anything unexpected
               retries used up                     → DISCARD
               otherwise                           → RETRY
  → tracing.annotate(decision)
  → act on the decision:
       ACK      basic_ack
       DISCARD  service.record_discarded(...) writes a 'failed' row, then basic_ack
       RETRY    publish a copy to the retry queue, then basic_ack the original,
                then service.record_retrying(...) writes a 'retrying' row
```

### Retry topology

All declared by `NotificationConsumer.declare_topology()` on every connect.

```
producers ──notify.<type>──► [notifications] topic exchange
                                   │ binding notify.#
                                   ▼
                        [notification_service] queue ──► consumer
                                   ▲                        │ temporary failure
                  TTL expires      │                        ▼
                 [notification_service.retry.30s]    retry 1
                 [notification_service.retry.2m]     retry 2
                 [notification_service.retry.10m]    retry 3
```

Each retry queue has a message TTL equal to its delay and dead-letters back to the main queue through the default exchange. Because that changes the routing key, the original key travels in the `x-original-routing-key` header and the count in `x-retry-count`. Other headers (including `traceparent`) are kept.

After the last retry, or for a message that can never succeed, the message is **discarded**: logged at ERROR with the word "discarding" and recorded as `failed` in the notification log. This service has no dead-letter queue of its own.

The copy is published to the retry queue *before* the original is acked. If the process dies in between, the original is redelivered and the `notification_id` stops a double email.

## Flow 2: an HTTP notification

```
routes.require_api_key          before every request; 401 on a missing or wrong X-API-Key
routes.view (from _make_view)
  → service.notify(type, payload)
       ok                        200 {status: "sent" | "duplicate", type, email_id}
       ValidationError           400 {error: "validation_failed", details}
       UnknownRecipient          422 {retryable: true}
       EmailSendError            502 {retryable: not permanent}
       StoreError                503 {retryable: true}
```

HTTP calls are not retried by this service. The caller is told whether a retry could help.

## Flow 3: the user directory

```
Authentication ──user.created | user.updated | user.deleted──► [user.events] topic exchange
                                   │ binding user.*
                                   ▼
                        [notifications.users] queue
                                   ▼
NotificationConsumer.on_user_message
  → users.process_user_message(store, routing_key, body, headers, max_retries)
       user.deleted                 → store.delete_user(id)
       user.created / user.updated  → store.save_user(id, service_id(id), email, fullName)
       bad JSON, no id, no valid email → DISCARD (logged)
       StoreError (database down)      → RETRY through [notifications.users.retry.30s], up to 20 times
```

Each user is stored under **two ids**:

- `user_id`: the Cognito `sub` (a UUID), as the authentication service sends it.
- `service_id`: a number derived from the sub with 32-bit FNV-1a (`users.service_id()`), matching what the frontend computes. The events and payment services use integer user ids, so their notifications name the recipient by this number.

`store.find_user(ref)` matches either column. If a notification arrives before the user's `user.created` message, `UnknownRecipient` sends it round the retry queues until the user is known.

## Storage

One database, chosen by `DATABASE_URL`: `sqlite:///path` (default, a local file), `mysql://...` (for example AWS RDS, shared by all replicas) or `memory` (tests). Tables are created lazily on first use, so the service still starts if the database is briefly down.

**`notifications`**: one row per send attempt.

| Column | Notes |
|---|---|
| `type`, `notification_id` | Indexed together; this is the duplicate check |
| `recipient`, `subject` | Recipient is lower-cased and indexed |
| `status` | `sent`, `retrying` or `failed` |
| `attempt` | 1 = first try, 2 = first retry, ... |
| `email_id` | Resend's message id |
| `error`, `created_at` | |

**`users`**: `user_id` (PK), `service_id` (indexed), `email`, `name`, `updated_at`.

## Email sending

- `ResendSender` posts to Resend with `requests`, one session per thread (the HTTP server and the consumer run in different threads).
- Retryable: network errors, 408, 429, any 5xx, and a 409 mentioning a concurrent request. Everything else is permanent.
- `DryRunSender` is used when `RESEND_API_KEY` is empty or `EMAIL_DRY_RUN=true`. It logs the email instead of sending it.
- HTML templates are rendered with autoescape on, so an event title cannot inject markup. The plain-text body is derived from the HTML.

## Configuration

Read by `Config.from_env()`; see [`.env.example`](notifications/.env.example).

| Variable | Default | Purpose |
|---|---|---|
| `RABBITMQ_URL` (or `RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_VHOST`) | local guest | Broker |
| `NOTIFICATION_EXCHANGE`, `NOTIFICATION_QUEUE` | `notifications`, `notification_service` | Topology names |
| `RETRY_DELAYS_MS` | `30000,120000,600000` | One retry per value |
| `ENABLE_CONSUMER` | `true` | `false` = HTTP only |
| `RESEND_API_KEY`, `EMAIL_FROM`, `EMAIL_DRY_RUN` | dry run | Email provider |
| `ADMIN_EMAIL` | empty | Recipient of `admin_alert` |
| `EMAIL_REDIRECT_TO` | empty | Testing: send every email to one inbox |
| `DATABASE_URL` (or `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`) | SQLite file | Log and user directory |
| `USER_EXCHANGE`, `USER_QUEUE`, `USER_DIRECTORY_ENABLED` | `user.events`, `notifications.users`, `true` | User directory |
| `API_KEY` | empty (no check) | HTTP shared secret |
| `SHUTDOWN_TIMEOUT_S` | 25 | Time to finish the current message on SIGTERM |
| `LOG_FORMAT`, `OTEL_ENABLED` | `text`, `false` | Logging and tracing |

## Things worth knowing

- **Emails are off by default.** With no `RESEND_API_KEY` the service runs in dry-run mode and only logs.
- **A failed email is eventually dropped, not parked.** After three retries (about 12 minutes) it is recorded as `failed` and discarded. `GET /notifications?type=...` is the only record of it.
- **Nothing publishes `notify.account_created`.** The type, endpoint and template exist, but none of the four services sends it. The authentication service's `user.created` only updates the user directory. The type also only accepts roles `participant` and `organiser`.
- **Notifications that name a `user_id` depend on the user directory.** If a `user.created` message was lost (the authentication service publishes best effort), every notification for that user retries and is then discarded.
- **`service_id` is a stop-gap.** `users.py` says to remove it once every service uses the Cognito sub. Two different subs could in theory hash to the same number.
- **With the default SQLite file, the log and the duplicate check are per container.** Replicas only share them when `DATABASE_URL` points at MySQL.
- **No API key means open endpoints.** With `API_KEY` unset, anyone who can reach the service can send emails and read the log.
