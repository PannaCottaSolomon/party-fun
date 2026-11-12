# Events service: GitLab CI/CD pipeline

Source: [`events/.gitlab-ci.yml`](events/.gitlab-ci.yml) · related: [`events/Dockerfile`](events/Dockerfile), [`events/ci/smoke-test.sh`](events/ci/smoke-test.sh), [`events/pom.xml`](events/pom.xml)

For what the service itself does, see [events.md](events.md). For what happens after this pipeline (deploying to AWS), see [DEVOPS_FLOW.md](../infrastructure/templates/DEVOPS_FLOW.md).

## What it does

On every push, the pipeline checks the code, builds one Docker image, proves that image works, scans it, and (on `main` or a git tag) publishes it under a release tag.

It follows one rule: **build once, promote.** The image is built a single time. The same image is then smoke-tested, scanned and released. Nothing is rebuilt between the test and the release, so what was tested is exactly what runs later.

**The pipeline does not deploy.** Its last step is publishing an image to this project's GitLab container registry. Deploying to AWS is a separate manual job in the infrastructure repo, which pulls the image this pipeline released.

## At a glance

```
lint        hadolint ─────────────────────────┐
                                              │
test        unit-tests ───────────────────────┤
            integration-tests ────────────────┼──► package   build-image ──► scan   container-scan ──► release   release-image
            chaos-tests ──────────────────────┘              (build, smoke          (Trivy, SBOM,                (main and tags only)
            semgrep-sast      (GitLab template)               test, push :sha)       CRITICAL gate)
            secret_detection  (GitLab template)
```

| Stage | Job | What it proves | Blocks the image? |
|---|---|---|---|
| lint | `hadolint` | The Dockerfile follows best practices | yes |
| test | `unit-tests` | The logic is right, in isolation | yes |
| test | `integration-tests` | The service works against a real database and broker, and coverage is high enough | yes |
| test | `chaos-tests` | No money is left behind when the payment service or the broker fails mid-flow | yes |
| test | `semgrep-sast`, `secret_detection` | No obvious insecure code patterns or committed secrets | no (see below) |
| package | `build-image` | The image builds, boots and reaches its dependencies | yes |
| scan | `container-scan` | The image has no fixable CRITICAL vulnerability | blocks the *release*, not the `:sha` image |
| release | `release-image` | (publishes `:latest` or `:<git tag>`) | n/a |

## When it runs

```yaml
workflow:
  rules:
    - if: $CI_COMMIT_TAG
    - if: $CI_COMMIT_BRANCH
```

One pipeline per branch push and one per git tag. There are no separate merge-request pipelines; a merge request shows its branch's pipeline.

| Trigger | lint, test, package, scan | release |
|---|---|---|
| Push to a feature branch | run | skipped |
| Push to `main` | run | publishes `:latest` |
| Git tag, e.g. `v1.2.0` | run | publishes `:v1.2.0` |

## Settings shared by every job

**Pinned tool versions.** Every tool image is a fixed version (`maven:3.10.0-eclipse-temurin-21`, `docker:29.9.1`, `aquasec/trivy:0.76.0`, `hadolint/hadolint:v2.14.0-debian`), so the pipeline behaves the same from one day to the next. The Maven version matches the project's Maven wrapper.

**`default`:**

- `interruptible: true`: a newer push to the same branch cancels a pipeline that is still running.
- `retry` once, but only for runner problems (`runner_system_failure`, `stuck_or_timeout_failure`, `scheduler_failure`). A failing test is never retried.

**`IMAGE: $CI_REGISTRY_IMAGE:$CI_COMMIT_SHA`.** The name of the one image this pipeline builds, e.g. `registry.gitlab.com/cs302-2026/g4-team2/services/events:<commit sha>`. Every later job refers to it.

**No secrets.** The pipeline needs no CI/CD variables to be set. Tests start their own throw-away database and broker with made-up passwords, and registry access uses the credentials GitLab gives every job (`CI_REGISTRY_USER` / `CI_REGISTRY_PASSWORD`).

### Reusable templates

Jobs whose names start with a dot never run by themselves. Other jobs pull them in with `extends`.

| Template | What it adds | Used by |
|---|---|---|
| `.maven` | The Maven image, and a cache of `.m2/repository` keyed on `pom.xml` (dependencies are only re-downloaded when `pom.xml` changes) | `unit-tests`, and through `.testcontainers` |
| `.dind` | A Docker-in-Docker service: a private Docker daemon for the job, reachable at `tcp://docker:2375` | `build-image`, `release-image`, and through `.testcontainers` |
| `.testcontainers` | `.maven` + `.dind`, plus two settings so Testcontainers finds its containers on the `docker` host and skips its cleanup container (the daemon is thrown away with the job anyway) | `integration-tests`, `chaos-tests` |
| `.registry-login` | A `before_script` that runs `docker login` to the GitLab registry, with the password passed on stdin | `build-image`, `release-image` |

Docker-in-Docker is why the runner must allow privileged containers (GitLab.com shared runners do). TLS is switched off for it because the daemon is only reachable inside the job's own private network.

