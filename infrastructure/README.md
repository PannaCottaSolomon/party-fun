# party.fun infrastructure

CloudFormation templates for party.fun's **shared AWS services**, deployed with one script:

```bash
./deploy.sh
```

The ECS cluster, RabbitMQ, tracing (OpenTelemetry Collector + Jaeger), and the Event, Payment, Authentication and
Notification Services (each with its RDS database) are in here.

## What gets created

| Stack | AWS service | Resources | Purpose |
|---|---|---|---|
| `partyfun-secrets` | **Secrets Manager** | `partyfun/payment`, `partyfun/payment-service-key`, `partyfun/database`, `partyfun/rabbitmq`, `partyfun/notifications` | API keys and credentials, generated here; the Stripe and Resend keys are placeholders you fill in |
| `partyfun-cognito` | **Cognito** | user pool `partyfun-users`, app client `partyfun-web`, groups `participant` / `organiser` / `admin` | Sign-up and login; issues the JWT carrying the user's group |
| `partyfun-frontend` | **S3** + **CloudFront** | private bucket, distribution with Origin Access Control | Hosts the React app over HTTPS (`https://xxxx.cloudfront.net`) |
| `partyfun-cluster` | **ECS** + **Cloud Map** | Fargate cluster `partyfun`, private DNS namespace `partyfun.local`, security group `partyfun-services` | Where the backend services run; they reach each other by name |
| `partyfun-rabbitmq` | **ECS** + **EFS** | RabbitMQ service at `rabbitmq.partyfun.local:5672`, its data on EFS | Message broker shared by Payment, Events and Notifications |
| `partyfun-observability` | **ECS** | OpenTelemetry Collector at `otel-collector.partyfun.local:4318` + Jaeger UI on port 16686 | Distributed tracing for the backend services |
| `partyfun-api` | **API Gateway** (HTTP API) | API, `$default` stage, Cognito JWT authorizer, routes once a backend exists | Single entry point; checks the token before anything reaches a service |
| `partyfun-events` | **ECS** + **RDS** (PostgreSQL) | cluster `partyfun-events`, service `events`, database `partyfun-events-db` | The Event Service ([`templates/events.yaml`](templates/events.yaml), `deploy-events` job only) |
| `partyfun-payment` | **ECS** + **RDS** (MySQL) | cluster `partyfun-payment`, service `payment`, database `partyfun-payment-db` | The Payment Service ([`templates/payment.yaml`](templates/payment.yaml), `deploy-payment` job only) |
| `partyfun-auth` | **ECS** + **RDS** (PostgreSQL) | cluster `partyfun-auth`, service `auth`, database `partyfun-auth-db` | The Authentication Service ([`templates/auth.yaml`](templates/auth.yaml), `deploy-auth` job only) |
| `partyfun-notifications` | **ECS** + **RDS** (MySQL) + **CloudWatch** | cluster `partyfun-notifications`, service `notifications`, database `partyfun-notifications-db`, alarm `partyfun-notifications-email-dropped` | The Notification Service ([`templates/notifications.yaml`](templates/notifications.yaml), `deploy-notifications` job only) |
| `partyfun-chaos` | **Fault Injection Service** + **CloudWatch** | 5 experiment templates, their role, alarm `partyfun-money-at-risk`, log group `/fis/partyfun` | Chaos engineering ([`templates/chaos.yaml`](templates/chaos.yaml), `./deploy.sh chaos` / `deploy-chaos` only) |

[`deploy.sh`](deploy.sh) deploys them in that order. Every run creates or updates them (only what changed). It then
builds the frontend (`../user-interfaces/frontend`) against the API's URL, uploads it to the bucket and refreshes
CloudFront.

## How authentication works

```
1. Client ──username or email + password──▶ Cognito   (through our own Login / Register pages)
2. Client ◀──signed JWT──────── Cognito           payload includes "cognito:groups": ["admin"]
3. Client ──Authorization: Bearer <JWT>──▶ API Gateway
4. API Gateway validates the JWT (no Lambda): Cognito's public key signature, "iss" = the user pool,
   issued to our app client, not expired. Invalid or missing → 401, the backend is never called.
5. API Gateway ──proxies──▶ backend service, with X-User-Id = the token's "sub" and the original Authorization
   header
```

**Usernames.** Every user has a username they choose (e.g. `alice`, no `@`) and an email address; they can sign in
with either. Tokens carry the username in `cognito:username` and a fixed user ID in `sub` (the ID the services
store).

