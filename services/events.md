# Events service

Source: [`events/`](events/) · Spring Boot 4.1 (Java 21) · PostgreSQL · RabbitMQ (publisher only) · calls the payment service over HTTP · port 8080

## What it does

It owns **events** and the **bookings** (tickets) people hold for them.

- Organisers create, update, delete and cancel events.
- Participants sign up for an event. Each ticket is one booking. A signup reserves the places first, then charges the card through the payment service.
- It keeps capacity correct under concurrent signups with a database row lock.
- It tells the notifications service what happened (signup confirmed, event created, updated, deleted) and tells the payment service when an event is cancelled so paid bookings get refunded.
- Three scheduled jobs clean up after failures: cancelling events that did not reach their minimum signups, re-sending undelivered `event.cancelled` messages, and recovering payments whose answer was lost.

## Where it sits

| Direction | Who | How |
|---|---|---|
| Called by | Frontend (through the API gateway) | HTTP `/events/**` with a Cognito access token |
| Calls | Payment service | HTTP: `POST /bookings`, `POST /payments`, `GET /payments/booking/{id}`, `POST /payments/{id}/refund` |
| Publishes to | Notifications service | RabbitMQ exchange `notifications`, keys `notify.signup`, `notify.event_created`, `notify.event_updated`, `notify.event_deleted` |
| Publishes to | Payment service | RabbitMQ exchange `party.events`, key `event.cancelled` |
| Consumes | Nothing | It has no RabbitMQ listeners |

## HTTP API

Every endpoint needs a Cognito access token. Roles come from the token's `cognito:groups` claim.

| Method and path | Roles | What it does |
|---|---|---|
| `POST /events` | ORGANISER, ADMIN | Create an event. 201 |
| `GET /events` | any role | List all events |
| `GET /events/{id}` | any role | One event |
| `PATCH /events/{id}` | ORGANISER, ADMIN | Partial update (only non-null fields are applied) |
| `DELETE /events/{id}` | ORGANISER, ADMIN | Delete the event and cancel its bookings. 204. No refunds |
| `POST /events/{id}/cancel` | ORGANISER, ADMIN | Same as delete, plus `event.cancelled` so the payment service refunds |
| `POST /events/{id}/signup?userId=&paymentMethodId=&quantity=` | any role | Buy 1 to 10 tickets. 201 with the bookings |
| `POST /events/{id}/bookings/{bookingId}/payment?paymentMethodId=` | any role | Retry the payment of a PENDING booking |
| `GET /events/bookings?userId=` | any role | A user's bookings in every status, newest first |
| `GET /actuator/health/**` | open | Health probes |

"Any role" means PARTICIPANT, ORGANISER or ADMIN. A token with no role group is refused.

## How the pieces fit

```
controller/   EventController ──────────────► EventService
              EventScheduler (3 cron jobs) ─► EventService, Outbox, PaymentRecoveryService
              EventExceptionHandler

service/      EventService                    orchestrates everything
                ├─ BookingReservationService  transaction 1: lock event, take places, save PENDING bookings
                ├─ BookingPaymentService      charge the order (no transaction held)
                │     ├─ PaymentClient              HTTP to the payment service
                │     ├─ BookingFinalizerService    short transactions recording the outcome
                │     └─ BookingRefundService       compensation refund if the booking is no longer PENDING
                └─ EventMessaging             RabbitMQ publishing
                      └─ Outbox               guaranteed delivery for event.cancelled
              PaymentRecoveryService          the every-minute safety net

repository/   EventRepository, BookingRepository, OutboxMessageRepository
entity/       Event, Booking, BookingStatus, OutboxMessage
client/       PaymentClient + PaymentResponse, RefundResponse, two exceptions
config/       SecurityConfig, CognitoGroupsConverter, RabbitMQConfig
```

### Classes by package

**`controller`**
- [`EventController`](events/src/main/java/events/controller/EventController.java): all `/events` endpoints. Every method delegates to `EventService`.
- [`EventScheduler`](events/src/main/java/events/controller/EventScheduler.java): three `@Scheduled` methods (see "Scheduled jobs").
- [`EventExceptionHandler`](events/src/main/java/events/controller/EventExceptionHandler.java): maps exceptions to RFC 7807 `ProblemDetail` responses.

**`service`**
- [`EventService`](events/src/main/java/events/service/EventService.java): the entry point for every use case. Event CRUD, validation, cancel, signup, payment retry, booking list, and the expired-event check.
- [`BookingReservationService`](events/src/main/java/events/service/BookingReservationService.java): `reserve()`. One transaction that locks the event row and creates the PENDING bookings. It is a separate bean so the call goes through the Spring proxy and `@Transactional` and `@Retryable` apply.
- [`BookingPaymentService`](events/src/main/java/events/service/BookingPaymentService.java): `pay()` charges an order once for all its tickets and records the outcome. `confirmIfPaid()` is used by recovery.
- [`BookingFinalizerService`](events/src/main/java/events/service/BookingFinalizerService.java): small transactional methods, each a conditional `UPDATE ... WHERE status = 'PENDING'`: `confirm`, `recordPaymentFailure`, `linkPaymentBooking`, `recordRefundOwed`, `recordRefundSettled`.
- [`BookingRefundService`](events/src/main/java/events/service/BookingRefundService.java): compensation refunds. Records the refund as owed, tries it, and keeps it owed until the payment service has taken it over.
- [`PaymentRecoveryService`](events/src/main/java/events/service/PaymentRecoveryService.java): `recover()` retries owed refunds and confirms bookings whose payment went through although the answer was lost.

**`messaging`**
- [`EventMessaging`](events/src/main/java/events/messaging/EventMessaging.java): builds and sends every outgoing message. Notifications are deferred until the current transaction commits, then sent best effort.
- [`Outbox`](events/src/main/java/events/messaging/Outbox.java): transactional outbox used only for `event.cancelled`.

**`client`**
- [`PaymentClient`](events/src/main/java/events/client/PaymentClient.java): `RestClient` wrapper with connect and read timeouts. It turns HTTP failures into two exceptions:
  - `PaymentRejectedException`: the payment service answered 4xx. A definite "no"; nothing was charged by that call.
  - `PaymentUnavailableException`: timeout, connection refused, 5xx or an unreadable answer. The outcome is unknown.

**`repository`**
- [`EventRepository`](events/src/main/java/events/repository/EventRepository.java): `findByIdForUpdate` takes a pessimistic write lock (`SELECT ... FOR UPDATE`, 5 s lock timeout).
- [`BookingRepository`](events/src/main/java/events/repository/BookingRepository.java): finder methods plus the conditional `@Modifying` updates the finalizer uses.
- [`OutboxMessageRepository`](events/src/main/java/events/repository/OutboxMessageRepository.java): oldest 100 messages, and a failed-attempt counter.

**`config`**
- [`SecurityConfig`](events/src/main/java/events/config/SecurityConfig.java): Spring Security resource server. Path rules here, plus `@PreAuthorize` on each controller method.
- [`CognitoGroupsConverter`](events/src/main/java/events/config/CognitoGroupsConverter.java): turns `cognito:groups` into `ROLE_<GROUP>` authorities.
- [`RabbitMQConfig`](events/src/main/java/events/config/RabbitMQConfig.java): declares the two topic exchanges and the JSON converter.

## Request flows

### The ids and words used below

Two services each keep their own "booking", and three different ids travel between them. These are easy to mix up.