## The jobs

### lint: `hadolint`

```yaml
script:
  - hadolint Dockerfile
```

Lints the [Dockerfile](events/Dockerfile) for common mistakes (unpinned base images, running as root, wasteful layers). `needs: []` lets it start immediately.

### test: `unit-tests`

```yaml
script:
  - mvn $MAVEN_CLI_OPTS test
```

Runs the `*Test` classes with Maven Surefire. No Docker and no containers, so it is the fastest feedback. Results are uploaded as a JUnit report, which GitLab shows in the pipeline's Tests tab.

### test: `integration-tests`

```yaml
script:
  - mvn $MAVEN_CLI_OPTS verify
```

The main gate. `mvn verify` runs the unit tests again and then the `*IT` classes with Maven Failsafe. Those start, through Testcontainers:

- a real **PostgreSQL**;
- a real **RabbitMQ**, with Toxiproxy in front of it;
- **WireMock** standing in for the payment service.

So these tests exercise real SQL, real row locks and real message publishing. Only the payment service is faked.

`verify` also runs JaCoCo, which writes a coverage report and then **fails the build if line coverage is under 85% or branch coverage under 75%** (the limits are in `pom.xml`).

The `awk` line after it adds up the `LINE_MISSED` and `LINE_COVERED` columns of `jacoco.csv` and prints `Line coverage: NN.N%`. The job's `coverage:` regex picks that line up, which is how the percentage appears on the pipeline and merge request pages.

Artifacts: the JUnit report, the JaCoCo XML (GitLab uses it to mark covered lines in merge request diffs) and the HTML report, kept for a week.

### test: `chaos-tests`

```yaml
script:
  - mvn $MAVEN_CLI_OPTS verify -Pchaos
```

Runs only the `*ChaosIT` classes (`PaymentChaosIT`, `BrokerChaosIT`), which carry the JUnit tag `chaos`. The `chaos` Maven profile skips the unit tests and the coverage gate, because coverage is measured over the unit and integration tests.

The setup is the same as the integration tests, but faults are injected while a payment or a cancellation is in flight:

- WireMock makes the payment service time out, drop connections, answer garbage or errors, refuse the service key, or disappear.
- Toxiproxy cuts the connection to the broker.

Each test asserts the same thing: **no money is left behind.** Every charge ends as a confirmed booking or as a refund the payment service has taken over, and every `event.cancelled` message reaches the broker once it is back.

A failure here blocks the image exactly like a failing integration test.

### test: `semgrep-sast` and `secret_detection`

These two jobs are not written in this file. They come from GitLab's own templates:

```yaml
include:
  - template: Jobs/SAST.gitlab-ci.yml
  - template: Jobs/Secret-Detection.gitlab-ci.yml
```

- **SAST** (static application security testing) scans the source for insecure code patterns.
- **Secret detection** scans the repository for things that look like committed passwords, keys or tokens.

Their findings appear as job artifacts and in the pipeline's security report.

### package: `build-image`

```yaml
needs: [hadolint, unit-tests, integration-tests, chaos-tests]
```

Runs only when all four of those jobs have passed. Four steps:

1. **Pull the last release for its layers.** `docker pull "$CI_REGISTRY_IMAGE:latest" || true`. The `|| true` keeps the very first pipeline (when no `:latest` exists yet) from failing.
2. **Build.**
   - `--pull` always fetches the newest base image, so base-image security patches are picked up.
   - `--cache-from …:latest` with `BUILDKIT_INLINE_CACHE=1` reuses unchanged layers from the last release, so usually only the small application layer is rebuilt.
   - Three `--label`s record where the image came from: the project URL, the build time, and **`org.opencontainers.image.revision=<commit sha>`**. The infrastructure repo's deploy job reads that last label to find out which commit `:latest` is.
3. **Smoke test:** `sh ci/smoke-test.sh "$IMAGE"` (below).
4. **Push** the image as `:<commit sha>`, only if the smoke test passed.

The [Dockerfile](events/Dockerfile) is multi-stage: a Maven stage builds a layered jar, and the final image is a JRE-only Alpine image that runs the app as a non-root user. Tests are not run inside the Docker build; the earlier jobs already ran them. `.dockerignore` only lets `pom.xml` and `src/main/` into the build, so no `.env` file or test code can end up in the image.

#### The smoke test

[`ci/smoke-test.sh`](events/ci/smoke-test.sh) answers "does this actual image start?", which no earlier job checks (they run the code from source, not from the image).

It starts PostgreSQL and RabbitMQ containers on a private Docker network, runs the freshly built image against them with `docker run -e …`, and checks three things:

| # | Check | What it catches |
|---|---|---|
| 1 | The image's own `HEALTHCHECK` reports `healthy` | A bad entrypoint, a missing class, a crash on startup |
| 2 | `/actuator/health` answers `"status":"UP"` | The app cannot reach the database or the broker |
| 3 | `GET /events` without a token answers 401 | The API is up **and** still protected |

On failure it prints the last 100 lines of the container's logs. All containers and the network are removed when the script exits, pass or fail.