**Roles = Cognito groups.**
- Every ID and access token from the pool includes the user's groups in `cognito:groups`
  (`participant`, `organiser` or `admin`).
- Only admin API calls change group membership: [`add-user.sh`](add-user.sh), or the Authentication Service adding a
  newly registered user to `participant` or `organiser`. Nobody can make themselves an admin.

**Who checks what.**

| | Where |
|---|---|
| Is the token genuine and still valid? | API Gateway (JWT authorizer) |
| Is the user allowed to call this route? (e.g. `/admin/*` needs `admin`) | The backend service |

API Gateway's built-in authorizer can only check the token, not its groups. HTTP API header mappings also can't
forward claims whose names contain a colon (`cognito:groups`), so the backend reads the groups from the payload of
the forwarded token. It doesn't need to verify the signature again: API Gateway already did, and the services must
only be reachable through API Gateway (private subnets).

**Routes.** API Gateway sends each path straight to its service, with no load balancer: through a **VPC Link**
into the VPC, where it asks **Cloud Map** for a running task's IP and port (`auth`, `events`, `payment` and
`notifications` are registered in `partyfun.local` with A and SRV records). A service's routes exist once it is
deployed and `api` is deployed after it. Notifications listens on port 5000 and only accepts listed sources, so the
api stack adds the VPC Link to its security group.

| Route | Token needed? | Service |
|---|---|---|
| `ANY /auth/{proxy+}` (sign-up settings, confirm sign-up, login) | no: these come before the user has a token | Authentication |
| `/user/{proxy+}` (`/user/me`: the profile) | **yes** | Authentication |
| `/events`, `/events/{proxy+}` | **yes** | Events |
| `GET /payments/config`, `GET /payments/{paymentId}` | no: publishable key, a payment's status | Payment |
| `POST /payments/webhook/stripe` | no: Stripe calls it; Payment checks Stripe's signature | Payment |
| `/payments`, `/payments/{proxy+}` (all but GET), `GET /payments/booking/{bookingId}`, `GET /payments/user/{userId}`, `/bookings/{proxy+}`, `/admin/{proxy+}` | **yes** | Payment |
| `GET /notifications` (the email log) | **yes**, plus the service's `X-API-Key` | Notifications |
| `GET /notification-types` (the message contract) | **yes** | Notifications |

Notifications' `POST /notify_*` (sends emails) has no route: only the other services call it, inside the VPC.

Any other path gets `404`. CORS allows only the CloudFront URL (methods GET, POST, PUT, PATCH, DELETE). API Gateway
answers the browser's preflight requests itself.

Routes that need a token are created per method (GET, POST, PUT, PATCH, DELETE), never as `ANY`: `ANY` would also
match the browser's CORS preflight (`OPTIONS`, which carries no token), the authorizer would answer `401`, and the
browser would block the request. With no `OPTIONS` route, API Gateway answers every preflight itself.

**Order:** deploy the services (`deploy-auth`, `deploy-events`, `deploy-payment`, `deploy-notifications`), then
`deploy-api`. The API URL never changes, so the frontend needn't be rebuilt.

**If a service deploy fails while changing its Cloud Map registration** (the auth, payment and notifications stacks
registered A records only before; CloudFormation may refuse to add SRV in place): set the CI/CD variable
`SKIP_SERVICE_DISCOVERY=true`, run that service's job once (it removes the registration), delete the variable, and
run the job again (it registers the service with SRV).

## Before you deploy

You need:
- **AWS CLI v2:** `aws --version`.
- **Git Bash** (on Windows) to run the `.sh` scripts.
- **Node.js 22 + npm** to build the frontend.
- **An AWS account** whose credentials can create Cognito, S3, CloudFront, API Gateway and Secrets Manager
  resources and CloudFormation stacks.

### 1. Point the AWS CLI at your account

Use one of these.

| You have | Run | Then |
|---|---|---|
| An IAM user with access keys | `aws configure` | Enter the access key ID, secret key, region `ap-southeast-1`, output `json` |
| IAM Identity Center (SSO) | `aws configure sso`, then `aws sso login --profile <name>` | Set `AWS_PROFILE=<name>` in `config.env` |
| AWS Academy Learner Lab | AWS Details → **AWS CLI: Show**, paste into `~/.aws/credentials` | These expire when the lab session ends; paste them again next session |

Check that it works and shows the account you expect:

```bash
aws sts get-caller-identity
```

### 2. Settings

```bash
cp config.env.example config.env     # config.env is git-ignored
```

The defaults are fine for a first deploy:

