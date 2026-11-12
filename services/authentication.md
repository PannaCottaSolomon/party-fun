# Authentication service

Source: [`authentication/`](authentication/) · Spring Boot 4.1 (Java 17) · PostgreSQL · RabbitMQ (publisher only) · Amazon Cognito · port 8080

## What it does

Passwords, sessions and token signing all live in the team's **Amazon Cognito user pool**. This service does not issue tokens of its own. It does three things around Cognito:

1. **Owns the user profile.** One row per user in PostgreSQL (`user_accounts`): email, full name, role, phone. The row's primary key is the Cognito `sub`.
2. **Assigns the role.** When a new user picks PARTICIPANT or ORGANISER, it puts them in the matching Cognito group. That group is what every other service reads from the token (`cognito:groups`) to authorise requests.
3. **Announces user changes.** It publishes `user.created`, `user.updated` and `user.deleted` on RabbitMQ so other services can keep their own copy. Today the notifications service is the consumer (it builds its user directory from these).

It also offers thin login, refresh and logout endpoints that forward to Cognito. These exist for API tools such as Bruno and curl. The frontend talks to Cognito directly for those.

## Where it sits

| Direction | Who | How |
|---|---|---|
| Called by | Frontend, API tools | HTTP `/auth/**` and `/user/**` |
| Calls | Amazon Cognito | AWS SDK (`CognitoIdentityProviderClient`) and the pool's JWKS URL |
| Publishes to | Anyone bound to `user.events` (notifications) | RabbitMQ topic exchange `user.events` |
| Consumes | Nothing | It has no RabbitMQ listeners |

## HTTP API

`/auth/**` is open (you cannot need a token to get one). Every `/user/**` endpoint needs `Authorization: Bearer <Cognito access token>`.

| Method and path | Who may call | What it does |
|---|---|---|
| `GET /auth/config` | Anyone | Returns `{emailVerificationEnabled}` so the frontend knows whether to ask for the emailed code |
| `POST /auth/confirm-sign-up` | Anyone | Confirms a new Cognito account without the emailed code. 403 when email verification is switched on |
| `POST /auth/login` | Anyone | Email + password → Cognito access, ID and refresh tokens |
| `POST /auth/refresh` | Anyone | Refresh token → new access and ID tokens |
| `POST /auth/logout` | Anyone | Revokes the refresh token in Cognito. Always 204 |
| `POST /user/me` | Any valid token (no role needed yet) | One-time onboarding: creates the profile and assigns the role group. 201 |
| `GET /user/me` | Any valid token | The caller's own profile. 404 means "not onboarded yet" |
| `GET /user/{id}` | Owner or ADMIN | One profile |
| `GET /user` | ADMIN | Every profile |
| `PUT /user/{id}` | Owner only | Updates `fullName` and/or `phoneNumber` |
| `DELETE /user/{id}` | Owner only | Deletes the Cognito user, then the profile row |

There is no sign-up endpoint. A user signs up in Cognito through the frontend, then calls `POST /user/me`.

## How the pieces fit

```
controller/        AuthController, UserController        HTTP in, DTOs out
   │
service/           AuthService, UserService              business rules
   │                    │              │
cognito/           CognitoGateway      │                 every AWS Cognito call
repository/                      UserAccountRepository   JPA → user_accounts
service/                         UserEventPublisher      RabbitMQ → user.events

security/          JwtAuthInterceptor → CognitoTokenVerifier → AuthenticatedUser
config/            WebSecurityConfig, CognitoConfig (in cognito/), AdminBootstrap,
                   FeatureFlags, CorsConfig, RabbitMQConfig
exception/         GlobalExceptionHandler + one class per error kind
```

### Classes by package

**`controller`**
- [`AuthController`](authentication/src/main/java/auth/controller/AuthController.java): the five `/auth` endpoints. Each is a one-line delegation to `AuthService`.
- [`UserController`](authentication/src/main/java/auth/controller/UserController.java): the `/user` endpoints. It reads the caller from the `AuthenticatedUser` request attribute and applies the owner/ADMIN rules itself, because only the controller knows which account a request is about.

**`service`**
- [`AuthService`](authentication/src/main/java/auth/service/AuthService.java): login, refresh, logout, config and confirm-without-code. Normalises the email (trim, lower case) and maps `CognitoTokens` to `AuthDto.TokenResponse`.
- [`UserService`](authentication/src/main/java/auth/service/UserService.java): onboarding, reads, update and delete. This is where the profile row, the Cognito group and the RabbitMQ event are tied together.
- [`UserEventPublisher`](authentication/src/main/java/auth/service/UserEventPublisher.java): sends `user.created`, `user.updated`, `user.deleted`.