| Name | What it is | Who creates it, and when | Where it is stored |
|---|---|---|---|
| **Booking** (events side) | One ticket. A signup for 3 tickets creates 3 bookings | `BookingReservationService.reserve`, at the start of a signup | events DB, table `bookings`. Its `id` is the "booking id" in every `/events/...` URL |
| **`orderId`** | A random UUID that groups the tickets of one signup | `reserve`, once per signup (`UUID.randomUUID()`) | `bookings.order_id`, the same value on every ticket of that signup. Events-only: the payment service never sees it |
| **order** (in the code) | Not a table. A `List<Booking>`: the PENDING tickets that share one `orderId`. It is what gets charged and confirmed together | Built in memory: the list `reserve` returns, or `BookingPaymentService.unpaidOrderOf(booking)` on a retry | nowhere |
| **`paymentMethodId`** | A Stripe PaymentMethod id such as `pm_card_visa`. It names *the card*, not a payment | The frontend's Stripe card form (or a Stripe test token) | nowhere in this service. It arrives as a request parameter and is passed straight on to `POST /payments` |
| **`paymentBookingId`** | The id of the **payment service's** booking row: its own record of "user X owes amount Y for event Z" | The payment service, when events calls `POST /bookings`. Once per order, on the first charge attempt | `bookings.payment_booking_id`, the same value on every ticket of the order (written by `linkPaymentBooking`) |
| **payment** / `paymentId` | One charge attempt in the payment service (one Stripe PaymentIntent). An order can have several: a declined one, then a paid one | The payment service, on every `POST /payments` | payment service DB only |
| **`paymentReference`** | The `paymentId` of the payment that matters for this ticket: the one that paid for it, or the one that must be refunded | `BookingFinalizerService.confirm` (paid) or `recordRefundOwed` (to refund) | `bookings.payment_reference` |

How they nest for one signup of 3 tickets:

```
events DB                                        payment service DB
─────────                                        ──────────────────
orderId 7f3a…                                    booking 55   (quantity 3, total 45.00)   ← paymentBookingId
  ├─ booking 101 ─┐                                ├─ payment 900  FAILED  (declined card)
  ├─ booking 102 ─┼─ paymentBookingId = 55         └─ payment 901  PAID    ← paymentReference = "901"
  └─ booking 103 ─┘
```

So: **many events bookings → one order → one payment-side booking → one or more payments, at most one of them PAID.**

### What is created when

| Moment | Created or changed | By |
|---|---|---|
| `POST /events` | 1 `events` row | `EventService.create` |
| Signup, step 1 | `quantity` rows in `bookings` (PENDING, shared new `orderId`, `expiresAt` = now + 15 min); `events.capacity` reduced by `quantity` | `BookingReservationService.reserve` |
| Signup, step 2a (paid events, first attempt only) | 1 booking in the **payment service**; its id written to `payment_booking_id` on every ticket | `PaymentClient.createBooking` then `BookingFinalizerService.linkPaymentBooking` |
| Signup, step 2b (paid events) | 1 payment in the **payment service** (and a Stripe PaymentIntent) | `PaymentClient.createPayment` |
| Payment succeeded | every ticket → CONFIRMED, `confirmed_at` and `payment_reference` set | `BookingFinalizerService.confirm` |
| Payment declined | every ticket stays PENDING, `failed_at` and `failure_reason` set | `BookingFinalizerService.recordPaymentFailure` |
| Paid, but the tickets are no longer PENDING | `refund_required_at` and `payment_reference` set, then a refund is requested | `BookingRefundService.refund` |
| Payment retry | a **new** payment in the payment service, on the **same** payment-side booking | `PaymentClient.createPayment` |
| Event deleted or cancelled | every PENDING/CONFIRMED ticket → CANCELLED; the `events` row is deleted | `EventService.delete` / `cancel` |
| Event cancelled | 1 `outbox_messages` row (removed once the broker confirms it) | `Outbox.enqueue` |

Nothing is created for a free event in the payment service: no payment-side booking, no payment.

### How to read the flows

Every flow below has the same two parts:

- **Who calls whom:** each call in order, as *caller → callee*, with what the call is for.
- **Scenarios:** the branches. Each row is one situation, the path it takes through those calls, and what the client, the database and the broker end up with.

Every request enters through `EventController`, which only unpacks the parameters and calls one `EventService` method. `EventService` is the orchestrator: it decides the order of the steps and delegates each one. The flows are grouped as:

| Group | Flows |
|---|---|
| Event CRUD | create, read, update, delete, cancel |
| Signups | signup, payment retry, list bookings |
| Refunds | compensation refund, cancellation refund, owed-refund retry |

### Flow 1: create an event (`POST /events`)

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.create` → `EventService.create(request)` | Start the use case. `@Transactional` opens here |
| 2 | `EventService.create` → `EventDto.toEntity()` | Copy the request into a new `Event`. `id`, `createdAt`, `updatedAt` from the body are ignored |
| 3 | `EventService.create` → `defaultStatus(status)` | No status sent → `"ACTIVE"` |
| 4 | `EventService.create` → `normaliseCurrency(currency)` | No currency sent → `"SGD"`. Anything other than SGD → `InvalidRequestException` |
| 5 | `EventService.create` → `validate(event, 1)` | Title not blank; end after start; `capacity` ≥ 1; price between 0 and 99,999,999.99 with at most 2 decimals |
| 6 | `EventService.create` → `EventRepository.save(event)` | INSERT. The database assigns the id |
| 7 | `EventService.create` → `EventMessaging.publishEventCreated(saved)` | Build `notify.event_created` for the organiser |
| 8 | `EventMessaging.publishEventCreated` → `send` → (after commit) `sendNow` → `RabbitTemplate.convertAndSend` | Publish once the transaction has committed |
| 9 | `EventService.create` → `EventDto.from(saved)` | Build the response |

Between steps 4 and 5, `maxCapacity` is set to `capacity` if it was not sent, and after step 5 `createdAt` and `updatedAt` are set to now (UTC).

**Scenarios**

| Scenario | Path | Result |
|---|---|---|
| Valid event | 1 → 9 | 201 with the saved event. One `events` row. `notify.event_created` sent |
| Blank title, end before start, capacity < 1, or bad price | stops at 5 | 400. Transaction rolled back, nothing saved, no message |
| Currency other than SGD | stops at 4 | 400 |
| No `organiserId` | 7 returns at once | 201. Event saved, **no message** (nobody to notify) |
| Broker down | 8 fails inside `sendNow` | 201. Event saved; a warning is logged and the email is lost |
| Database down | 6 throws `DataAccessException` | 503 |

No lock is taken: a row that does not exist yet cannot be contended.

### Flow 2: read events (`GET /events`, `GET /events/{id}`)

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.listAll` → `EventService.listAll()` | Read-only transaction |
| 2 | `EventService.listAll` → `EventRepository.findAll()` → `EventDto.from` each | Every event, no paging or filter |
| 1 | `EventController.get` → `EventService.get(id)` | Read-only transaction |
| 2 | `EventService.get` → `loadEvent(id)` → `EventRepository.findById` | Plain read, no lock |

**Scenarios**

| Scenario | Result |
|---|---|
| Event exists | 200 with the event |
| Unknown id, or an event that was deleted or cancelled | 404 `EventNotFoundException` (`get`); simply absent (`listAll`) |