| Setting | Default | Meaning |
|---|---|---|
| `AWS_REGION` | `ap-southeast-1` | Region for every stack |
| `STACK_PREFIX` | `partyfun` | Prefix of stack, resource and export names (one per environment, e.g. `partyfun-dev`) |
| `AWS_PROFILE` | empty | Named CLI profile; empty = default credentials |
| `FRONTEND_DIR` | `../user-interfaces/frontend` | The React app to build and upload |

No secrets go in `config.env`.

## Deploy

```bash
./deploy.sh                 # everything; shows the account and region, asks before continuing
./deploy.sh --yes           # everything, no question
./deploy.sh cognito api     # only these parts
./deploy.sh site            # only rebuild and upload the website
```

| Part | What it deploys |
|---|---|
| `secrets` | stack `partyfun-secrets` |
| `cognito` | stack `partyfun-cognito` |
| `frontend` | stack `partyfun-frontend` (S3 bucket + CloudFront) |
| `cluster` | stack `partyfun-cluster`, in the default VPC unless `VPC_ID` / `SUBNET_IDS` are set |
| `rabbitmq` | stack `partyfun-rabbitmq`; needs `secrets` and `cluster` |
| `observability` | stack `partyfun-observability`; needs `cluster` |
| `api` | stack `partyfun-api`; reads the Cognito issuer and client ID and the CloudFront URL from those stacks |
| `site` | builds the React app with the API URL and the Cognito region and app client ID (`VITE_*`), uploads it to S3 and refreshes CloudFront; reads them from the stacks |

Parts always run in that order. A part that reads another stack's outputs fails if that stack isn't deployed yet.
After a Cognito change, always deploy `api` as well.

### From GitLab

The pipeline (`.gitlab-ci.yml`) runs `cfn-lint` on every push. On `main` it also has manual deploy jobs; click ▶ on
one in **Build → Pipelines**:

| Job | Runs |
|---|---|
| `deploy-all` | `./deploy.sh --yes` |
| `deploy-secrets` | `./deploy.sh --yes secrets` |
| `deploy-cognito-api` | `./deploy.sh --yes cognito api` |
| `deploy-cluster-rabbitmq` | `./deploy.sh --yes cluster rabbitmq` |
| `deploy-observability` | `./deploy.sh --yes observability` |
| `deploy-frontend` | `./deploy.sh --yes frontend` |
| `deploy-api` | `./deploy.sh --yes api` |
| `deploy-site` | `./deploy.sh --yes site` |
| `deploy-events` | the `partyfun-events` stack, with the events project's newest released image |
| `deploy-payment` | the `partyfun-payment` stack, with the image released for the payment project's newest `main` commit. |
| `deploy-auth` | the `partyfun-auth` stack, with the authentication project's newest released image |
| `deploy-notifications` | the `partyfun-notifications` stack, with the image released for the notifications project's newest `main` commit |


They use the g4-team2 group's protected AWS variables. `deploy-all` and `deploy-site` clone the frontend repo, so
the frontend project's **Settings → CI/CD → Job token permissions** must allow this project. Run `deploy-api`
after deploying or registering a service, so API Gateway routes to it.

The first run takes about 5–10 minutes (CloudFront is the slow part). At the end it prints the frontend URL, the API
URL, the user pool ID, the app client ID and the issuer.

## After the first deploy

**1. Create your admin** (nobody can sign up as one):

```bash
./add-user.sh admin you@example.com 'YourPassw0rd' admin
```

Use it for test users too:

```bash
./add-user.sh organiser organiser@example.com 'YourPassw0rd' organiser
./add-user.sh participant participant@example.com 'YourPassw0rd' participant
```

Arguments: username (no `@`), email, password, group. The password needs 8+ characters with upper, lower and a
digit. Users are created with a permanent password and a verified email, so Cognito sends them nothing.

**2. Check the token carries the group:**

```bash
CLIENT_ID=$(aws cloudformation describe-stacks --stack-name partyfun-cognito \
  --query "Stacks[0].Outputs[?OutputKey=='AppClientId'].OutputValue" --output text)
TOKEN=$(aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id "$CLIENT_ID" \
  --auth-parameters USERNAME=admin,PASSWORD='YourPassw0rd' \
  --query AuthenticationResult.IdToken --output text)
python -c "import base64,json,sys; p=sys.argv[1].split('.')[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4))),indent=2))" "$TOKEN"
```

The output includes `"cognito:groups": ["admin"]` and `"cognito:username": "admin"`. `USERNAME=you@example.com`
works too.