**`cognito`**
- [`CognitoGateway`](authentication/src/main/java/auth/cognito/CognitoGateway.java): the only class that calls the Cognito SDK. It translates Cognito's errors into this service's exceptions. Admin calls address a user by Cognito *username*, never by `sub`.
- [`CognitoConfig`](authentication/src/main/java/auth/cognito/CognitoConfig.java): builds the SDK client and the `JwtDecoder` (signature via the pool's JWKS, issuer, `token_use=access`, `client_id`).
- [`CognitoProperties`](authentication/src/main/java/auth/cognito/CognitoProperties.java): region, user pool id, client id, and the issuer and JWKS URLs derived from them.
- `CognitoTokens`, `CognitoUser`: small records for what Cognito returns.

**`security`**
- [`JwtAuthInterceptor`](authentication/src/main/java/auth/security/JwtAuthInterceptor.java): a Spring MVC `HandlerInterceptor`. Reads the bearer token, has it verified, and stores the caller as a request attribute. Missing or bad token → 401.
- [`CognitoTokenVerifier`](authentication/src/main/java/auth/security/CognitoTokenVerifier.java): decodes the token and builds the `AuthenticatedUser` (id from `sub`, `username`, roles from `cognito:groups`). Groups that are not one of the three roles grant nothing.
- [`AuthenticatedUser`](authentication/src/main/java/auth/security/AuthenticatedUser.java): record with `isAdmin()` and `isOwnerOf(id)`.

**`config`**
- [`WebSecurityConfig`](authentication/src/main/java/auth/config/WebSecurityConfig.java): registers the interceptor on `/user` and `/user/**` only.
- [`AdminBootstrap`](authentication/src/main/java/auth/config/AdminBootstrap.java): runs once at startup (see below).
- [`FeatureFlags`](authentication/src/main/java/auth/config/FeatureFlags.java): the `EMAIL_VERIFICATION_ENABLED` switch.
- [`RabbitMQConfig`](authentication/src/main/java/auth/config/RabbitMQConfig.java): declares the durable topic exchange `user.events` and the JSON message converter.
- [`CorsConfig`](authentication/src/main/java/auth/config/CorsConfig.java): allowed browser origins from `CORS_ALLOWED_ORIGINS`.

**`entity`, `repository`, `dto`, `exception`**
- [`UserAccount`](authentication/src/main/java/auth/entity/UserAccount.java): the JPA entity, with the nested `Role` enum (`PARTICIPANT`, `ORGANISER`, `ADMIN`). `Role.groupName()` gives the Cognito group name (the role in lower case).
- [`UserAccountRepository`](authentication/src/main/java/auth/repository/UserAccountRepository.java): `JpaRepository` plus `findByEmail` and `existsByEmail`.
- `AuthDto`, `UserDto`: request and response shapes. No DTO ever carries a password back out.
- [`GlobalExceptionHandler`](authentication/src/main/java/auth/exception/GlobalExceptionHandler.java): maps exceptions to JSON errors.

## Request flows

### Any `/user/**` request (authentication)

```
HTTP request
  → JwtAuthInterceptor.preHandle        reads "Authorization: Bearer ..."
      → CognitoTokenVerifier.verify
          → JwtDecoder.decode           signature (JWKS), expiry, issuer, token_use=access, client_id
          ← AuthenticatedUser(id, username, roles)
      request.setAttribute(AuthenticatedUser.ATTRIBUTE, caller)
  → UserController method               @RequestAttribute AuthenticatedUser caller
      owner / ADMIN check → ForbiddenException (403) if it fails
  → UserService
```

### Onboarding: `POST /user/me`

```
UserController.onboard
  → UserService.onboard(sub, cognitoUsername, request)      @Transactional
      1. role must be PARTICIPANT or ORGANISER              else 400 (ADMIN can never be chosen)
      2. no profile row for this id yet                     else 409
      3. CognitoGateway.lookup(username)                    email, email_verified, current groups
           no email → 403; email unverified (when the flag is on) → 403
      4. email not used by another profile                  else 409
      5. user not already in a different role group         else 409
      6. UserAccountRepository.saveAndFlush(account)        id = Cognito sub
      7. CognitoGateway.addUserToGroup(username, role)
      8. UserEventPublisher.publishUserCreated(saved)       → user.events / user.created
  ← 201 UserDto.Response
```

The row is written before the group is added, so a Cognito failure rolls the row back and the call can simply be retried. The caller's current token has no group yet, so the frontend refreshes its token afterwards.

### Login, refresh, logout

```
AuthController.login   → AuthService.login   → CognitoGateway.login    InitiateAuth USER_PASSWORD_AUTH
AuthController.refresh → AuthService.refresh → CognitoGateway.refresh  InitiateAuth REFRESH_TOKEN_AUTH
AuthController.logout  → AuthService.logout  → CognitoGateway.revoke   RevokeToken (unknown token = no-op)
```

A wrong password and an unknown email look the same to the caller (401). A login that Cognito answers with a challenge (for example `NEW_PASSWORD_REQUIRED`) is refused, because this service does not support challenges.

### Confirm sign-up without the code: `POST /auth/confirm-sign-up`

Only while `EMAIL_VERIFICATION_ENABLED=false`. `CognitoGateway.confirmWithoutCode` first checks that no *other* account already has the same email verified (409 if so), then calls `AdminConfirmSignUp` and sets `email_verified=true`.

### Delete: `DELETE /user/{id}`

`UserService.delete` deletes the Cognito user first (so the account can no longer log in or refresh), then the profile row, then publishes `user.deleted`. Access tokens already issued stay valid until they expire.

### Startup: `AdminBootstrap.run`

```
AdminBootstrap (ApplicationRunner)
  → CognitoGateway.ensureGroups()        creates participant / organiser / admin groups if missing
  → seedAdmin()
      skip if no admin email or password is configured
      skip if a profile already exists for that email
      → CognitoGateway.ensureUser(username, email, password, ADMIN)   create or reuse, add to admin group
      → UserAccountRepository.save(admin row)
```

The admin seed does not publish `user.created`. Any Cognito failure here is logged and the service still starts.

## Data model

Table `user_accounts` (Hibernate `ddl-auto: update`):

| Column | Notes |
|---|---|
| `id` (UUID, PK) | The Cognito `sub`. Assigned, never generated here |
| `email` | Unique, lower case, read from Cognito at onboarding |
| `fullName` | Required |
| `role` | `PARTICIPANT`, `ORGANISER` or `ADMIN`, stored as text |
| `phoneNumber` | Optional |
| `createdAt`, `updatedAt` | Set by `@PrePersist` / `@PreUpdate` |

The role stored here is for profile reads and the `user.*` events. For authorisation, the Cognito group in the token is the source of truth.

## Messages published

Exchange `user.events` (topic, durable). No queues are declared here; consumers bind their own.

| Routing key | Body |
|---|---|
| `user.created` | `{id, email, fullName, role, phoneNumber}` |
| `user.updated` | Same shape |
| `user.deleted` | `{id}` |

Publishing is best effort: a broker failure is logged and the HTTP request still succeeds. There is no outbox.

## Errors

| Exception | HTTP |
|---|---|
| `InvalidCredentialsException`, `InvalidTokenException` | 401 |
| `ForbiddenException` | 403 |
| `UserNotFoundException` | 404 |
| `ConflictException`, `EmailAlreadyExistsException` | 409 |
| `IllegalArgumentException`, bean validation failures | 400 |
| `IdentityProviderException` (Cognito unreachable or not configured) | 503 |

## Configuration

Set in [`application.yml`](authentication/src/main/resources/application.yml) from environment variables; see [`.env.example`](authentication/.env.example).

| Variable | Purpose |
|---|---|
| `AWS_REGION`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID` | Which pool and app client. Without them every token is rejected and Cognito calls answer 503 |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Credentials for the Cognito admin calls (local only; a task or pod role in AWS) |
| `ADMIN_BOOTSTRAP_EMAIL`, `_USERNAME`, `_PASSWORD`, `_NAME` | The seeded admin. Blank password = no admin is seeded |
| `EMAIL_VERIFICATION_ENABLED` | Default `false` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | RabbitMQ |
| `CORS_ALLOWED_ORIGINS` | Default `http://localhost:5173` |

## Things worth knowing

- **Security is a plain MVC interceptor, not a Spring Security filter chain.** The project only pulls in `spring-security-oauth2-jose` for the JWT decoder. Anything outside `/user/**` is unauthenticated by design.
- **Only access tokens are accepted.** An ID token fails the `token_use=access` check, and a token from another app client fails the `client_id` check.
- **With email verification off, anyone can sign up with an email they do not own.** `FeatureFlags` says to turn it on before real users sign up.
- **`user.*` events are sent from inside the database transaction and are not guaranteed.** If RabbitMQ is down the event is lost, and the notifications service will not learn about that user.
- **`PUT /user/{id}` has no validation.** A blank `fullName` is accepted; only `null` fields are skipped.