### scan: `container-scan`

```yaml
script:
  - trivy image --exit-code 0 --severity HIGH,CRITICAL --output trivy-report.txt "$IMAGE"
  - trivy image --exit-code 0 --format cyclonedx --output sbom.cdx.json "$IMAGE"
  - cat trivy-report.txt
  - trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed "$IMAGE"
```

Trivy pulls the pushed image and runs three times:

| Run | Output | Can it fail the job? |
|---|---|---|
| 1 | A report of every HIGH and CRITICAL vulnerability | no (`--exit-code 0`): information only |
| 2 | An **SBOM** (software bill of materials) in CycloneDX format: every package in the image | no |
| 3 | The gate: CRITICAL vulnerabilities **that have a fix available** | **yes** (`--exit-code 1`) |

`--ignore-unfixed` is the practical part of the gate. A vulnerability with no fix yet cannot be acted on, so it is reported but does not block. One with a fix means "bump the base image or the dependency", so it blocks.

The report and the SBOM are kept for 30 days. Trivy's vulnerability database is cached between pipelines.

### release: `release-image`

```yaml
needs: [build-image, container-scan]
rules:
  - if: $CI_COMMIT_TAG
    variables: { RELEASE_TAG: $CI_COMMIT_TAG }
  - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    variables: { RELEASE_TAG: latest }
script:
  - docker pull "$IMAGE"
  - docker tag "$IMAGE" "$CI_REGISTRY_IMAGE:$RELEASE_TAG"
  - docker push "$CI_REGISTRY_IMAGE:$RELEASE_TAG"
```

Pulls the image that was just tested and scanned, gives it a second name, and pushes that name. No build happens here: this is the "promote" in "build once, promote".

- On `main`, the second name is `:latest`.
- On a git tag, it is the tag itself, e.g. `:v1.2.0`.
- On any other branch the job does not exist.

Two safety settings:

- `interruptible: false`: a newer push never cancels a release half-way.
- `resource_group: release`: only one release runs at a time, so two pipelines cannot race to move `:latest`.

After this job, `:latest` means: **the newest commit on `main` that passed every test, the smoke test and the vulnerability gate.**

## What ends up in the registry

| Tag | Written by | When | Meaning |
|---|---|---|---|
| `:<commit sha>` | `build-image` | every pipeline, on every branch | built, unit/integration/chaos tested, smoke-tested. **Not necessarily scanned** |
| `:latest` | `release-image` | `main` only | all of the above, plus the scan gate passed |
| `:<git tag>` | `release-image` | git tags only | the same, for a named version |

A commit tag never moves. `:latest` moves with every release.

## What stops a bad change

| Problem | Caught by | Effect |
|---|---|---|
| Sloppy Dockerfile | `hadolint` | no image is built |
| Logic bug | `unit-tests` | no image is built |
| Broken SQL, locking or messaging | `integration-tests` | no image is built |
| Coverage below 85% lines / 75% branches | `integration-tests` (JaCoCo) | no image is built |
| Money lost when a dependency fails | `chaos-tests` | no image is built |
| Image does not start, or cannot reach its database or broker | `build-image` (smoke test) | image is not pushed |
| API accidentally left open | `build-image` (smoke test, 401 check) | image is not pushed |
| Fixable CRITICAL vulnerability | `container-scan` | `:latest` does not move |

## How it connects to deployment

The infrastructure repo's manual `deploy-events` job picks up where this pipeline ends:

1. It reads the `org.opencontainers.image.revision` label of `:latest` to get the commit sha.
2. It deploys `:<that sha>` to ECS through CloudFormation.

So a commit reaches AWS only if this pipeline moved `:latest` to it, and only when someone presses the deploy button. Database and broker passwords are not this pipeline's concern: on AWS they come from Secrets Manager.

## Things worth knowing

- **Stage order is not what sequences the jobs; `needs` is.** `hadolint` and the three test jobs all have `needs: []`, so they start at once, in parallel. `build-image` starts as soon as the four jobs it names have passed.
- **`build-image` does not wait for SAST or secret detection.** They are not in its `needs` list, so a finding from either does not stop the image from being built or released by this file's logic. Treat them as reports to read.
- **The `:<sha>` image is pushed before the scan.** A build that fails the Trivy gate is still in the registry under its commit tag, on any branch. Only `:latest` is protected by the scan. The deploy job is safe because it starts from `:latest`.
- **Unit tests run twice per pipeline:** once in `unit-tests` for fast feedback, and again inside `integration-tests`, because `mvn verify` includes the test phase and coverage needs both.
- **Coverage is only enforced in `integration-tests`.** The chaos profile switches JaCoCo off.
- **Every branch push builds and stores an image.** Nothing in this file cleans old `:<sha>` tags up; that would need a registry cleanup policy in the project settings.
- **`pom.xml` configures `spring-boot:build-image` with a Windows Docker pipe** (`//./pipe/dockerDesktopLinuxEngine`). That is a second, local way to build an image and is not used here: the pipeline builds from the Dockerfile.