**3. Put the Stripe sandbox keys into Secrets Manager:**
- Console → Secrets Manager → `partyfun/payment` → **Retrieve secret value** → **Edit**.
- Replace the three `CHANGE_ME` values with `sk_test_…`, `whsec_…` and `pk_test_…`. Leave
  `payment_admin_api_key` as it is.
- The console keeps the keys out of your shell history.
- Never use live (`sk_live_`) keys.
- Don't change `GenerateSecretString` in `templates/secrets.yaml` afterwards: CloudFormation would regenerate the
  secret and overwrite your values.

**4. Open the frontend** at the printed `https://xxxx.cloudfront.net`. Deep links such as `/events/e-neon` work
because CloudFront serves `index.html` for paths that aren't files.

## Connecting the backend

`deploy.sh api` reads each service stack's `DiscoveryServiceArn` output and creates that service's routes (see
**Routes** above). Requests without a valid token get `401` from API Gateway.

The service tasks still have public IPs (to pull images from GitLab) and accept port 8080 directly. For production,
put them in private subnets with a NAT gateway so they can only be reached through API Gateway.

## RabbitMQ

```bash
./deploy.sh cluster rabbitmq
```

- **One broker, data on EFS.** Fargate wipes a task's disk when it is replaced, so `/var/lib/rabbitmq` is on EFS and
  queued messages (including emails waiting to be retried) survive restarts and deploys. A deploy stops the old
  broker before starting the new one, so the broker is down for about a minute; the services reconnect by themselves.
- **Login.** User `partyfun`, password in Secrets Manager `partyfun/rabbitmq`. It is only applied the first time
  (empty data folder): changing the secret later does not change the broker's password.
- **Who can connect.** Port 5672 accepts only tasks in the `partyfun-services` security group, plus
  `RABBITMQ_ADMIN_CIDR` (your IP, `x.x.x.x/32`) for 5672 and the management UI on 15672. The default allows no one.
- **Exchanges and queues** are not created here: each service declares its own when it starts.
- **Services connect with** `amqp://partyfun:<password>@rabbitmq.partyfun.local:5672/%2F`, inside the VPC (exports
  `partyfun-rabbitmq-host`, `partyfun-rabbitmq-secret-arn`).

**Check it from your laptop.** Set `RABBITMQ_ADMIN_CIDR=$(curl -s https://checkip.amazonaws.com)/32` in `config.env`,
run `./deploy.sh rabbitmq`, then find the task's public IP (ECS console → cluster `partyfun` → service `rabbitmq` →
task → Networking) and open `http://<public-ip>:15672`. The IP changes whenever the task is replaced. Set the CIDR
back to `127.0.0.1/32` and redeploy when you're done (a deploy from GitLab also resets it).

**Run `rabbitmqctl` inside the broker** (needs the
[Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)):

```bash
TASK=$(aws ecs list-tasks --cluster partyfun --service-name rabbitmq --query 'taskArns[0]' --output text)
aws ecs execute-command --cluster partyfun --task "$TASK" --container rabbitmq --interactive \
  --command "rabbitmqctl list_queues name messages consumers"
```

**Logs:** CloudWatch log group `/ecs/partyfun/rabbitmq`.

## Payment Service

[`templates/payment.yaml`](templates/payment.yaml): a MySQL 8.4 RDS database (`payment`, not public) and one
Fargate task, like the Event Service. Its settings are the same lowercase names as the local `.env`: the database
and broker logins come from `partyfun/database` and `partyfun/rabbitmq`, the Stripe sandbox keys and the admin key
from `partyfun/payment`, the service key from `partyfun/payment-service-key`. The empty database's tables are created
on the first start (`SPRING_JPA_HIBERNATE_DDL_AUTO=update`).

Once, before the first `deploy-payment`:
1. In the payment project, **Settings → Repository → Deploy tokens**: a token with `read_registry`. Put its
   username and token in this project's masked CI/CD variables `PAYMENT_REGISTRY_USERNAME` /
   `PAYMENT_REGISTRY_TOKEN` (ECS keeps using it to pull the image).
2. In the payment project, **Settings → CI/CD → Job token permissions**: allow this project (the job reads the
   newest `main` commit).
3. The Stripe sandbox keys in `partyfun/payment` (`sk_test_...`, `pk_test_...`); the service won't start with
   `CHANGE_ME`.

