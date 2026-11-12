# party.fun Frontend

The web frontend for **party.fun**, an event discovery and ticketing platform for university events. Participants
browse events, buy tickets at a fixed price and help "hype" events into happening. Organisers can do all of that,
and also create and manage their own events.

Built with React 19, Vite, TypeScript and React Router. Styling is plain CSS (`src/styles.css`), with light and
dark themes and a layout that works on desktop, tablet and mobile.

> **Backend status:** login and registration use **Amazon Cognito**; events, signups and payment retries go to the
> **Event Service** through the **API Gateway**, with the user's Cognito access token (see
> [Backend integration](#backend-integration)). Payments are real, in a Stripe **sandbox** (test cards, no real
> money): the Event Service charges the card through the Payment Service. See [Payments](#payments).
>
> Deployed as static files in **S3** behind **CloudFront**; every API call goes to the API Gateway, which validates
> the Cognito JWT before forwarding it to the services on ECS.

## Running the frontend

There are two ways to open the site. Pick one.

| | Option A: development server | Option B: Docker |
|---|---|---|
| Open in the browser | **http://localhost:5173** | **http://localhost:8001** |
| Run from | `user-interfaces/frontend` | `user-interfaces/frontend` (its [`compose.yaml`](compose.yaml)) |
| Needs | Node.js 20.19+ or 22.12+ | Docker Desktop running |
| Code changes | show up instantly (hot reload) | need a rebuild |

### Option A: development server (http://localhost:5173)

```bash
cd user-interfaces/frontend
npm install          # first time only, and after package.json changes
npm run dev          # then open http://localhost:5173
```

Leave the terminal open while you use the site; stop it with **Ctrl+C**.

### Option B: Docker (http://localhost:8001) (recommended)

```bash
cd user-interfaces/frontend
cp .env.example .env             # first time only, then fill it in (see Configuration)
docker compose up -d --build     # then open http://localhost:8001
```

- The first build takes a few minutes (`npm ci` inside Docker); later builds are faster.
- After changing frontend code, run the same command again to rebuild.
- See its logs with `docker compose logs -f frontend`; stop it with `docker compose down`.
- Only the site runs in this container. The services run from their own folders, and nginx reaches them through the
  ports they publish on this machine: Payment on 8080 (`services/payment`), Events on 8083 and Authentication on
  8082 (change these with `PAYMENT_UPSTREAM`, `EVENTS_UPSTREAM` and `AUTH_UPSTREAM` in `.env`).

### Then

1. Set the [configuration](#configuration) in `.env` (copy `.env.example`); `npm run dev` and Docker both read it.
2. Open the URL above and log in with a Cognito account in the `participant`, `organiser` or `admin` group (see
   [Accounts](#accounts)).
3. Every page with events or bookings needs the API Gateway and the Event Service. **Paying for tickets** also needs
   the Payment Service (it hands out the Stripe publishable key), and the **Admin dashboard** calls the Payment
   Service's `/admin/*` through the API Gateway. See [Payments → What you need running](#what-you-need-running).

| Problem | Fix |
|---|---|
| The page doesn't load | Check the right port: 5173 for `npm run dev`, 8001 for Docker. For Docker, check `docker compose ps frontend` |
| "Port 5173 is in use" | Another `npm run dev` is already running: use it, or stop it with Ctrl+C |
| You still see the old UI after a change (Docker) | Rebuild: `docker compose up -d --build frontend` |
| Checkout says "Can't reach the Payment Service" | Start it: `docker compose up -d --build` in `services/payment` |
| "The API is not configured" / "Login is not configured" | Set `VITE_API_BASE_URL` / `VITE_COGNITO_REGION` and `VITE_COGNITO_CLIENT_ID`, then restart `npm run dev` (or rebuild) |
| "Your account isn't allowed to do that." (403) | The Cognito user is in none of the groups that endpoint allows (`participant`, `organiser`, `admin`) |

## Accounts

Accounts live in the team's **Cognito user pool**. The app logs in with `USER_PASSWORD_AUTH` straight from the
browser (no AWS SDK), keeps the tokens in `localStorage` (`partyfun.session.v2`), refreshes the access token before
it expires, and sends it as `Authorization: Bearer …` on every Event Service call. Logout revokes the refresh token.

The user's role comes from the ID token's **`cognito:groups`** claim, the same claim the Event Service reads:

| Cognito group | Role in the app |
|---|---|
| `participant` | Participant |
| `organiser` (British spelling; `organizer` gets no access) | Organiser |
| `admin` | Admin |

On the (participant/organiser) Login page, choose the matching role with the **Participant | Organiser** toggle. The
account must be in that group:

| Toggle | Account's groups | Result |
|---|---|---|
| Participant | `participant` | ✅ logged in |
| Organiser | `organiser` | ✅ logged in |
| Participant | `organiser` only | ❌ "This account is registered as an organiser." |
| Organiser | `participant` only | ❌ "This account is registered as a participant." |
| any | `admin` only | ❌ "Admins log in on the Admin login page." |
| any | none of them | ❌ "This account isn't a participant or organiser yet …" |
| any | wrong password | ❌ Cognito's message, e.g. "Incorrect username or password." |

Admins use their own page: on the Login page click **Log in as an admin**, or open `/admin/login`. Only accounts in
the `admin` group are accepted there; it lands on the **Failed refunds** dashboard (see
[Admin dashboard](#admin-dashboard-failed-refunds)). Nobody can register as an Admin.

**Register** (full name, username, email, password, role) creates the account in Cognito (`SignUp`), which emails a
confirmation code; the page then asks for the code (`ConfirmSignUp`). **Email verification is a switch** on the
Authentication Service (`EMAIL_VERIFICATION_ENABLED`, **off for now**), read from `GET /auth/config`: while it's off,
the page skips the code and asks that service to confirm the account (`POST /auth/confirm-sign-up`); if the service
can't be asked, the code is required. Once confirmed, the page signs in once behind the
scenes, calls the **Authentication Service**'s `POST /user/me` with the full name and the chosen role, and signs out
again: that service creates the profile and adds the account to the `participant` or `organiser` group (the browser
can't choose a group itself, and `ADMIN` is never accepted). The user is then sent to **Login** to log in; their tokens
now carry the group. If the profile step fails (e.g. the service is down), the account is still confirmed and the first
login on the Login page finishes it, with the role on the toggle.

## Roles

| Role | Can do |
|---|---|
| **Participant** | Browse events, view event details, buy a ticket (Stripe), view bookings, retry a declined payment, view profile |
| **Organiser** | Everything a participant can do, including buying tickets to other organisers' events, plus create, view, edit and cancel **their own** events |
| **Admin** | Process the failed refunds in the Payment Service's refund dead-letter queue (retry them) |

Participants and organisers land on **Browse Events** after logging in, and clicking **party.fun** in the navbar
always goes back there. The Admin lands on **Failed refunds**. Participants and organisers who open an admin page are
sent back to Browse Events; a logged-out visitor is sent to the Admin login.

| Navbar | Links |
|---|---|
| Participant | party.fun · Browse Events · My Bookings · Profile |
| Organiser | party.fun · Browse Events · My Bookings · My Organised Events · + Create Event · Profile |
| Admin | party.fun · Failed Refunds · Profile |

## Views and routes (12, plus 2 Admin pages)

| # | View | Route | Who |
|---|---|---|---|
| 1 | Login | `/login` | everyone |
| 2 | Register | `/register` | everyone |
| 3 | Browse Events (home) | `/events` | both roles |
| 4 | Event Details | `/events/:eventId` | both roles |
| 5 | Checkout | `/events/:eventId/checkout` | both roles |
| 6 | My Bookings | `/bookings` | both roles |
| 7 | Booking Details (incl. retrying a declined payment) | `/bookings/:bookingId` | both roles |
| 8 | Profile (incl. Logout) | `/profile` | both roles |
| 9 | My Organised Events | `/organiser/events` | organiser |
| 10 | Create Event | `/organiser/events/create` | organiser |
| 11 | Organised Event Details / Manage Event (incl. Cancel Event) | `/organiser/events/:eventId` | organiser |
| 12 | Edit Event | `/organiser/events/:eventId/edit` | organiser |
| – | Admin login | `/admin/login` | everyone |
| – | Failed refunds (Admin dashboard: the refund dead-letter queue) | `/admin/refunds` | admin |

There are no separate success/failure pages. Payment results and cancelling events are shown with inline messages
and confirmation dialogs on these views.

## Business rules shown in the UI

- **Event statuses:** only `PENDING`, `CONFIRMED` and `CANCELLED`, derived from the Event Service's `status` field
  ([`src/api/eventMapping.ts`](src/api/eventMapping.ts)):

  | Event Service `status` | Shown as | Signups |
  |---|---|---|
  | `ACTIVE` (the default) | **PENDING**, however many signups it has | open |
  | `CONFIRMED` (only if the service sets it) | **CONFIRMED** | closed (the service only takes signups for `ACTIVE`) |
  | anything else | **CANCELLED** | closed |
  | the event no longer exists (cancelling deletes it) | **CANCELLED**, on the bookings that were made for it | – |

  Reaching the minimum does **not** confirm an event in the frontend: an `ACTIVE` event stays Pending.
- **Hype meter:** signups ÷ minimum signups (`minSignUps`) × 100, capped at 100%, where signups = `maxCapacity −
  capacity` (paid bookings plus places held while a payment is pending). An event below its minimum at the hype
  deadline (`expiresAt`) is cancelled by the Event Service's scheduled job, and paid bookings are refunded. The hype
  deadline is always before the event's date and time.
- **Several tickets per signup, at a fixed price:** Checkout lets the user pick 1 to 10 tickets (fewer if fewer spots
  are left). The Event Service takes every place or none (409 if not enough are left) and makes one booking per
  ticket, each costing the event's ticket price and charged separately. There is no dynamic, early-bird or curve pricing.
- **One signup per event:** while a user's tickets hold a place (paid, or waiting for payment), Event Details shows
  them with a link instead of **Buy Tickets**. The Event Service refuses a second signup (409). My Bookings shows each
  ticket of a multi-ticket signup as its own card ("Ticket 2 of 3").
- **Declined card:** the booking stays **Awaiting payment** with its place held for 15 minutes; the user can pay with
  another card on Checkout (which retries every unpaid ticket of the signup) or Booking Details (one ticket). After 15 minutes it shows as **Expired** and the place is released.
- **Refunds:** only when an event is **cancelled** (by its organiser, or by the Event Service because it missed its
  minimum). The Payment Service refunds every paid booking through Stripe.

## Payments

Payments go through the **Event Service** (`services/events`), which charges the card through the **Payment
Service** (`services/payment`) and **Stripe**, in a Stripe **sandbox**: you type test card numbers, Stripe processes
them for real, and no real money ever moves. There is **no in-app wallet**: no balance, top-ups, credits or
withdrawals.

### What you need running

1. **The Event Service** behind the API Gateway (`VITE_API_BASE_URL`), with its Cognito issuer
   (`AWS_REGION`, `COGNITO_USER_POOL_ID`) and `EVENTS_PAYMENT_BASE_URL` pointing at the Payment Service.
2. **The Payment Service** (`VITE_PAYMENT_API_URL`, default `http://localhost:8080`), with these set in
   `services/payment/dev.env`: `stripe_secret_key` (`sk_test_…`), `payment_admin_api_key`, `payment_service_api_key`, and
   **`stripe_publishable_key`** (`pk_test_…`: Stripe Dashboard → your sandbox → Developers → API keys →
   Publishable key). The browser reads the publishable key (`GET /payments/config`) and a payment's live status
   (`GET /payments/{id}`) from it. From `services/payment`: `docker compose up -d --build`.
3. **This frontend**: `npm run dev` (http://localhost:5173) or `docker compose up -d --build` in this folder
   (http://localhost:8001). Both origins must be in the Payment Service's `cors_allowed_origins`.
3. *Optional:* **the Stripe webhook forwarder** (`stripe listen`, with `stripe_webhook_secret` in `services/payment/dev.env`). Checkout
   no longer waits for Stripe's webhook, but Stripe-side changes that happen later (e.g. a refund Stripe reverses a
   minute afterwards, card `…5126`) only reach the Payment Service through it:
   ```
   stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,charge.refunded,charge.refund.updated,refund.created,refund.updated,refund.failed --forward-to localhost:8080/payments/webhook/stripe
   ```

### Paying for tickets

1. Log in as a participant, open an event, click **Buy Tickets** and choose how many (one booking per ticket).
2. Type a test card into Stripe's card form → **Pay S$…**. Card details go straight from the browser to Stripe, which
   turns them into a PaymentMethod (`pm_…`); neither this app nor the services see the card.
3. The app calls `POST /events/{id}/signup?userId=…&paymentMethodId=pm_…&quantity=n`. The Event Service takes `n`
   places, then charges the PaymentMethod once per ticket through the Payment Service (the amount always comes from
   the event's price). It returns the list of bookings.
4. **CONFIRMED** → **Payment successful**; the booking appears in **My Bookings**, and the payment in the Stripe
   Dashboard (**Transactions**). **PENDING** (declined) → the reason is shown and the place is held for 15 minutes;
   paying again calls `POST /events/{id}/bookings/{bookingId}/payment?paymentMethodId=pm_…` for each unpaid booking.
   Free events (price 0) skip the card form.

| Test card (any future date, any CVC) | Result |
|---|---|
| `4242 4242 4242 4242` | succeeds |
| `4000 0000 0000 0002` | declined; the place is held, try another card |
| `4000 0000 0000 9995` | declined: insufficient funds |
| `4000 0000 0000 5126` | succeeds; a later refund first succeeds, then Stripe reverses it (refund-failed flow) |

**Booking Details** shows the booking's status (Paid, Awaiting payment, Expired, Cancelled), the payment deadline
while it's pending, and for a paid booking a **Stripe sandbox payment** panel with the live status from the Payment
Service: `Paid`, `Refund in progress`, `Refunded` or `Refund failed`, with the `pi_…` / `re_…` ids.

### Refunds

- **Cancel an event** (the business rule: refunds only happen on cancellation). Log in as an organiser, open one of
  your events → **Cancel Event**. The app calls `POST /events/{id}/cancel`: the Event Service deletes the event,
  cancels its bookings and publishes `event.cancelled`, and the Payment Service refunds every paid booking through
  Stripe. The browser makes no refund call. A refund that fails doesn't un-cancel the event: it goes to the Payment
  Service's dead-letter queue, where the Admin retries it.
- **Automatic cancellation:** the Event Service's scheduled job cancels an `ACTIVE` event that is below its minimum
  at its hype deadline, in exactly the same way, so its paid bookings are refunded too.
- **Failed refunds** (Admin login, `/admin/refunds`): the dead-letter queue dashboard, below.

The Admin calls go to the Payment Service's `/admin/*` **through the API Gateway, with the Admin's own access
token**, like every other call that needs a login. The Payment Service checks the token itself (a Cognito user in the
`admin` group, see its `COGNITO_USER_POOL_ID`); the browser never holds a key:

| The browser calls | In AWS | Locally (`VITE_API_BASE_URL=/gw`) |
|---|---|---|
| `GET {API}/admin/refunds/failed` | API Gateway → Payment Service, with the Admin's Cognito token | this app's server forwards it with `X-Admin-Api-Key` (`payment_admin_api_key` from `.env`) |
| `GET {API}/admin/refunds/dlq/count` | same | same |
| `POST {API}/admin/refunds/{paymentId}/retry` | same | same |

Locally the Payment Service usually has no user pool configured, so nginx (Docker) and the Vite dev server stand in
for the gateway and add the admin key, as before. Nothing else under `/gw/admin` is forwarded and other methods get
403. Set `COGNITO_USER_POOL_ID` on a local Payment Service to test the AWS way instead.

### Admin dashboard (failed refunds)

A refund that still fails after every attempt is marked `REFUND_FAILED` by the Payment Service, which puts one
message for it in the refund **dead-letter queue** (`payments.refunds.dlq` in RabbitMQ) and alerts the Admin. The
**Failed refunds** page is where the Admin processes that queue. The page only talks to the Payment Service, never to
RabbitMQ:

| On the page | Payment Service call |
|---|---|
| "⚠ Failed refunds: N", or "No failed refunds require attention." (waiting / being processed / total) | `GET /admin/refunds/dlq/count` |
| The table of failed refunds: payment, booking, event, user, amount, reason, attempts, when, its DLQ messages | `GET /admin/refunds/failed` |
| **Retry refund** (after a confirmation) | `POST /admin/refunds/{paymentId}/retry` |

A retry checks Stripe first and never refunds twice, then refunds again only if needed. When the refund succeeds,
the Payment Service removes the payment's message from the queue, so the count goes down by 1. When it still fails,
the message stays and the page shows the new reason. After every retry the count and the list reload.

**Log in:** Login page → **Log in as an admin** → an account in the Cognito `admin` group. The page loads straight
away with that login; there is no key to type (see [Refunds](#refunds)).

**Seeded failed refunds:** from `services/payment`, with the Payment Service running:

```bash
python scripts/seed_failed_refunds.py            # 3 failed refunds; --count 1..5
```

Each one is real in the Stripe sandbox: a ticket for a demo event paid with `pm_card_visa`, refunded directly at
Stripe (a refund that went through while the Payment Service's own call failed), then refused by Stripe when the
Payment Service tries (`charge_already_refunded`), so it is `REFUND_FAILED` with one DLQ message. **Retry refund**
on any of them succeeds: the Payment Service finds the refund at Stripe, records it without refunding twice, and
removes the message. Every run adds more.

**Ids:** the Event and Payment Services store `userId` / `organiserId` as integers, while a Cognito user's id is a
UUID (`sub`), so the frontend maps the `sub` to a stable integer
([`src/api/serviceIds.ts`](src/api/serviceIds.ts)); see [Things to check or decide](#things-to-check-or-decide).

## Backend integration

Every view gets its data from the async functions in [`src/api/api.ts`](src/api/api.ts):

| Views need | Goes to | Code |
|---|---|---|
| Login, Register, Logout, token refresh | Amazon Cognito (Identity Provider API, from the browser) | [`src/auth/cognito.ts`](src/auth/cognito.ts), [`src/auth/session.ts`](src/auth/session.ts) |
| Events, signups, payment retries | Event Service via the API Gateway, `Authorization: Bearer <access token>` | [`src/api/eventsApi.ts`](src/api/eventsApi.ts), [`src/api/eventMapping.ts`](src/api/eventMapping.ts) |
| Stripe publishable key, a payment's live status, Admin DLQ dashboard | Payment Service | [`src/api/paymentApi.ts`](src/api/paymentApi.ts) |
| My Bookings | bookings recorded in this browser (no Event Service endpoint yet) | [`src/api/bookingStore.ts`](src/api/bookingStore.ts) |

| Event Service endpoint (`EventController`) | Used by |
|---|---|
| `GET /events` | Browse Events, My Organised Events, My Bookings |
| `GET /events/{id}` | Event Details, Checkout, Manage/Edit Event, Booking Details |
| `POST /events` | Create Event |
| `PATCH /events/{id}` (only the changed fields) | Edit Event |
| `POST /events/{id}/cancel` | Manage Event → Cancel Event |
| `DELETE /events/{id}` | client function only, no UI (it deletes without refunds) |
| `POST /events/{id}/signup?userId=…&paymentMethodId=…&quantity=…` | Checkout |
| `POST /events/{id}/bookings/{bookingId}/payment?paymentMethodId=…` | Checkout and Booking Details, after a declined card |

- **Times:** the Event Service stores UTC without a zone (`2026-11-20T11:00:00`); the views show and edit local time.
- **Errors:** the service's `ProblemDetail` `detail` (or the gateway's `message`) is shown as-is. A 401 refreshes
  the access token and retries once; if Cognito rejects the refresh token, the user is sent back to Login.
- **Ownership:** the Event Service only checks the organiser *role*, so the frontend checks that an event's
  `organiserId` is the logged-in user before showing Manage/Edit.
- The business rules live in [`src/domain/eventRules.ts`](src/domain/eventRules.ts) and are unit-tested.

### Configuration

`VITE_*` values are baked in at build time (`.env` for `npm run dev` and `docker compose`, CI variables or Docker
build args for other builds). They are public, never secrets. See [`.env.example`](.env.example).

| Variable | Example | What |
|---|---|---|
| `VITE_API_BASE_URL` | `https://abc123.execute-api.ap-southeast-1.amazonaws.com` | API Gateway; the Event Service is at `${VITE_API_BASE_URL}/events` |
| `VITE_COGNITO_REGION` | `ap-southeast-1` | Region of the Cognito user pool |
| `VITE_COGNITO_CLIENT_ID` | `1a2b3c…` | The pool's public app client |
| `VITE_PAYMENT_API_URL` | `http://localhost:8080` | Payment Service (through the gateway when deployed) |
| `VITE_USER_API_URL` | `http://localhost:8082` | Authentication (User) Service, for `POST /user/me` after sign-up. Empty = through the gateway (`${VITE_API_BASE_URL}/user/me`) |

### Things to check or decide

1. **There's no endpoint to list a user's bookings.** My Bookings only shows bookings made in that browser
   (`localStorage` key `partyfun.bookings.v1`, with a snapshot of each event). Add one to the Event Service (e.g.
   `GET /events/bookings?userId=`) and replace [`src/api/bookingStore.ts`](src/api/bookingStore.ts) with it.
2. **`userId` is a hash of the Cognito `sub`.** The services want an integer user id and Cognito's is a UUID, so the
   frontend sends `toServiceId(sub)`. The real fix is for the services to take the user from the token. That also
   closes the Event Service's known issue that anyone can sign up as any `userId` (or manage any organiser's event).
3. **Cognito app client:** it must have **no client secret** and allow **`USER_PASSWORD_AUTH`** and refresh tokens.
   Admin-created users must set a permanent password first (the app doesn't handle the `NEW_PASSWORD_REQUIRED`
   challenge). If the pool uses the email as the username, Register's `Username` must be the email.
4. **Groups after registration:** done by the Authentication Service (`POST /user/me`, see [Accounts](#accounts)),
   so the API Gateway needs a route for `/user/*` to it. Without a group, every other call returns 403.
5. **Token type:** the frontend sends the **access** token, as the Event Service expects. An HTTP API JWT authorizer
   accepts it (it checks `client_id`). A REST API Cognito authorizer only accepts access tokens when the method has
   OAuth scopes configured; otherwise it expects the ID token.
6. **CORS:** if the API Gateway is on a different domain from CloudFront, it must allow the CloudFront origin and the
   `Authorization` header (and answer the preflight `OPTIONS` itself).
7. **3-D Secure cards stay PENDING.** The Payment Service confirms the card on the server, so the browser can't show
   the 3-D Secure check; such a booking just expires after 15 minutes. That test card was removed from the hints.
8. **The Admin dashboard in AWS** calls `/admin/*` through the API Gateway with the Admin's Cognito token, so the
   Payment Service must be deployed with `COGNITO_USER_POOL_ID` (the infrastructure's `deploy-payment` sets it).
9. **`/api/payment-internal` is now unused.** [`nginx.conf.template`](nginx.conf.template) and
   [`vite.config.ts`](vite.config.ts) still forward it with the service key, which lets anyone trigger refunds.
   Delete those routes.
10. **When is an event Confirmed?** An `ACTIVE` event always shows as Pending, even after reaching its minimum. It
    only shows as Confirmed if the Event Service sets its `status` to `CONFIRMED` (which also closes signups, since
    the service only accepts them for `ACTIVE`). Decide whether the service should do that, and when.

## Scripts

| Command | Does |
|---|---|
| `npm run dev` | Start the dev server with hot reload |
| `npm run build` | Type-check (`tsc -b`) and build to `dist/` |
| `npm run preview` | Serve the production build locally |
| `npm run lint` | ESLint (TypeScript + React Hooks rules) |
| `npm test` | Vitest: business rules, Event Service mapping and client (with `fetch` stubbed), Cognito role checks, route access, and page rendering |

## Testing with the Event Service

There are three levels of testing. Automated tests run as-is. Everything else needs a **Cognito user pool**: the
Event Service rejects every request without a valid Cognito token.

### 1. Automated tests

```bash
# Frontend
cd frontend
npm test            # Vitest: mapping, API client with fetch stubbed, Cognito roles, rules, routes
npm run lint
npm run build       # type-check + production build

# Event Service (needs Docker running, for Testcontainers)
cd services/events
./mvnw clean verify              # unit + integration tests + coverage gate
./mvnw clean verify -DskipITs    # unit tests only, no Docker needed
```

### 2. Prepare Cognito (for every test below)

1. **App client:** no client secret, with `ALLOW_USER_PASSWORD_AUTH` and `ALLOW_REFRESH_TOKEN_AUTH` enabled.
2. **Groups:** `participant`, `organiser` and `admin`.
3. **Test users:** create them, give each a **permanent** password (the app can't handle the "set a new password"
   step), and add each to a group:
   ```bash
   aws cognito-idp admin-create-user --user-pool-id <pool> --username org1 --user-attributes Name=email,Value=org1@x.sg
   aws cognito-idp admin-set-user-password --user-pool-id <pool> --username org1 --password '<Passw0rd!>' --permanent
   aws cognito-idp admin-add-user-to-group --user-pool-id <pool> --username org1 --group-name organiser
   ```
   Do the same for a participant user (e.g. `pat1`, group `participant`).

### 3. Frontend against the Event Service, locally

**Step 1: dependencies.** From `services/events`:

```bash
docker compose up -d postgres rabbitmq
```

**Step 2: the Event Service.** Run it with Maven so it picks up the Cognito settings (its `compose.yaml` doesn't pass
`AWS_REGION` / `COGNITO_USER_POOL_ID` yet). In `services/events/dev.env`:

```
DB_PASSWORD=...
RABBITMQ_PASSWORD=guest
AWS_REGION=ap-southeast-1
COGNITO_USER_POOL_ID=ap-southeast-1_XXXX
```

```bash
./mvnw spring-boot:run      # listens on :8080; it expects the Payment Service on :8081
```

**Step 3: the Payment Service.** Choose one:

- **Real Stripe sandbox (needed to try checkout in the UI).** Run the Payment Service on port 8081 with
  `stripe_secret_key` and `stripe_publishable_key` set, and add `http://localhost:5173` to its
  `cors_allowed_origins`. The card form loads Stripe with the publishable key it provides.
- **Fake gateway** (`docker compose --profile payment up -d` in `services/events`). Browsing, creating, editing and
  cancelling events work, but the card form won't load (the fake gateway has no publishable key). Test signup with
  curl instead ([section 4](#4-the-event-service-api-on-its-own)).

**Step 4: a dev proxy.** The Event Service has no CORS settings (in AWS the API Gateway handles CORS), so the browser
can't call `localhost:8080` directly. Let the Vite dev server forward the calls by adding this to `server.proxy` in
[`vite.config.ts`](vite.config.ts):

```ts
'/gw': {
  target: process.env.EVENTS_UPSTREAM ?? 'http://localhost:8080',
  rewrite: (path) => path.replace(/^\/gw/, ''),
},
```

**Step 5: frontend settings.** `frontend/.env.local`:

```
VITE_API_BASE_URL=/gw
VITE_COGNITO_REGION=ap-southeast-1
VITE_COGNITO_CLIENT_ID=<app client id>
VITE_PAYMENT_API_URL=http://localhost:8081
```

Then `npm run dev` and open http://localhost:5173.

**Step 6: what to click through.**

| Step | Expect |
|---|---|
| Log in as `pat1` with the Organiser toggle | "This account is registered as a participant." |
| Log in as `org1` (Organiser) → Create Event | Event created, shown as **Pending** |
| Edit the event (e.g. change the capacity) | Only the changed fields are sent (DevTools → Network → `PATCH`) |
| Log out → `pat1` → Browse → **Buy Tickets** with card `4000 0000 0000 0002` | Decline message; booking is **Awaiting payment**, place held |
| Pay again with `4242 4242 4242 4242` | Payment successful; spots left go down by 1 |
| My Bookings → open the booking | **Paid**, with the Stripe payment panel |
| Buy the same event again | "You already have a ticket for this event." |
| `org1`: Manage → **Cancel Event** | Event disappears; `pat1`'s booking shows **Cancelled**, the payment panel moves to **Refunded** |
| Log in with a user in no group | Login refused |

In DevTools → Network, every `/gw/events…` request should carry `Authorization: Bearer eyJ…`.

### 4. The Event Service API on its own

Get a token from the CLI (the app client must allow `USER_PASSWORD_AUTH`):

```bash
TOKEN=$(aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id <client> \
  --auth-parameters USERNAME=org1,PASSWORD='<pw>' --query AuthenticationResult.AccessToken --output text)
```

Then call the API. With the fake payment gateway, `pm_card_visa` succeeds and `pm_card_chargeDeclined` is declined:

```bash
curl -H "Authorization: Bearer $TOKEN" localhost:8080/events
curl -X POST "localhost:8080/events/1/signup?userId=42&paymentMethodId=pm_card_visa" -H "Authorization: Bearer $TOKEN"
```

### 5. Deployed (S3 + CloudFront + API Gateway)

1. Build with the real settings: `VITE_API_BASE_URL=https://<api-id>.execute-api.…`, plus the Cognito variables.
2. Upload and refresh the cache: `aws s3 sync dist/ s3://<bucket> --delete`, then invalidate the CloudFront cache.
3. The API Gateway must allow the CloudFront domain and the `Authorization` header for CORS, and its JWT check must
   accept access tokens (an HTTP API JWT authorizer does).
4. CloudFront must serve `index.html` for unknown paths (403/404 → `/index.html`), or reloading a deep link like
   `/events/12` fails.
5. Run through the same table as in [section 3](#3-frontend-against-the-event-service-locally).

## Docker

The [`Dockerfile`](Dockerfile) is multi-stage: Node 22 runs `npm ci` and `npm run build`, then nginx serves the
static files on port 80. [`nginx.conf.template`](nginx.conf.template) sends unknown paths to `index.html` so deep
links like `/events/12` work, and forwards the keyed Admin calls (see [Refunds](#refunds)) to the Payment Service.
The nginx image fills the template in at start from the container's environment: `PAYMENT_UPSTREAM`,
`EVENTS_UPSTREAM`, `AUTH_UPSTREAM`, `payment_admin_api_key` and `payment_service_api_key`. This folder's
[`compose.yaml`](compose.yaml) passes the two keys from `.env` and points the upstreams at the services' published
ports on this machine (`http://host.docker.internal:8080`, `:8083`, `:8082`); the keys stay on the server.

```bash
docker compose up -d --build                       # http://localhost:8001, settings from .env
```

The `VITE_*` URLs and ids the **browser** uses (see [Configuration](#configuration)) are baked in at build time
(Docker build args, which `compose.yaml` fills in from `.env`, CI variables, or `.env` for `npm run dev`). No
Stripe key is built in: the publishable key comes from the Payment Service (`GET /payments/config`) at runtime.

## GitLab CI/CD

[`.gitlab-ci.yml`](.gitlab-ci.yml) has three stages and **no deploy stage**:

| Stage | Jobs |
|---|---|
| `static_analysis` | `lint` (ESLint), `typecheck` (`tsc -b`), `dependency-scan` (`npm audit`, non-blocking), `semgrep-sast`, `secret_detection` |
| `test` | `unit-tests` (Vitest, JUnit report), `build` (production build, `dist/` artifact) |
| `release` | `release-image` (build and push `$CI_REGISTRY_IMAGE/$CI_COMMIT_REF_SLUG:$CI_COMMIT_SHA`, Syft SBOM → `sbom.json`), `container-scan` (Trivy, non-blocking) |

## Structure

```
src/
├── App.tsx                 routes (the 12 views, the Admin login and the failed-refunds dashboard)
├── main.tsx                entry point (router + auth provider)
├── types.ts                User, PartyEvent, Booking, EventInput
├── api/api.ts              async functions the views call (Cognito, Event Service, Payment Service)
├── api/eventsApi.ts        Event Service client: every EventController endpoint, with the Cognito access token
├── api/eventMapping.ts     EventDto / Booking JSON ↔ the views' types (UTC ↔ local time, statuses)
├── api/bookingStore.ts     bookings recorded in this browser (until the Event Service can list them)
├── api/paymentApi.ts       Payment Service: Stripe config, payment status, the Admin DLQ dashboard
├── api/serviceIds.ts       Cognito sub → the services' integer user id
├── payments/stripe.ts      loads Stripe.js with the publishable key from the Payment Service
├── payments/bookingPayment.ts the message shown when a payment didn't go through
├── payments/retryResult.ts the message shown after an Admin retries a failed refund
├── auth/                   Cognito calls, the token session, AuthProvider, route guards, role rules
├── domain/eventRules.ts    hype, booking status, signup and validation rules
├── hooks/useLoad.ts        small data-loading hook
├── components/             Navbar, EventCard, BookingCard, StatusBadge, HypeMeter, EventForm, EventFacts,
│                           ConfirmDialog, RoleToggle, Message
├── pages/                  one file per view
└── styles.css              theme and layout
```