### Flow 3: update an event (`PATCH /events/{id}`)

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.update` → `EventService.update(id, request)` | `@Transactional`. No `@Valid`: every field is optional |
| 2 | `EventService.update` → `loadEventForUpdate(id)` → `EventRepository.findByIdForUpdate` | `SELECT ... FOR UPDATE`. Signups for this event now wait, so the update cannot write back a stale `capacity` |
| 3 | `EventService.update` → `EventMessaging.participantFacingDetails(event)` | Snapshot **before**: title, location, starts, ends, ticket price |
| 4 | `EventService.update` → `applyUpdates(event, request)` | Copy each non-null field onto the entity |
| 5 | `applyUpdates` → `normaliseCurrency`, then `validate(event, 0)` | Same rules as create, but `capacity` may be 0 (a sold-out event has 0 places left) |
| 6 | `EventService.update` → `EventMessaging.participantFacingDetails(event)` + `changedDetails(before, after)` | Work out what a participant would notice, e.g. `{"location": "Hall B"}` |
| 7 | `EventService.update` → `participantsOf(id)` → `BookingRepository.findByEventIdAndStatusIn` | Distinct user ids holding a PENDING or CONFIRMED ticket |
| 8 | `EventService.update` → `EventMessaging.publishEventUpdated(event, changes, participants)` | One `notify.event_updated` per participant and one for the organiser, sent after commit |
| 9 | (commit) | No explicit save: JPA dirty checking writes the changed row |

**Scenarios**

| Scenario | Path | Result |
|---|---|---|
| Location, time, title or price changed | 1 → 9 | 200. Row updated. Each participant and the organiser get an email listing only what changed |
| Only `description`, `capacity`, `minSignUps`, `expiresAt` or `status` changed | 8 returns at once (`changes` is empty) | 200. Row updated, **no message** |
| Nobody has a ticket | 7 returns an empty list | 200. Only the organiser is notified |
| Change breaks a rule | stops at 5 | 400. Rolled back; the event is unchanged |
| Unknown id | stops at 2 | 404 |
| Row lock not acquired within 5 s | 2 throws `PessimisticLockingFailureException` | 503 "The event is busy, please try again" |

Two behaviours to know: a field cannot be cleared (`null` means "leave as it is"), and `capacity` is *places left* and is set directly by the request, not recalculated from the bookings.

### Flow 4: delete an event (`DELETE /events/{id}`)

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.delete` → `EventService.delete(id)` | `@Transactional` |
| 2 | `EventService.delete` → `loadEventForUpdate(id)` | Row lock |
| 3 | `EventService.delete` → `deleteLoadedEvent(event, id)` | The shared delete routine (also used by cancel) |
| 4 | `deleteLoadedEvent` → `participantsOf(id)` | Read who has a ticket **before** the tickets change |
| 5 | `deleteLoadedEvent` → `BookingRepository.cancelActiveBookingsForEvent(id)` | One UPDATE: every PENDING or CONFIRMED ticket → CANCELLED |
| 6 | `deleteLoadedEvent` → `EventRepository.delete(event)` | The `events` row is really removed |
| 7 | `deleteLoadedEvent` → `EventMessaging.publishEventDeleted(event, participants)` | `notify.event_deleted` per participant and for the organiser, after commit |

**Scenarios**

| Scenario | Result |
|---|---|
| Event with tickets | 204. Tickets CANCELLED and kept; event row gone; emails sent. **Nobody is refunded** |
| Event with no tickets | 204. Only the organiser is notified |
| Unknown id | 404 |
| A payment for one of its tickets is in flight | The delete wins. When the payment returns, the ticket is no longer PENDING, so Flow 9 refunds it |

### Flow 5: cancel an event (`POST /events/{id}/cancel`)

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.cancel` → `EventService.cancel(id)` | `@Transactional` |
| 2 | `EventService.cancel` → `loadEventForUpdate(id)` | Row lock |
| 3 | `EventService.cancel` → `deleteLoadedEvent(event, id)` | Exactly Flow 4, steps 4 to 7 |
| 4 | `EventService.cancel` → `EventMessaging.publishEventCancelled(event)` | Ask for the refunds |
| 5 | `publishEventCancelled` → `Outbox.enqueue("party.events", "event.cancelled", EventCancelledMessage)` | Save the message as an `outbox_messages` row in this same transaction |
| 6 | (after commit) `Outbox.relay()` → `publish` | Send it and wait for the broker's confirm |

`EventService.cancel` has a second caller: `EventService.processExpiredEvents`, run nightly by `EventScheduler`, for events that missed their `minSignUps`.

**Scenarios**

| Scenario | Result |
|---|---|
| Everything healthy | 200 with the deleted event. Tickets CANCELLED, event gone, emails sent, `event.cancelled` delivered. The payment service then refunds every paid payment (Flow 10) |
| Broker down | 200. The database change stands. `notify.event_deleted` emails are lost. `event.cancelled` stays in the outbox and is re-sent every 10 s until delivered |
| Payment service has never started (no queue bound) | 200. `event.cancelled` comes back as unroutable and stays in the outbox |
| Anything throws before commit | Error response. Nothing changed, no outbox row, no messages |
| Unknown id | 404 |

**Delete versus cancel:** the only difference is step 4. Use cancel when people have paid.

### Flow 6: signup (`POST /events/{id}/signup?userId=&paymentMethodId=&quantity=`)

`EventService.signUp` has no transaction of its own. It runs three steps, each managing its own, so that no database lock or connection is held while the payment service and Stripe are called.

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.signUp` → `EventService.signUp(eventId, userId, paymentMethodId, quantity)` | Start the use case |
| 2 | `EventService.signUp` → `BookingReservationService.reserve(...)` | **Step 1.** One transaction: check the rules, take the places, create the tickets |
| 2a | `reserve` → `EventRepository.findByIdForUpdate(eventId)` | Lock the event row; a concurrent signup for this event waits here |
| 2b | `reserve` → `BookingPaymentService.requiresPayment(event.ticketPrice)` | Is this a paid event? If so a `paymentMethodId` must be present |
| 2c | `reserve` → `BookingRepository.findByEventIdAndUserIdAndStatusAndExpiresAtLessThanEqual` | Find this user's PENDING tickets past their deadline; set them EXPIRED and give the places back |
| 2d | `reserve` → `BookingRepository.existsByEventIdAndUserIdAndStatusIn` | One signup per user per event |
| 2e | `reserve` → `newPendingBooking(...)` + `BookingRepository.save`, `quantity` times | **The bookings and the `orderId` are created here.** Commit releases the lock |
| 3 | `EventService.signUp` → `BookingPaymentService.pay(bookings, paymentMethodId)` | **Step 2.** Charge the whole order once. No transaction |
| 3a | `pay` → `requiresPayment(total)` | Free event? Then go straight to 3g with no payment reference |
| 3b | `pay` → `EventRepository.findById` | The event title, so the payment service can name the event in a refund email |
| 3c | `pay` → `PaymentClient.createBooking(userId, eventId, quantity, total, title)` | `POST /bookings`. **The payment-side booking is created here** (first attempt only) |
| 3d | `pay` → `BookingFinalizerService.linkPaymentBooking(order, paymentBookingId)` | Save `payment_booking_id` on every ticket *before* charging, so a retry or the recovery job can find it |
| 3e | `pay` → `PaymentClient.createPayment(paymentBookingId, paymentMethodId)` | `POST /payments`. **A payment is created and the card is charged here** |
| 3f | `pay` → `reconcile(exception, paymentBookingId)` → `findPaidPayment` → `PaymentClient.findPayments` | Only on a 409: is there already a PAID payment for this booking? |
| 3g | `pay` → `confirmOrRefund(order, payment)` → `BookingFinalizerService.confirm(order, paymentReference)` | PAID: tickets PENDING → CONFIRMED, all or none |
| 3h | `pay` → `BookingFinalizerService.recordPaymentFailure(order, reason)` | Declined or refused: keep the tickets PENDING, record `failed_at` and `failure_reason` |
| 3i | `confirmOrRefund` → `BookingRefundService.refund(order, paymentId)` | Paid, but the tickets are no longer PENDING: give the money back (Flow 9) |
| 4 | `EventService.signUp` → `publishSignupOutcome(bookings, confirmed)` → `EventMessaging.publishSignupConfirmation(order)` | **Step 3.** One `notify.signup` for the order, only if it is CONFIRMED |