The job (manual, like the others) deploys the image the payment pipeline released for `main`'s newest commit (that pipeline must
have passed its release stage), or the commit in the CI/CD variable `PAYMENT_IMAGE_TAG`. It waits until the task is healthy and prints
`http://<public ip>:8080`. When the cluster stack exists, the task also joins the services security group (needed to
reach RabbitMQ) and is reachable inside the VPC at `http://payment.partyfun.local:8080`; set that as
`EVENTS_PAYMENT_BASE_URL` and run `deploy-events` again. The Event Service also receives the internal service key
(`PAYMENT_SERVICE_API_KEY`, from `partyfun/payment-service-key`), which it sends as `X-Service-Api-Key` when it asks
Payment to refund a booking. The frontend URL is used for CORS when the frontend stack exists.

- Deploy it only through `deploy-payment`, never with `ecs update-task-definition`:
  CloudFormation owns the task definition.
- The Admin dashboard's DLQ count uses RabbitMQ's management API (15672), which the broker only allows from
  `RABBITMQ_ADMIN_CIDR`; the count shows an error there for now.
- The payment endpoints have no login and the port is public, so anyone can make Stripe **sandbox** payments (no
  real money).

**Logs:** CloudWatch log group `/ecs/partyfun/payment`.

## Authentication Service

[`templates/auth.yaml`](templates/auth.yaml): a PostgreSQL 17 RDS database (`authdb`, not public) and one Fargate
task, like the Payment Service. The service keeps each user's profile (keyed by their Cognito `sub`), adds a newly
registered user to the `participant` or `organiser` group (`POST /user/me`), and publishes `user.created` /
`user.updated` / `user.deleted` on RabbitMQ. The frontend still signs users up and logs them in with Cognito
directly.

Unlike the other services, it calls AWS itself: the Cognito admin APIs (confirm a sign-up, add to a group, create the
admin, delete a user), on this user pool only. The stack gives the task a role for that, or uses
`ECS_TASK_ROLE_ARN` (e.g. LabRole) when the account can't create IAM roles. It never uses access keys: the
`AWS_ACCESS_KEY_ID` in the service's local `.env` is for local runs only.

Once, before the first `deploy-auth`:
1. In the authentication project, **Settings → Repository → Deploy tokens**: a token with `read_registry`. Put its
   username and token in this project's masked CI/CD variables `AUTH_REGISTRY_USERNAME` / `AUTH_REGISTRY_TOKEN`.
2. The authentication project's `main` pipeline must have passed its `release-image` job (that publishes
   `:latest`).
3. In a Learner Lab: set `ECS_TASK_ROLE_ARN` to LabRole's ARN, as well as `ECS_EXECUTION_ROLE_ARN`.

The job deploys the commit that `:latest` was built from, or the commit in `AUTH_IMAGE_TAG`. It waits until the task
is healthy and prints `http://<public ip>:8080` (health: `/actuator/health`). When the cluster stack exists, the
task also joins the services security group (needed to reach RabbitMQ) and is reachable inside the VPC at
`http://auth.partyfun.local:8080`. The frontend URL is used for CORS when the frontend stack exists.

Optional CI/CD variables:

| Variable | Default | Meaning |
|---|---|---|
| `AUTH_ADMIN_EMAIL` | empty | Set it to have the service create the admin (username `admin`) on startup, with a password generated into Secrets Manager `partyfun/auth-admin`. Empty: create the admin with `add-user.sh` instead. An `admin` user that already exists keeps its password. |
| `AUTH_EMAIL_VERIFICATION` | `false` | `true`: new accounts must enter the code Cognito emails. `false` lets anyone sign up with an email address they don't own, so turn it on before real users sign up. |
| `AUTH_IMAGE_TAG` | empty | A commit SHA to deploy instead of `:latest`'s. |

- Deploy it only with `deploy-auth`, for the same reason as payment: task definitions registered elsewhere are
  unknown to CloudFormation.
- Without the rabbitmq stack the service still runs (its health check then skips the broker), but the `user.*`
  events are not published.
- The port is public, like the other services. `/user` needs a Cognito access token; `/auth/*` doesn't.

**Logs:** CloudWatch log group `/ecs/partyfun/auth`.

## Notification Service

