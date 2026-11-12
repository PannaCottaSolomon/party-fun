# Authentication Service

Owns user accounts on top of **AWS Cognito**: each account's role (a Cognito
group) and its profile (name, phone). Users sign up and log in on the frontend,
which talks to the team's Cognito user pool directly; this service then creates
the profile and puts the user in the role's group (`POST /user/me`). Cognito
stores the passwords and issues the tokens, so every service verifies the same
Cognito access tokens. This service absorbed the former `user-service`, so one
profile row is the single source of truth for an account (see
[Why user-service was merged in](#why-user-service-was-merged-in)).

## Stack
- Java 17 + Spring Boot 4.1.1 (Spring MVC, embedded Tomcat, port 8080)
- AWS Cognito user pool through the AWS SDK for Java v2 (`cognitoidentityprovider`)
- Cognito access-token verification with Spring Security's `NimbusJwtDecoder`
  (RS256, keys from the pool's JWKS); only the decoder is pulled in, not the
  security starter
- PostgreSQL (RDS in prod, local container for dev) for the profiles
- RabbitMQ (AMQP) — publishes `user.created` / `user.updated` / `user.deleted`
  on the `user.events` topic exchange for choreography with other services.
  JSON messages use Spring AMQP's `JacksonJsonMessageConverter`. Payloads never
  contain password material
- Docker / docker-compose for local dev, built for ECS + Kubernetes deploy

The `pom.xml` `<properties>` block pins three versions managed by Spring Boot
4.1.1 (Tomcat, Jackson, the RabbitMQ client) to patched releases flagged by the
CI Trivy scan; remove each override once a Spring Boot release manages that
version or newer.

## Endpoints

| Method | Path            | Description                                   | Success |
|--------|-----------------|-----------------------------------------------|---------|
| POST   | /user/me        | Create my profile after signing up in Cognito: role (participant / organiser), name, phone | 201 |
| GET    | /user/me        | My profile (404 = not created yet)            | 200     |
| GET    | /user/{id}      | Get a profile by account id — **owner or ADMIN** | 200  |
| GET    | /user           | List all profiles — **ADMIN only**            | 200     |
| PUT    | /user/{id}      | Update `fullName` / `phoneNumber` — **owner only** | 200 |
| DELETE | /user/{id}      | Delete the Cognito user and the profile — **owner only** | 204 |
| GET    | /auth/config    | Public feature switches for the frontend: `{"emailVerificationEnabled": false}` | 200 |
| POST   | /auth/confirm-sign-up | Confirm a new account **without** the emailed code (`{"username": ...}`); only while email verification is off, else 403 | 204 |
| POST   | /auth/login     | *Testing:* email or username + password → Cognito tokens | 200 |
| POST   | /auth/refresh   | *Testing:* new access + ID token for a refresh token | 200 |
| POST   | /auth/logout    | *Testing:* revoke a refresh token in Cognito  | 204     |

Errors: 400 (validation, or a role other than participant / organiser), 401
(wrong password, bad token), 403 (wrong owner / role, email not verified while
verification is on, or confirming without a code while it is on), 404, 409 (profile already exists, email taken, already in another role's
group), **503** (Cognito not configured or unreachable).

The `/auth/*` endpoints are for testing with Bruno, Postman or curl. They
forward to the same Cognito app client as the frontend, so the tokens are the
same. There is no `/auth/register`: sign-up happens in Cognito through the
frontend.

### Email verification switch (`EMAIL_VERIFICATION_ENABLED`, **off** for now)

| | On (`true`) | Off (`false`, current default) |
|---|---|---|
| After sign-up | The user enters the code Cognito emails (`ConfirmSignUp`) | The frontend calls `POST /auth/confirm-sign-up`: this service confirms the account and marks its email verified, no code needed |
| `POST /user/me` | Only for a verified email (else 403) | Any account with an email |

The frontend reads the switch from `GET /auth/config`, so it is set in one place.
With it off, anyone can sign up with an email address they don't own, so turn
it on before real users sign up. While it's off, Cognito may still email a code
at sign-up (the user can ignore it); to stop those emails, turn off
"Cognito-assisted verification" in the user pool too.

### Sign-up flow (frontend)

```
1. Register page:  Cognito SignUp (username, email, password)  → Cognito emails a code
2.                 Cognito ConfirmSignUp (code)                 → email verified
                   (switch off: POST /auth/confirm-sign-up instead, no code)
3. Login page:     Cognito InitiateAuth (USER_PASSWORD_AUTH)    → tokens, no group yet
4.                 POST /user/me {fullName, role, phoneNumber}  → this service checks the role,
                                                                   creates the profile, adds the group
5.                 Cognito InitiateAuth (REFRESH_TOKEN_AUTH)    → new tokens with cognito:groups
```

`POST /user/me` takes the account id from the token (`sub`) and the email from
Cognito (it must be verified), never from the request. It writes the profile
first and adds the Cognito group last, so if Cognito fails nothing is saved and
the call can simply be retried. Until step 4 the user has no group, so the
other services answer 403 and `GET /user/me` answers 404: that's how the
frontend knows to show the "complete your profile" step.

### Endpoint protection
`/auth/**` is open. Every `/user` endpoint requires
`Authorization: Bearer <Cognito access token>`:

- missing, malformed, forged or expired token, a token from another user pool or
  app client, or an **ID** token instead of the access token → **401**
- valid token, but not the account owner / not ADMIN as the table requires → **403**

`/user/me` works for a token without any group (a user who just signed up).
ADMIN can *read* profiles (to find who to contact about a failed refund) but
cannot edit or delete accounts — its job is refund recovery, not account
management. The check is `JwtAuthInterceptor` + `CognitoTokenVerifier`
(signature, expiry, issuer, `token_use=access`, `client_id`, then the caller's
`sub` and `cognito:groups`) plus the owner/role rules in `UserController`.

The `/user/{id}` paths are the same as the old user-service API. There is no
`POST /user`. Email, password and role are not updatable through
`PUT /user/{id}`.

### Roles
There are three roles, each a Cognito group: `PARTICIPANT` (`participant`),
`ORGANISER` (`organiser`) and `ADMIN` (`admin`) — the same groups the events
service reads. Group names are matched ignoring case, so a pool with `Participant`,
`Organiser`, `Admin` works as is; a missing group is created on startup (lower case).

- **`PARTICIPANT` / `ORGANISER`** are chosen by the user in `POST /user/me`. Any
  other value is **rejected with 400** (fail-closed), so a privileged role can
  never be self-assigned. This service, not the browser, adds the user to the
  group (Cognito admin API). A user who is already in another role's group
  cannot pick a different one (409).
- **`ADMIN`** is a platform-operator role (manage failed refunds / the payment DLQ)
  and can **never be chosen**. A single admin account is **seeded on startup**
  into Cognito (group `admin`) from configuration (see
  [Configuration](#configuration)); it logs in like everyone else. Seeding does
  not publish `user.created`: the admin is an operator, not a platform user.

For authorisation the **token's `cognito:groups` is the source of truth**. The
profile's `role` column is a copy for profile reads and the `user.*` events; if
you move a user to another group in the AWS console, update the row too.

### Token model
All tokens are Cognito's, whether the frontend got them directly or through
`POST /auth/login` (`{ accessToken, idToken, refreshToken, tokenType, expiresInSeconds }`):

- **Access token** — send it as `Authorization: Bearer` to every service.
  Claims include `sub` (account id = profile id), `cognito:groups`, `client_id`,
  `token_use=access`. It has no `email` claim. Lifetime: the app client's
  setting (Cognito default 1 hour).
- **ID token** — for the frontend (it carries `email`). Not accepted as a bearer
  token.
- **Refresh token** — opaque, revocable (Cognito's `RevokeToken` also
  invalidates the access tokens issued from it). A refresh returns the same
  refresh token unless refresh-token rotation is enabled on the app client.
  Default lifetime 30 days.

## Cognito setup

Use the team's user pool (the same one as the events service). Once per pool:

1. **App client:** the frontend's — **no client secret**, auth flows
   `ALLOW_USER_PASSWORD_AUTH` and `ALLOW_REFRESH_TOKEN_AUTH`. Put its id in
   `COGNITO_CLIENT_ID`: tokens issued to any other app client are rejected here
   (the `client_id` check).
2. **Sign-in:** the frontend signs up with a username plus an email, so allow
   sign-in with the username and the email. Self sign-up on, email verification
   on. The pool must not require attributes other than `email`.
3. **AWS credentials** for the admin calls: the task/pod role in AWS, or
   `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (and `AWS_SESSION_TOKEN` for
   Learner Lab credentials) locally. Needed `cognito-idp` actions:
   `AdminGetUser`, `AdminListGroupsForUser`, `AdminAddUserToGroup`,
   `AdminDeleteUser`, `AdminCreateUser`, `AdminSetUserPassword`, `ListGroups`,
   `CreateGroup`, and, while email verification is off, `AdminConfirmSignUp`,
   `AdminUpdateUserAttributes` and `ListUsers`. (`InitiateAuth` and `RevokeToken` need no IAM
   permission.)
4. **Admin username:** in a pool that signs in with usernames (email as an
   alias), Cognito refuses an email-shaped username, so set
   `ADMIN_BOOTSTRAP_USERNAME` (e.g. `admin`). In a pool that signs in with email,
   leave it empty.

Without `COGNITO_USER_POOL_ID` / `COGNITO_CLIENT_ID` the service still starts
(health probes work), but `POST /user/me` and `/auth/*` answer 503 and every
token is rejected.

## Run locally

Configuration and secrets (Cognito ids, AWS credentials, admin bootstrap
credentials) are read from a gitignored `.env` file. Create it from the template
first:

```bash
cp .env.example .env    # then fill in the values
docker compose up --build
```

Starts the service on `localhost:8080`, plus Postgres (host port `5433`) and
RabbitMQ (management UI at `localhost:15672`, guest/guest). The admin account in
your `.env` is seeded into Cognito on first startup.

Without Docker, run Postgres/RabbitMQ yourself and pass the same variables (the
shell does not auto-load `.env`):

```bash
set -a; source .env; set +a      # export everything in .env
mvn spring-boot:run
```

## Example

Sign up on the frontend (or with the AWS CLI: `aws cognito-idp sign-up` +
`confirm-sign-up`), then:

```bash
# Login -> { accessToken, idToken, refreshToken, tokenType, expiresInSeconds }
curl -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"a@b.com","password":"Password123!"}'

# Create my profile and get my role (once, right after signing up)
curl -X POST localhost:8080/user/me \
  -H "Authorization: Bearer <accessToken>" -H "Content-Type: application/json" \
  -d '{"fullName":"Alice","role":"PARTICIPANT","phoneNumber":"+65 9123 4567"}'

# Refresh, so the access token contains the new group
curl -X POST localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken-from-login>"}'

# My profile
curl localhost:8080/user/me -H "Authorization: Bearer <accessToken>"

# Logout
curl -X POST localhost:8080/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken>"}'
```

## Configuration

| Env var              | Default                  | Notes                              |
|----------------------|--------------------------|------------------------------------|
| `AWS_REGION`         | `ap-southeast-1`         | Region of the user pool            |
| `COGNITO_USER_POOL_ID` | *(none)*               | e.g. `ap-southeast-1_AbCdEfGhI`; same value as the events service |
| `COGNITO_CLIENT_ID`  | *(none)*                 | The frontend's app client, without a secret (see [Cognito setup](#cognito-setup)) |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` | *(none)* | Local only; in AWS use the task/pod role |
| `EMAIL_VERIFICATION_ENABLED` | `false` | Must new accounts enter the emailed code? See [Email verification switch](#email-verification-switch-email_verification_enabled-off-for-now) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Browser origins (comma-separated) allowed to call this API; empty = none (e.g. when API Gateway handles CORS) |
| `ADMIN_BOOTSTRAP_EMAIL`    | `admin@partyfun.local` | Email of the seeded ADMIN account |
| `ADMIN_BOOTSTRAP_USERNAME` | *(the email)*          | Cognito username of the admin; set a plain one (e.g. `admin`) for a pool that signs in with usernames |
| `ADMIN_BOOTSTRAP_PASSWORD` | *(none — set in `.env`)* | **No committed default.** Must meet the pool's password policy; **blank ⇒ no admin is seeded**. An existing Cognito user's password is never changed |
| `ADMIN_BOOTSTRAP_NAME`     | `Platform Admin`       | Display name of the seeded admin |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | localhost / 5432 / authdb / postgres / postgres | |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | localhost / 5672 / guest / guest | |

Local dev reads these from `.env` (see [Run locally](#run-locally)); real
environments supply them through their secrets manager / Kubernetes Secret.

## Notes
- `ddl-auto: update` is used for convenience during coursework. Swap in
  Flyway/Liquibase migrations before deploying to RDS. Profiles are in the
  `user_accounts` table (keyed by Cognito `sub`); the old `credentials` and
  `refresh_tokens` tables from the pre-Cognito version are no longer used and
  can be dropped.
- The `/auth/**` endpoints are intentionally open (no Spring Security filter
  chain) — in this project auth is fronted by an API Gateway.
- A user who signed up in Cognito but never called `POST /user/me` has no group
  and no profile: other services refuse them (403) until they finish.
- A deleted account's access token stays valid until it expires (≤ 1 hour by
  default); it can no longer log in or refresh, and its `/user/{id}` calls
  return 404.

## Why user-service was merged in

Auth and user-service each stored the role (`credentials.role` and a copy in
`users.role`, synced by an `auth.registered` event). Merging them gives one
place for the role and profile and removes that sync event and its failure
modes. Since the move to Cognito, no password material is stored here at all.
Nothing else in the project consumed `auth.registered` or called user-service,
so no other service needed changes. The `user.*` events are still published on
`user.events` for any future consumer.

## CI/CD

The GitLab pipeline in `.gitlab-ci.yml` runs Dockerfile linting, Maven tests,
GitLab SAST and secret detection, then builds one immutable image tagged with
the commit SHA. That image is smoke-tested against PostgreSQL and RabbitMQ
(without Cognito: only health is checked), scanned with Trivy, and promoted to
`latest` on the default branch or to the Git tag on tagged commits. The tests
never call AWS: Cognito is mocked and tokens are signed with a test key. The
registry credentials are supplied by GitLab's built-in CI variables.

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

Then start the service, make a request, and open Jaeger at <http://localhost:16686> (service `auth-service`). If Jaeger
isn't running, the service works normally and only logs that it couldn't export spans.