Steps 3c and 3e are two separate calls to the payment service. Replacing them with its single `POST /payments/checkout` is discussed under [Open consideration: one payment call instead of two](#open-consideration-one-payment-call-instead-of-two).

**Scenarios: rejected before anything is created (step 1)**

All of these throw inside `reserve`, the transaction rolls back, and no ticket, order or payment exists afterwards.

| Scenario | Stops at | Result |
|---|---|---|
| No `userId`, or `quantity` outside 1 to 10 | start of `reserve` | 400 |
| Unknown event | 2a | 404 |
| Event status is not ACTIVE | after 2a | 409 `EventNotOpenException` |
| Paid event, no `paymentMethodId` | 2b | 400. No place is held for a signup that cannot pay |
| User already holds a PENDING or CONFIRMED ticket here | 2d | 409 `DuplicateBookingException`. A PENDING one means: retry the payment (Flow 7) |
| Fewer places left than `quantity` | after 2d | 409 `EventFullException`. All or nothing |
| Row lock not acquired | 2a, retried 3 times with backoff | 503 "The event is busy, please try again" |

**Scenarios: after the tickets exist (steps 2 and 3)**

From here the answer is always **201 with the bookings**. What differs is their status.

| Scenario | Path | Tickets | Money | Message |
|---|---|---|---|---|
| Free event | 3a → 3g with reference `null` | CONFIRMED | none; the payment service is never called | `notify.signup`, no amount |
| Paid, card accepted | 3b → 3c → 3d → 3e (`PAID`) → 3g | CONFIRMED, `paymentReference` set | charged once | `notify.signup` with the total |
| Card declined | 3e answers 201 with status `FAILED` → 3h | PENDING, `failureReason` = the decline reason | not charged | none |
| Payment needs more customer action | 3e answers a status that is neither PAID nor FAILED | PENDING, nothing recorded | not yet | none |
| Payment service refuses the booking | 3c gets 4xx → 3h | PENDING, `failureReason` set, no `paymentBookingId` | not charged | none |
| Payment service refuses the charge | 3e gets 4xx other than 409 → 3h | PENDING, `failureReason` set | not charged | none |
| Earlier attempt already paid | 3e gets 409 → 3f finds a PAID payment → 3g | CONFIRMED | charged once, not twice | `notify.signup` |
| 409 but nothing is PAID | 3e gets 409 → 3f finds none → 3h | PENDING, `failureReason` set | not charged | none |
| Payment service down before the booking call | 3c throws `PaymentUnavailableException`, caught by `signUp` | PENDING, no `paymentBookingId` | not charged | none |
| Timeout during the charge, card **not** charged | 3e throws, caught by `signUp` | PENDING, `paymentBookingId` set | not charged | none |
| Timeout during the charge, card **was** charged | 3e throws, caught by `signUp` | PENDING for now. The recovery job confirms them about a minute later | charged once | `notify.signup`, sent by the recovery job |
| Event cancelled or deleted while the charge was in flight | 3e `PAID` → 3g `confirm` changes 0 rows → 3i | CANCELLED | charged, then refunded (Flow 9) | none |
| Another request confirmed the order with this same payment | 3g `confirm` returns false, but every ticket is already CONFIRMED with this `paymentReference` | CONFIRMED | charged once | `notify.signup` (deduplicated by its `notification_id`) |

In every PENDING row the user keeps the places until `expiresAt` (15 minutes) and can pay with Flow 7.

**How `confirm` and `confirmOrRefund` decide**

`BookingFinalizerService.confirm` runs one statement:

```sql
UPDATE bookings SET status = 'CONFIRMED', confirmed_at = ?, payment_reference = ?
 WHERE id IN (...order ids...) AND status = 'PENDING'
```

If the number of rows changed equals the size of the order, it returns `true`. Otherwise it rolls its own update back and returns `false`, so an order is never half confirmed. The `AND status = 'PENDING'` is what makes the flow safe without holding a lock across the payment call: it can never overwrite a ticket that was cancelled or expired meanwhile.

When `confirm` returns `false`, `confirmOrRefund` reloads each ticket and counts those that are CONFIRMED with this very `paymentReference`:

| Count | Meaning | Action |
|---|---|---|
| all | a concurrent retry or the recovery job got there first with the same payment | return `true`; no refund |
| none | the tickets were cancelled or expired | `BookingRefundService.refund`; return `false` |
| some | should be impossible | log an error, keep the money, return `false` |

### Flow 7: payment retry (`POST /events/{id}/bookings/{bookingId}/payment?paymentMethodId=`)

For tickets left PENDING by Flow 6.

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.pay` → `EventService.payForBooking(eventId, bookingId, paymentMethodId)` | Start the use case. No transaction |
| 2 | `payForBooking` → `BookingRepository.findById(bookingId)` | Load the ticket and check it belongs to this event |
| 3 | `payForBooking` → `BookingPaymentService.unpaidOrderOf(booking)` → `BookingRepository.findByOrderIdOrderByIdAsc` | Rebuild the order: this ticket plus every other ticket of the same `orderId` that is still PENDING |
| 4 | `payForBooking` → `BookingPaymentService.pay(order, paymentMethodId)` | The same method as Flow 6, steps 3a to 3i |
| 5 | `payForBooking` → `publishSignupOutcome(order, confirmed)` | `notify.signup` if the order is now CONFIRMED |

Inside `pay`, `paymentBookingId` is normally already set, so `createBooking` and `linkPaymentBooking` are skipped: only `POST /payments` is called. That creates a **new payment** on the **same payment-side booking**. No new ticket and no new `orderId` are created.

**Scenarios**

| Scenario | Path | Result |
|---|---|---|
| No `paymentMethodId` | before 2 | 400 |
| Unknown booking, or it belongs to another event | 2 | 404 `BookingNotFoundException` |
| Ticket already CONFIRMED (e.g. paid through another ticket of its order) | after 2 | 200 with the ticket as it is. Nothing is charged |
| Ticket EXPIRED or CANCELLED | after 2 | 409 `BookingNotPayableException` |
| `expiresAt` has passed | after 2 | 409 "its payment window closed at …" |
| Card accepted | 3 → 4 → 5 | 200. Every unpaid ticket of the order CONFIRMED; one charge; `notify.signup` |
| Card declined again | 4 → `recordPaymentFailure` | 200. Still PENDING with the new `failureReason` |
| The earlier attempt had in fact charged the card | 4: `POST /payments` → 409 → `reconcile` finds the PAID payment → `confirm` | 200, CONFIRMED. **No second charge** |
| The first attempt never reached `POST /bookings` | 4 runs `createBooking` and `linkPaymentBooking` first | as a first attempt |
| Payment service gives no answer | 4 throws `PaymentUnavailableException`; not caught here | 503. Nothing recorded; trying again is safe |

Retrying any one ticket pays for all the unpaid tickets of that signup, and a different card can be used.

The difference from Flow 6 on an outage is deliberate: a signup has just reserved places worth keeping, so it answers 201; a retry has created nothing new, so it reports the failure.

### Flow 8: list a user's bookings (`GET /events/bookings?userId=`)

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventController.listBookings` → `EventService.listBookingsForUser(userId)` | Read-only transaction |
| 2 | `listBookingsForUser` → `BookingRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)` | Every ticket of the user, newest first |

| Scenario | Result |
|---|---|
| `userId` given | 200. One entry per ticket in every status, including CANCELLED tickets of deleted events |
| No `userId` | 400 |

The `Booking` entity itself is returned, so `orderId`, `paymentBookingId`, `paymentReference` and the refund fields are visible to the client.

### Refunds: three different routes

Money goes back by one of three routes, and it matters which one applies.

| Route | Trigger | Who performs the refund | What events records |
|---|---|---|---|
| **Compensation refund** (Flow 9) | A card was charged for tickets that no longer hold a place | events asks the payment service, over HTTP | `refundRequiredAt`, `refundedAt`, `refundReference` on each ticket |
| **Cancellation refund** (Flow 10) | An event was cancelled | the payment service, on its own, after `event.cancelled` | nothing: the tickets are just CANCELLED |
| **Owed-refund retry** (Flow 11) | A compensation refund that did not go through | the scheduler re-runs the compensation refund | the same fields as Flow 9 |

There is no endpoint on this service for a user to ask for a refund, and `DELETE /events/{id}` refunds nobody.

### Flow 9: compensation refund

Not an endpoint. It starts inside `confirmOrRefund` during Flow 6, Flow 7 or the recovery job, when a payment turned out PAID but `confirm` could not confirm any ticket.

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `BookingPaymentService.confirmOrRefund` → `BookingRefundService.refund(order, paymentId)` | The money was taken for nothing: return it |
| 2 | `refund` → `BookingFinalizerService.recordRefundOwed(ticket, paymentId)`, for every ticket | Write `refund_required_at` and `payment_reference` **before** trying, so the debt survives a crash or an outage |
| 3 | `refund` → `tryRefund(firstTicket, paymentId)` | One payment, so one refund call, whatever the number of tickets |
| 4 | `tryRefund` → `PaymentClient.refund(paymentId)` | `POST /payments/{paymentId}/refund` with the `X-Service-Api-Key` header |
| 5 | `tryRefund` → `settle` → `BookingFinalizerService.recordRefundSettled(ticket, reference)` | Write `refunded_at` and `refund_reference`: the payment service has taken the refund over |
| 6 | `refund` → `recordRefundSettled` for the other tickets of the order | Same payment, same outcome |

`recordRefundOwed` only writes where `refund_required_at IS NULL`, and `recordRefundSettled` only where `refunded_at IS NULL`, so repeating either is harmless. A refund is **owed** while `refundRequiredAt` is set and `refundedAt` is not.

**Scenarios**

| Scenario | Path | Result on the tickets |
|---|---|---|
| Payment service refunds it | 4 → 200 `REFUNDED` → 5, 6 | settled, `refundReference` = `REFUNDED` |
| Stripe has accepted but not finished | 4 → 200 `PENDING` → 5, 6 | settled, `PENDING`. The payment service completes it |
| It was already refunded (e.g. by Flow 10) | 4 → 200 `ALREADY_REFUNDED` → 5, 6 | settled, `ALREADY_REFUNDED` |
| The payment service says it was never paid | 4 → 409 → 5 with `NOT_PAID` | settled, `NOT_PAID`. Nothing to return |
| Stripe kept failing | 4 → 502 (treated as "no answer") | stays owed. Flow 11 later finds the payment `REFUND_FAILED` and settles it; the payment service's admin handles it from its dead-letter queue |
| Payment service down or timed out | 4 throws `PaymentUnavailableException` | stays owed; Flow 11 retries every minute |
| Service key missing or wrong, or wrong base URL | 4 → 401, 403 or 404 | stays owed; an error is logged naming the two settings to check. Needs an operator |

`refund` never throws for a payment-service failure. The request that triggered it still completes normally, returning the tickets with their real status (CANCELLED or EXPIRED).

### Flow 10: cancellation refund

This is Flow 5 seen from the money side. Events does not refund anyone itself.

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventService.cancel` → `deleteLoadedEvent` → `BookingRepository.cancelActiveBookingsForEvent` | Tickets → CANCELLED |
| 2 | `EventService.cancel` → `EventMessaging.publishEventCancelled` → `Outbox.enqueue` | Store `event.cancelled` with the cancellation |
| 3 | `Outbox.relay` → `publish` → RabbitMQ `party.events` | Deliver it, retrying until the broker confirms |
| 4 | (payment service) `EventCancelledListener` → `RefundService.refundEvent` | Cancel its own bookings for the event and refund every paid payment, one by one |
| 5 | (payment service) → `notify.refund` | The participant's refund email |

After step 3, events is finished. It consumes no reply, so a ticket cancelled this way has `refundRequiredAt` and `refundedAt` both null even though the money was returned. To see whether a refund happened, ask the payment service.

**Scenarios**

| Scenario | Result |
|---|---|
| All tickets were paid | Each payment refunded by the payment service; each participant gets a refund email |
| Some tickets were still PENDING (unpaid) | Nothing to refund for those; they are just CANCELLED |
| Free event | `event.cancelled` is still sent; the payment service finds no payments |
| Broker down at cancel time | Refunds start when the outbox delivers the message |
| A refund fails at Stripe | Handled entirely in the payment service: retries, then its dead-letter queue and an admin alert. The event stays cancelled |
| A charge was still in flight when the event was cancelled | Covered twice over. If the payment became PAID before the payment service processed `event.cancelled`, that refunds it and Flow 9 then gets `ALREADY_REFUNDED`. If it became PAID afterwards, Flow 9 refunds it |
| Event deleted with `DELETE` instead | Steps 2 to 5 do not happen. **No refunds** |

### Flow 11: owed-refund retry

Run by the scheduler, for compensation refunds that Flow 9 could not complete.

**Who calls whom**

| # | Caller → callee | What for |
|---|---|---|
| 1 | `EventScheduler.recoverPayments` → `PaymentRecoveryService.recover()` | Every minute |
| 2 | `recover` → `BookingRefundService.retryOwedRefunds()` | Work through the owed refunds |
| 3 | `retryOwedRefunds` → `BookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc` | The tickets with a refund still owed, oldest first |
| 4 | `retryOwedRefunds` → `retry(booking)` → `paymentState` → `PaymentClient.findPayments(paymentBookingId)` | Look the payment up first: what state is it in now? |
| 5 | `retry` → `settle` → `recordRefundSettled` | If the payment service already owns the refund, just record that |
| 6 | `retry` → `tryRefund(booking, paymentId)` | Otherwise ask again, exactly as Flow 9 steps 4 and 5 |

**Scenarios**

| Scenario | Path | Result |
|---|---|---|
| The earlier attempt worked but its answer was lost | 4 finds `REFUNDED` or `REFUND_PENDING` → 5 | settled without a second refund call |
| The payment service gave up on Stripe | 4 finds `REFUND_FAILED` → 5 | settled as `REFUND_FAILED`. It is now the payment service admin's to recover; asking again would only start another round of Stripe attempts and another alert |
| Still simply PAID | 4 → 6 → 200 | refunded and settled |
| Lookup refused (4xx) or payment not listed | 4 gives no state → 6 | falls through to a normal refund attempt |
| Payment service unreachable | 4 or 6 throws | the whole run stops; every owed refund waits for the next minute |
| Still refused with 401, 403 or 404 | 6 | stays owed, error logged, loop moves on to the next ticket. Repeats every minute until fixed |

The loop is per ticket. For three tickets paid by one payment, the first ticket's retry performs the refund and the other two then find the payment already `REFUNDED` at step 4.

## Scheduled jobs

`@EnableScheduling` on `EventsApplication` switches scheduling on. [`EventScheduler`](events/src/main/java/events/controller/EventScheduler.java) holds three `@Scheduled` methods; each is a one-line call into a service.

| Job | Property | Default schedule | Calls |
|---|---|---|---|
| Expired events | `app.scheduling.event-check-cron` | `0 0 0 * * *`: daily at midnight | `EventService.processExpiredEvents()` |
| Outbox relay | `events.outbox.relay-cron` | `*/10 * * * * *`: every 10 seconds | `Outbox.relay()` |
| Payment recovery | `events.payment.recovery-cron` | `30 * * * * *`: every minute, at second 30 | `PaymentRecoveryService.recover()` |

Cron format is Spring's six fields: second, minute, hour, day of month, month, day of week. Times are in the JVM's time zone, which is UTC in the container.

No scheduler pool size is configured, so Spring Boot's default applies: one scheduler thread. The three jobs therefore never run at the same moment as each other, and a slow run delays the next job rather than overlapping it. They do run alongside HTTP requests, which is why every step below is written to be safe against a concurrent signup, retry or cancel.

Each job exists to finish something a request could not finish:

| Job | The gap it closes |
|---|---|
| Expired events | Nobody calls an endpoint when an event misses its minimum signups; something has to notice |
| Outbox relay | The broker was down, or nothing was listening, when an event was cancelled |
| Payment recovery | The payment service answered too late, or not at all, during a payment or a refund |

### Job 1: expired events

An event can have a `minSignUps` and an `expiresAt`: "if fewer than N people have signed up by this time, call it off".

```
EventScheduler.checkExpiredEvents()                       daily at 00:00
  → EventService.processExpiredEvents()                   @Transactional (one transaction for the whole run)
      now = current UTC time
      expired = EventRepository.findByExpiresAtLessThanEqualAndStatus(now, "ACTIVE")
      for each event:
          currentSignUps = event.maxCapacity - event.capacity
          if event.minSignUps != null and currentSignUps < event.minSignUps:
              cancel(event.id)                             the same method as POST /events/{id}/cancel
```

What `cancel` then does for each such event (details in Flow 5 of the request flows):

1. Locks the event row.
2. Sets every PENDING and CONFIRMED ticket of the event to CANCELLED.
3. Deletes the event row.
4. Queues `notify.event_deleted` for each participant and the organiser.
5. Stores an `event.cancelled` message in the outbox, so the payment service refunds everyone who paid.

When the transaction commits, the notifications are sent and the outbox publishes `event.cancelled`.

Points to be aware of:

- **`currentSignUps` counts places taken, not places paid for.** `capacity` is reduced when a ticket is reserved, so a PENDING ticket whose payment was declined still counts towards the minimum.
- **Events that reach their minimum are left alone.** Nothing marks them as "confirmed". They stay ACTIVE, still match the query, and are checked again every night. This is harmless, since signups can only go up, unless a `PATCH` raises `capacity` or `minSignUps`.
- **Events with no `minSignUps` are never cancelled by this job**, whatever their `expiresAt`.
- **A cancelled event is not found again**, because its row is deleted.
- **The whole run is one transaction.** `cancel` is called on the same object, so it joins the surrounding transaction. If cancelling one event fails, every cancellation of that run is rolled back, no message is sent, and the job tries again the next night.
- **The check is daily.** An event whose `expiresAt` is 09:00 is cancelled at the following midnight, up to 24 hours late. Signups stay open until then.

### Job 2: outbox relay

Covered in full under "The outbox" below. In short: every 10 seconds, publish any `event.cancelled` message that is still stored because the broker has not confirmed it.

### Job 3: payment recovery

```
EventScheduler.recoverPayments()                          every minute, at second 30
  → PaymentRecoveryService.recover()
      1. BookingRefundService.retryOwedRefunds()
      2. reconcileUnconfirmedPayments()
```

Neither part holds a transaction across a call to the payment service. Each database change is one of the short conditional updates in `BookingFinalizerService`.

#### Part 1: retry owed refunds

A refund is **owed** when a card was charged for tickets that no longer hold a place. `BookingRefundService.refund` records that on the booking *before* trying the refund (`refund_required_at` set, `payment_reference` = the payment to refund). If the refund then did not go through, this job picks it up.

```
BookingRefundService.retryOwedRefunds()
  owed = BookingRepository.findByRefundRequiredAtIsNotNullAndRefundedAtIsNullOrderByIdAsc()
  for each booking, oldest first:
      paymentId = booking.paymentReference

      a. Ask what state the payment is in now
         PaymentClient.findPayments(booking.paymentBookingId)      GET /payments/booking/{paymentBookingId}
           no answer            → stop the whole run; the rest wait for the next minute
           4xx, or not found    → state unknown, go to b
           found                → state = that payment's status

         state is REFUNDED, REFUND_PENDING or REFUND_FAILED
           → recordRefundSettled(booking, state)                   the payment service owns the refund now
           → next booking

      b. Otherwise ask for the refund again
         PaymentClient.refund(paymentId)                           POST /payments/{paymentId}/refund + X-Service-Api-Key
           200          → recordRefundSettled(booking, outcome)    REFUNDED, ALREADY_REFUNDED, PENDING or SKIPPED
           409          → recordRefundSettled(booking, "NOT_PAID") the payment was never paid: nothing to refund
           other 4xx    → error logged; stays owed; next booking   (401/403: service key; 404: wrong base URL)
           no answer    → stays owed; stop the whole run
```

Why it looks the payment up first (step a) instead of simply asking again:

- **The earlier attempt may have worked with its answer lost.** The payment is then already `REFUNDED` or `REFUND_PENDING`.
- **The payment service may have given up on Stripe.** It then marks the payment `REFUND_FAILED`, moves it to its own refund dead-letter queue and alerts the admin. Asking again would start another round of Stripe attempts and another alert.

In all three states the refund is the payment service's responsibility, so this service marks it settled and stops asking. "Settled" here means "handed over", not necessarily "money returned": `refund_reference` records which it was.

The loop is per booking, not per order. For an order of three tickets paid by one payment, the first ticket's retry does the refund, and the other two then find the payment already `REFUNDED` in step a.

A refund refused with 401, 403 or 404 stays owed and is retried every minute, logging an error each time, until an operator fixes the key or URL.

#### Part 2: confirm payments whose answer was lost

The case: `POST /payments` timed out, so the request gave up with the tickets still PENDING, but Stripe did charge the card. Without this job the user would have paid and never been confirmed unless they happened to retry.

```
PaymentRecoveryService.reconcileUnconfirmedPayments()
  now = current UTC time
  candidates = BookingRepository.findUnconfirmedPayments(
                   createdBefore = now - 1 minute,          events.payment.reconcile-after
                   expiredAfter  = now - 1 day)             RECONCILE_WINDOW

  for each candidate, oldest first:
      skip it if another ticket of the same orderId was already checked in this run
      order = BookingPaymentService.unpaidOrderOf(booking)          this ticket + the order's other PENDING tickets
      confirmed = BookingPaymentService.confirmIfPaid(order)
          PaymentClient.findPayments(paymentBookingId)              GET /payments/booking/{paymentBookingId}
            no answer              → stop the whole run
            no PAID payment        → false: nothing to do yet
            a PAID payment         → confirmOrRefund(order, thatPayment)
      if confirmed:
          EventMessaging.publishSignupConfirmation(order)           the "You're in!" email, late but sent
```

Which bookings are candidates, and why each condition is there:

| Condition | Reason |
|---|---|
| `status = PENDING` | CONFIRMED tickets need nothing; CANCELLED and EXPIRED ones are not confirmed by this query |
| `paymentBookingId IS NOT NULL` | Without a payment-side booking, `POST /payments` was never reached, so nothing can have been charged |
| `createdAt <= now - 1 minute` | A younger booking's own request may still be waiting for the payment service's answer |
| `expiresAt > now - 1 day` | Stop asking about a booking a day after its payment window closed |

`confirmOrRefund` is the same method a normal signup uses (see the request flows), so recovery gets the same guarantees: the whole order is confirmed or none of it, and if the tickets were cancelled in the meantime the payment is refunded instead.

A ticket whose 15-minute deadline has passed is usually still PENDING in the database (see the note on lazy expiry under "Things worth knowing"), so a late-discovered payment still confirms it.

A booking with no PAID payment is simply asked about again every minute until the one-day window closes.

#### A worked example

```
12:00:00  POST /events/9/signup, 1 ticket. reserve() saves booking 101 (PENDING, expiresAt 12:15).
12:00:01  pay(): POST /bookings → 55; linkPaymentBooking. POST /payments ...
12:00:11  ... read timeout after 10 s. Stripe did charge the card (payment 900, PAID).
          PaymentUnavailableException → signUp answers 201 with booking 101 still PENDING.
12:00:30  recovery run: booking 101 is younger than 1 minute → not a candidate.
12:01:30  recovery run: candidate. GET /payments/booking/55 → payment 900 is PAID.
          confirmOrRefund → booking 101 CONFIRMED, paymentReference "900". notify.signup sent.
```

Had the organiser cancelled event 9 at 12:01:00, the run at 12:01:30 would not see booking 101 at all (it is CANCELLED, not PENDING). The money is still returned, by the other route: `event.cancelled` tells the payment service to refund every paid payment of the event.

## The outbox

### The problem it solves

Cancelling an event does two things that must both happen or both not happen:

1. **Database:** delete the event and cancel its tickets.
2. **Broker:** publish `event.cancelled`, which is the only thing that makes the payment service refund people.

A database and a message broker cannot share one transaction. Doing them one after the other leaves a gap either way:

| Order | What goes wrong |
|---|---|
| publish, then commit | the commit fails → refunds go out for an event that still exists |
| commit, then publish | the broker is down, or the process dies in between → the event is gone and nobody is ever refunded |

The outbox closes the gap by turning step 2 into a database write. The message is saved as a row **in the same transaction** as the cancellation, so the two commit or roll back together. Publishing happens afterwards and can be retried for as long as it takes.

Only `event.cancelled` goes through the outbox. Notification emails are sent best effort: if the broker is down, a warning is logged and the email is lost.

### The pieces

| Piece | Role |
|---|---|
| [`Outbox`](events/src/main/java/events/messaging/Outbox.java) | `enqueue()` stores a message; `relay()` publishes stored messages |
| [`OutboxMessage`](events/src/main/java/events/entity/OutboxMessage.java) / table `outbox_messages` | one row per message not yet confirmed: `id`, `exchange`, `routingKey`, `payload` (the JSON body as text), `createdAt`, `attempts`, `lastError` |
| [`OutboxMessageRepository`](events/src/main/java/events/repository/OutboxMessageRepository.java) | `findTop100ByOrderByIdAsc()`, `recordFailedAttempt(id, error)` |
| `EventMessaging.publishEventCancelled` | the only caller of `enqueue` |
| `EventScheduler.relayOutbox` | calls `relay()` every 10 seconds |

A row existing means "not delivered yet". A delivered message has no row.

Two settings in `application.properties` make delivery checkable:

- `spring.rabbitmq.publisher-confirm-type=correlated`: the broker sends an acknowledgement for each published message.
- `spring.rabbitmq.publisher-returns=true`: the broker hands back a message it could not route to any queue.

`Outbox` builds its own `RabbitTemplate` with `mandatory = true`, so only outbox messages are sent as "must be routable". The shared template used for notifications is left non-mandatory.

### Step 1: `enqueue`, inside the cancelling transaction

```
EventService.cancel(id)                                   @Transactional
  → deleteLoadedEvent(...)                                tickets CANCELLED, event row deleted
  → EventMessaging.publishEventCancelled(event)
      → Outbox.enqueue("party.events", "event.cancelled",
                       EventCancelledMessage(eventId, "cancel-<eventId>", timestamp, reason))
            @Transactional(propagation = MANDATORY)       throws if there is no transaction to join
            body = message converted to JSON
            OutboxMessageRepository.save(new OutboxMessage(exchange, routingKey, body, now))
            register an afterCommit callback → relay()
← commit
```

At commit, three things become true at once: the event is gone, its tickets are CANCELLED, and the outbox row exists. If anything throws before the commit, all three are undone and the callback never runs.

`MANDATORY` is a guard: calling `enqueue` outside a transaction would save a row that is not tied to any business change, so it is refused.

### Step 2: `relay`, after the commit and every 10 seconds

```
Outbox.relay()                                            synchronized
  pending = OutboxMessageRepository.findTop100ByOrderByIdAsc()      oldest first, in a new transaction
  for each message:
      failure = publish(message)
      failure is null (delivered):
          delete the row                                  new transaction
          log "delivered after N failed attempt(s)"
      otherwise:
          recordFailedAttempt(id, failure)                attempts + 1, lastError = why; new transaction
          log a warning
          return                                          stop: this one and all later ones wait for the next run
```

`relay` has two callers: the `afterCommit` callback, so a healthy system publishes within milliseconds of the cancel, and the scheduler, which is the retry.

Design choices in this method:

- **`synchronized`:** the scheduler thread and a request thread never publish the same row at the same time (within one instance).
- **Stop at the first failure:** a failure almost always means the broker is down or nothing is bound, so the rest would fail too. Stopping also keeps messages in order.
- **Each database step in its own new transaction (`REQUIRES_NEW`):** when `relay` runs from `afterCommit`, the finished transaction is still attached to the thread. Without a fresh transaction, the delete would join that finished one and never be committed.
- **At most 100 per run:** a backlog drains at up to 100 messages every 10 seconds.

### Step 3: `publish`, deciding whether one message really arrived

```
publish(stored)
  build the AMQP message: body = stored.payload, content type JSON,
                          delivery mode PERSISTENT, messageId = "outbox-<row id>"
  rabbitTemplate.send(exchange, routingKey, message, CorrelationData("outbox-<row id>"))
  wait for the broker's confirm, up to events.outbox.confirm-timeout (5 s)
```

| What happens | Meaning | Result |
|---|---|---|
| confirm = ack, nothing returned | the broker took it and put it in a queue | **delivered**: the row is deleted |
| confirm = nack | the broker refused it | failed: "the broker refused it" |
| confirm = ack, but the message was returned | the exchange exists but no queue is bound to `event.cancelled` | failed: "unroutable, no queue is bound for it" |
| no confirm within 5 s | broker slow or connection lost | failed: "no confirm from the broker" |
| `AmqpException` on send | broker unreachable | failed: the exception text |

The "returned" row matters more than it looks. A topic exchange silently discards a message that matches no binding. If events cancels an event before the payment service has ever started (and so before the queue `payments.refunds.requested` exists), a plain publish would be acknowledged and thrown away. Because the message is mandatory, it comes back, counts as a failure, and stays in the outbox until the payment service is up.

`PERSISTENT` means the broker writes the message to disk, so it also survives a broker restart once it is in the queue.

### What happens in each failure case

| Situation | Outcome |
|---|---|
| Everything healthy | Row saved with the cancel, published right after commit, deleted. The table is empty again within milliseconds |
| The cancel transaction rolls back | No row, no message, the event still exists |
| Broker down when the event is cancelled | Row stays with `attempts` and `lastError` rising. Every 10 seconds `relay` tries again. Delivered when the broker returns |
| Payment service never started (no queue bound) | Same: the message is returned as unroutable and kept until the queue exists |
| Events crashes after the commit, before publishing | The row is in the database. The first scheduled `relay` after restart publishes it |
| Events crashes after the broker confirmed, before the row was deleted | The row is still there, so the message is published **a second time** after restart |
| Two instances of events relay at once | `synchronized` only covers one JVM, so both may publish the same row |

The last two rows are why delivery is **at least once**, not exactly once. That is acceptable because the consumer is idempotent: the payment service treats a repeated `event.cancelled` as a no-op (already refunded payments are skipped, and Stripe idempotency keys stop a second refund).

### Checking on it

`outbox_messages` should normally be empty. A row that stays means a cancellation whose refunds have not started:

```sql
SELECT id, routing_key, created_at, attempts, last_error FROM outbox_messages ORDER BY id;
```

`last_error` says why: broker unreachable, no confirm, or no queue bound. Each failed attempt also logs a warning starting "Outbox message … not delivered". Nothing alerts on this and there is no attempt limit: a message is retried forever.

## Data model

Three tables, generated by Hibernate from the entities.

**`events`** ([`Event`](events/src/main/java/events/entity/Event.java))

| Field | Notes |
|---|---|
| `id` | Identity column |
| `title`, `description`, `location`, `organiserId` | |
| `startDateTime`, `endDateTime` | UTC; end must be after start |
| `capacity` | **Places left**, decremented on signup |
| `maxCapacity` | Initial capacity. Signups so far = `maxCapacity - capacity` |
| `minSignUps`, `expiresAt` | Auto-cancel rule |
| `ticketPrice`, `currency` | SGD only. Null or 0 price = free event |
| `status` | `ACTIVE` allows signups; any other value blocks them |

**`bookings`** ([`Booking`](events/src/main/java/events/entity/Booking.java)): one row per ticket.

| Field | Notes |
|---|---|
| `userId`, `eventId` | Plain ids, no foreign keys |
| `orderId` | UUID shared by every ticket of one signup; they are paid by one charge |
| `totalAmount`, `currency` | Copied from the event at signup time |
| `status` | See below |
| `createdAt`, `expiresAt`, `confirmedAt`, `failedAt` | `expiresAt` = payment deadline (created + 15 min) |
| `paymentBookingId` | The matching booking in the payment service |
| `paymentReference` | Id of the successful payment in the payment service |
| `failureReason` | Why the last payment attempt failed |
| `refundRequiredAt`, `refundedAt`, `refundReference` | Compensation-refund bookkeeping |

Booking status:

```
PENDING ──payment ok──► CONFIRMED ──event deleted/cancelled──► CANCELLED
   │                                                              ▲
   ├──event deleted/cancelled─────────────────────────────────────┘
   └──deadline passed (see note below)──► EXPIRED
```

A failed payment keeps the booking PENDING so the user can retry without losing the place.

**`outbox_messages`** ([`OutboxMessage`](events/src/main/java/events/entity/OutboxMessage.java)): exchange, routing key, JSON payload, attempts, last error.

## Messages published

Notification payloads use snake_case, amounts in cents, and name the recipient by `user_id` (the notifications service looks up the email). Each carries a `notification_id` so a redelivery never sends a second email.

| Exchange / key | When | `notification_id` |
|---|---|---|
| `notifications` / `notify.signup` | An order becomes CONFIRMED | `signup-booking-<first booking id>` |
| `notifications` / `notify.event_created` | Event created (to the organiser) | `event-created-<event id>` |
| `notifications` / `notify.event_updated` | Participant-facing details changed (one per participant, one for the organiser) | `event-updated-<id>-<epoch>-user-<userId>` / `-organiser` |
| `notifications` / `notify.event_deleted` | Event deleted or cancelled (same audience) | `event-deleted-<id>-user-<userId>` / `-organiser` |
| `party.events` / `event.cancelled` | Event cancelled (through the outbox) | body: `{eventId, requestId: "cancel-<id>", timestamp, reason}` |

## Errors

| Exception | HTTP |
|---|---|
| `EventNotFoundException`, `BookingNotFoundException` | 404 |
| `EventNotOpenException`, `EventFullException`, `DuplicateBookingException`, `BookingNotPayableException` | 409 |
| `InvalidRequestException` | 400 |
| `PaymentUnavailableException` (payment retry only) | 503 |
| `PessimisticLockingFailureException` (row lock timeout or deadlock) | 503 |
| Any other `DataAccessException` | 503 |

## Configuration

Set in [`application.properties`](events/src/main/resources/application.properties); secrets come from the environment or a git-ignored `.env` / `dev.env`.

| Property / variable | Purpose |
|---|---|
| `AWS_REGION`, `COGNITO_USER_POOL_ID` | Token issuer. Without a pool id every token is rejected |
| `SPRING_DATASOURCE_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL (`events_db`) |
| `SPRING_RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ |
| `EVENTS_PAYMENT_BASE_URL` | Payment service URL (default `http://localhost:8089`, the WireMock stub) |
| `PAYMENT_SERVICE_API_KEY` | Sent as `X-Service-Api-Key` on refunds. Without it every compensation refund is refused (401) and stays owed |
| `events.payment.connect-timeout` / `read-timeout` | 2 s / 10 s |

The service's own [`compose.yaml`](events/compose.yaml) starts PostgreSQL, RabbitMQ, the service and a WireMock stand-in for the payment service.

## Open consideration: one payment call instead of two

Not implemented. This section records what switching would involve, so the decision can be made with the facts in one place.

### Today: two calls

Charging an order takes two calls to the payment service (Flow 6, steps 3c to 3e):

| Call | Creates | When |
|---|---|---|
| `POST /bookings` | The payment-side booking: what is owed (user, event, quantity, total) | First attempt only |
| `POST /payments` | One payment: one charge attempt on that booking | Every attempt |

`POST /payments` cannot be used alone. Its body is only `bookingId` and `paymentMethodId`, and the payment service takes the amount from its booking row, never from the caller.

Between the two calls this service saves `paymentBookingId` on every ticket (`linkPaymentBooking`). Three things depend on that saved id:

- **Timeout recovery.** Job 3, part 2 reads `GET /payments/booking/{paymentBookingId}` to find out whether a lost answer was in fact a charge.
- **Payment retry.** Flow 7 calls only `POST /payments`, on the same payment-side booking.
- **Double-charge protection.** A second `POST /payments` on a booking that already has a PENDING or PAID payment gets a 409, and `reconcile` then looks for the PAID one.

### The alternative: `POST /payments/checkout`

The payment service already has a one-call endpoint ([`PaymentService.checkout`](payment/src/main/java/payment/service/PaymentService.java)). It creates or reuses the payment-side booking and charges it, and it is idempotent per `eventBookingId`, an id chosen by the caller.

| Repeat of the same `eventBookingId` | Answer |
|---|---|
| A PENDING or PAID payment exists | 200 with that payment. Stripe is not called again |
| The last payment FAILED | 201 with a new attempt on the same booking |
| Different event, user, quantity or total | 409 |

The request would be built from what `pay` already has:

| Checkout field | Value from this service |
|---|---|
| `eventBookingId` | `orderId` (a UUID, 36 characters; the limit is 64). A booking without an `orderId` needs a fallback, such as its own id |
| `eventId`, `userId` | from the first ticket |
| `quantity` | `order.size()` (checkout allows 1 to 20; a signup allows 1 to 10) |
| `ticketPrice` | one ticket's `totalAmount`. The payment service computes `ticketPrice × quantity` itself; the total is no longer sent |
| `paymentMethodId`, `eventTitle` | as today |

### What gets simpler

- **One network call per attempt**, and one fewer place for a first attempt to fail.
- **No payment-side booking without a payment.** Today a failure between the two calls leaves one behind.
- **No `linkPaymentBooking` write before charging.**
- **No `reconcile`.** "An earlier attempt already paid" comes back as a normal 200 with the PAID payment instead of a 409 followed by a lookup.
- **First attempt and retry become the same call.** Flow 7 would repeat the checkout with the same `orderId`.

### What has to be solved first: the lost answer

This is the one real obstacle. If the checkout call times out, this service has no payment-side id at all, because the id only arrives in the response. Job 3, part 2 then has nothing to look up.

Repeating the checkout from the recovery job is **not** a safe way to ask. The job has no card (`paymentMethodId` is stored nowhere), and a checkout without one creates a PENDING payment if the first request never arrived. That PENDING payment would then be returned to every later attempt, so the user's own retry would never charge.

The job needs a read-only lookup by `orderId`. Two ways to get one:

| Option | Change needed | Cost |
|---|---|---|
| `GET /payments/user/{userId}`, filtered by `eventBookingId` | None in the payment service: the endpoint exists and each payment carries `eventBookingId` | Fetches all of a user's payments to find one |
| A new lookup by `eventBookingId` in the payment service | A small new endpoint there | A change in a second service |

The candidate query changes too. `paymentBookingId IS NOT NULL` currently means "a charge may have been attempted". With one call it would be null after a timeout, so the query needs another marker, for example a `payment_attempted_at` column set just before the call.

### Other changes in this service

| Where | Change |
|---|---|
| [`PaymentClient`](events/src/main/java/events/client/PaymentClient.java) | Replace `createBooking` and `createPayment` with one `checkout` method |
| [`BookingPaymentService.pay`](events/src/main/java/events/service/BookingPaymentService.java) | One call; drop `reconcile`; a 409 now means mismatched details and is recorded as a failure |
| `BookingPaymentService.confirmIfPaid` | Use the new lookup instead of `findPayments(paymentBookingId)` |
| `paymentBookingId` column | Can still be filled from the checkout response (`bookingId`), but only after an answer arrives. [`BookingRefundService`](events/src/main/java/events/service/BookingRefundService.java) already copes with it being null |
| WireMock stand-in and tests | Stub `POST /payments/checkout` instead of the two calls |

Not affected: refunds (`POST /payments/{paymentId}/refund`), `event.cancelled`, and the payment service's own code, unless the new lookup endpoint is chosen.

### Summary

| | Two calls (today) | One call (checkout) |
|---|---|---|
| Calls per first attempt | 2 | 1 |
| Double-charge protection | 409 then `reconcile` | Idempotent per `orderId` |
| Id known before charging | `paymentBookingId` (from the payment service) | `orderId` (our own) |
| Lookup after a timeout | `GET /payments/booking/{id}`, exists | By `orderId`: must be added or done through the user's payments |
| Work needed | none | The changes above |

## Things worth knowing

- **`spring.jpa.hibernate.ddl-auto=create`** in `application.properties`. Hibernate drops and recreates the tables on every start, so all events and bookings are lost on restart unless this is overridden in the deployment.
- **Unpaid bookings are only expired lazily.** A PENDING booking past its 15-minute deadline is set to EXPIRED (and its place returned) only when *the same user* signs up for that event again, inside `BookingReservationService.reserve`. No scheduled job expires them, so an abandoned unpaid booking keeps holding a place.
- **`userId` is a request parameter, not taken from the token.** The service checks the caller's role but not that the `userId` matches the caller. It is also an `Integer`, while the authentication service's ids are UUIDs; the notifications service bridges the two with a derived `service_id`.
- **Events that reach their minimum are never marked as confirmed.** `processExpiredEvents` only cancels the ones that fall short; the rest stay ACTIVE and are checked again on every run.
- **`application.properties` mentions a `PaymentRefundedListener`.** That class does not exist. The service consumes no RabbitMQ messages, so the `listener.simple.*` settings currently do nothing.
- **`DELETE /events/{id}` returns 204**, so the `EventDto` the controller method returns is not sent to the client.