[`templates/notifications.yaml`](templates/notifications.yaml): a MySQL 8.4 RDS database (`notifications`, not
public) and one Fargate task, like the Payment Service. The database holds the notification log and the user
directory (user id -> email and name, kept from the Authentication Service's `user.*` messages); the service creates
its tables on the first start. It consumes `notify.*` and `user.*` from RabbitMQ and sends emails through Resend.
Logins come from Secrets Manager: `partyfun/notifications` (Resend key, API key), `partyfun/database`,
`partyfun/rabbitmq`.

Once, before the first `deploy-notifications`:
1. In the notifications project, **Settings → Repository → Deploy tokens**: a token with `read_registry`. Put its
   username and token in this project's masked CI/CD variables `NOTIFICATIONS_REGISTRY_USERNAME` /
   `NOTIFICATIONS_REGISTRY_TOKEN`.
2. In the notifications project, **Settings → CI/CD → Job token permissions**: allow this project.
3. Run `deploy-secrets` (it adds `partyfun/notifications`), then put the Resend API key (`re_...`) in it: Secrets
   Manager → `partyfun/notifications` → **Retrieve secret value** → **Edit** → replace `CHANGE_ME` in
   `resend_api_key`. Leave `api_key` as it is.
4. Optional CI/CD variables: `NOTIFICATIONS_ADMIN_EMAIL` (receives admin alerts, e.g. a refund that failed for good),
   `NOTIFICATIONS_ALARM_EMAIL` (emailed when an email is dropped; AWS sends a confirmation link first),
   `NOTIFICATIONS_EMAIL_REDIRECT_TO` (testing: every email goes to this inbox, real recipient in the subject; with
   Resend's `onboarding@resend.dev` sender, only your Resend account's address receives anything),
   `NOTIFICATIONS_EMAIL_FROM` (an address on a domain verified in Resend).

Deploy it after `deploy-cluster-rabbitmq` (and `deploy-observability` for tracing), then run `deploy-api`. Its HTTP
API is at `http://notifications.partyfun.local:5000` for the other services, with the `X-API-Key` from
`partyfun/notifications`. Through API Gateway, `GET /notifications` and `GET /notification-types` work with a
Cognito token; the log also needs that `X-API-Key`:

```bash
curl -H "Authorization: Bearer <ID token>" -H "X-API-Key: <api_key from partyfun/notifications>" \
  "<ApiUrl>/notifications?limit=5"
```

- **Healthy** means the RabbitMQ consumer is connected (`/ready`).
- **Users who registered before it first ran** aren't in its user directory: re-register them, or have them update
  their profile.
- **Alarm** `partyfun-notifications-email-dropped` fires on any `discarding` log line (an email given up on).

**Logs:** CloudWatch log group `/ecs/partyfun/notifications`.

## Tracing (OpenTelemetry + Jaeger)

```bash
./deploy.sh cluster observability
```

```
services --OTLP traces--> otel-collector.partyfun.local:4318 --> Jaeger (UI on :16686)
                           batch, drop sensitive attributes      keeps the latest 50,000 traces in memory
```

- **The services find it by themselves.** `deploy-events`, `deploy-payment` and `deploy-auth` read this stack's
  `OtlpEndpoint` output and pass it to their stacks, which set `OTEL_EXPORTER_OTLP_ENDPOINT`
  (`http://otel-collector.partyfun.local:4318`) and tag every trace with `deployment.environment.name=aws` and
  `service.version=<image commit>`. Deploy `observability` first, then (re)deploy the services. Without it they
  record no traces at all. The Event Service also joins the services security group, which the Collector (and
  RabbitMQ) require. `deploy-notifications` does the same for the Notification Service (which also sets `OTEL_ENABLED=true`).
- **One task, two containers.** The Collector receives every trace, batches it and deletes any attribute whose
  name mentions email, password, secret, token, authorization, cookie or phone, as a safety net for personal data.
  It then forwards to Jaeger in the same task. Replacing Jaeger (with Tempo or AWS X-Ray, say) is a change to the
  Collector's config in `templates/observability.yaml`; no service changes.
- **Traces are kept in memory.** A restart or redeploy of this stack loses them; that is fine for a course
  project. Raise `MaxTraces` in the template if Jaeger drops older traces too soon (and the task's memory with it).
- **Opening the UI.** Set `JAEGER_ADMIN_CIDR=$(curl -s https://checkip.amazonaws.com)/32` in `config.env`, run
  `./deploy.sh observability`, find the task's public IP (ECS console → cluster `partyfun` → service
  `observability` → task → Networking) and open `http://<public-ip>:16686`. The IP changes whenever the task is
  replaced. Only one CIDR is allowed: for several teammates, take turns, or use a range that covers you all.
- **Logs:** CloudWatch log group `/ecs/partyfun/observability` (streams `otel-collector/...` and `jaeger/...`).

## Chaos engineering (AWS FIS)

Experiments with AWS Fault Injection Service, on the deployed services, along the path money takes: the signup
payment, the event cancellation and the refund. Each one has the same hypothesis: **the fault costs availability,
never money**.

| Experiment (job) | Fault (FIS action) | What must hold |
|---|---|---|
| `payment-down` | Payment's task is stopped (`aws:ecs:stop-task`); ECS starts a new one | A signup keeps its place (PENDING); an event cancelled meanwhile is refunded once Payment is back (durable queue) |
| `broker-down` | RabbitMQ's task is stopped | The cancellation waits in the Event Service's outbox and is refunded once the broker is back (queues on EFS) |
| `events-crash` | The Event Service's task is stopped | The new task picks up owed refunds, unconfirmed payments and the outbox from its database |
| `payment-unreachable` | 100% packet loss from the Event Service to `payment.partyfun.local` for 3 min (`aws:ecs:task-network-packet-loss`), both still up | A network partition: signups keep their place, nothing is charged without a booking |
| `payment-slow` | 1.5 s (± 0.4 s) latency on the same traffic (`aws:ecs:task-network-latency`) | Slow payments and timeouts: nobody is charged twice or charged without a place |

Run one with its `chaos-*` job (manual, main), or locally with `node chaos/run.mjs <experiment>`. The runner
([`chaos/run.mjs`](chaos/run.mjs)):

1. checks the **steady state** first (events, payment and rabbitmq running, the API answering, the alarm OK) and
   starts nothing otherwise;
2. creates two test events, A and B (SGD 1.00, Stripe sandbox), and pays for a place at A;
3. starts the experiment and, **while the fault is on**, cancels A and signs up for B (`events-crash`: once the Event
   Service is back);
4. cancels B after the experiment, then waits up to 10 min: every payment of A and B must end `REFUNDED` (or never
   charged, `FAILED`), nobody may be charged twice, and the services must be back in steady state with no help.

It exits 1 and lists what broke otherwise. `chaos-check` (`node chaos/run.mjs check`) only checks the setup and the
steady state, and changes nothing.

**All of them with one button:** `chaos-all` (`node chaos/run.mjs all`) runs the check, then each experiment in the
order of the table, and ends with a summary of every verdict. It skips the network experiments while
`EVENTS_FAULT_INJECTION` is off, and carries on after a failed experiment. It only stops early if the system doesn't
return to steady state, because then no further fault is injected. Expect 45–60 minutes.

**Stop condition.** The alarm `partyfun-money-at-risk` fires when Payment moves a refund to its DLQ or refuses the Event
Service's compensation refund (log metric filters on both services). It stops a running experiment at once, and no
experiment starts while it's on.

**Setting it up:**

1. Two test accounts, then their sign-ins in masked CI/CD variables: `CHAOS_ORGANISER_USERNAME` /
   `CHAOS_ORGANISER_PASSWORD` (organiser group) and `CHAOS_PARTICIPANT_USERNAME` / `CHAOS_PARTICIPANT_PASSWORD`
   (participant group). Create them with `./add-user.sh` if needed.
2. Run `deploy-chaos` (or `./deploy.sh chaos`). It needs the cluster, rabbitmq, events and payment stacks.
3. For the two network experiments only, set the CI/CD variable `EVENTS_FAULT_INJECTION=true` and run `deploy-events`.
   The Event Service's task then gets what FIS needs to reach it:
   - an SSM agent sidecar (non-essential, AWS's own registration script);
   - `enableFaultInjection` and `pidMode: task`;
   - a task role for the sidecar;
   - 2 GB of memory instead of 1 GB.

   Without the variable the task is unchanged.
4. Optional: a pipeline schedule on main with the variable `CHAOS_EXPERIMENTS=true` runs `chaos-all`. Its ▶ button
   runs it on demand.

**Good to know:**
- Experiments share the `aws-infrastructure` resource group with the deploy jobs, so none runs during a deploy.
- Experiment logs go to `/fis/partyfun`, and the sidecar's to `/ecs/partyfun/events` (streams `ssm-agent/...`).
- The test events appear in the app for the few minutes a run takes, titled `Chaos test ...`.

## Changing the user pool

Some user pool settings (e.g. how users sign in) can't be changed in place: CloudFormation then **replaces the pool**.
The new pool has a new pool ID, app client ID and issuer, and **every user is deleted**. Afterwards:
1. redeploy `partyfun-api` as well, so its JWT authorizer trusts the new pool: deploy both with
   `./deploy.sh cognito api` or the `deploy-cognito-api` job;
2. recreate the users with `./add-user.sh`;
3. update anything configured with the old pool ID or app client ID.

## Not done yet (outside this repo)

- **Group checks in the services.**
  - The Payment Service's `/admin/*` currently accepts its own HS256 token or `X-Admin-Api-Key`.
  - Behind API Gateway it must instead allow `cognito:groups` containing `admin`, read from the gateway-verified
    token.
  - Until then the Admin dashboard won't work through API Gateway.
- **Frontend features that relied on its own server forwarding keys** (the Admin dashboard, cancellation refunds)
  don't work on CloudFront, which only serves files. They work once the services accept Cognito tokens through API
  Gateway.

## Removing everything

Delete in reverse order. The bucket must be emptied first:

```bash
aws cloudformation delete-stack --stack-name partyfun-api
aws cloudformation delete-stack --stack-name partyfun-notifications  # keeps a final database snapshot
aws cloudformation delete-stack --stack-name partyfun-observability
aws cloudformation delete-stack --stack-name partyfun-rabbitmq     # keeps the EFS file system (delete it in the EFS console)
aws cloudformation delete-stack --stack-name partyfun-cluster      # after every service in it is gone
aws s3 rm "s3://$(aws cloudformation describe-stacks --stack-name partyfun-frontend \
  --query "Stacks[0].Outputs[?OutputKey=='BucketName'].OutputValue" --output text)" --recursive
aws cloudformation delete-stack --stack-name partyfun-frontend
aws cloudformation delete-stack --stack-name partyfun-cognito      # deletes every user
aws cloudformation delete-stack --stack-name partyfun-secrets
```

Deleted secrets are kept for 7–30 days before they're gone. Redeploying with the same names during that time fails
until you restore them, or remove them with
`aws secretsmanager delete-secret --secret-id partyfun/payment --force-delete-without-recovery` (and the same for the
other three).

## Cost

Mostly free-tier or a few dollars a month at course traffic:
- Cognito: free up to 10,000 monthly active users on the Lite tier.
- API Gateway: per million requests.
- S3 and CloudFront: tiny for a small static site.
- **Secrets Manager:** about US$0.40 per secret per month, so ≈ US$1.60 for the four.
- **RabbitMQ:** the one Fargate task (0.5 vCPU, 1 GB) plus its public IP is roughly US$25 a month while it runs; EFS
  is cents. Stop it between demos with `aws ecs update-service --cluster partyfun --service rabbitmq --desired-count 0`
  (queued messages stay on EFS) and start it again with `--desired-count 1`.
- **Tracing:** the Collector + Jaeger task (0.5 vCPU, 1 GB) is roughly another US$25 a month while it runs. Stop it the
  same way: `aws ecs update-service --cluster partyfun --service observability --desired-count 0`.
- **Chaos (FIS):** US$0.10 per action-minute, so a few cents per experiment run; the templates cost nothing while
  idle. `EVENTS_FAULT_INJECTION=true` doubles the Event Service task's memory (about US$3–4 a month more).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `AWS credentials are missing or expired` | `aws configure` / `aws sso login` / paste fresh lab credentials, then `aws sts get-caller-identity` |
| A stack is in `ROLLBACK_COMPLETE` | Read the failed event (CloudFormation console → stack → Events), fix it, `aws cloudformation delete-stack --stack-name <name>`, deploy again |
| `... already exists` for a secret | A deleted secret with that name is still in its recovery window: see [Removing everything](#removing-everything) |
| The site shows an old version | `deploy.sh` invalidates CloudFront; wait a minute and hard-reload (Ctrl+Shift+R) |
| `AccessDenied` creating a resource | Your credentials lack permission for that service (common in restricted lab accounts) |

## Files

```
deploy.sh                 deploy / update everything, build + upload the frontend
add-user.sh               create a Cognito user in a group (e.g. your admin)
config.env.example        settings (copy to config.env, git-ignored)
templates/secrets.yaml    Secrets Manager
templates/cognito.yaml    Cognito user pool, app client, groups
templates/frontend.yaml   S3 bucket + CloudFront
templates/cluster.yaml    ECS cluster, Cloud Map namespace, services security group
templates/rabbitmq.yaml   RabbitMQ on ECS Fargate with its data on EFS
templates/observability.yaml  OpenTelemetry Collector + Jaeger on ECS Fargate
templates/api.yaml        API Gateway HTTP API + Cognito JWT authorizer + routes
templates/auth.yaml       Authentication Service on ECS Fargate + its PostgreSQL RDS database
templates/notifications.yaml  Notification Service on ECS Fargate + its MySQL RDS database + the dropped-email alarm
templates/chaos.yaml      AWS FIS experiment templates + the money-at-risk stop condition
chaos/run.mjs             runs one experiment with test traffic and checks that no money was lost
```
