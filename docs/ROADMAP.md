# Three-Day Roadmap

Dates use the America/New_York time zone. Each day's push contains that stage's implementation and checks.

| Date | Stage | Status |
| --- | --- | --- |
| October 23, 2026 | Day 1: Accounts and rooms | Complete |
| October 24, 2026 | Day 2: Messages and real-time delivery | Complete |
| October 25, 2026 | Day 3: Deployment and end-to-end verification | Pending |

## Day 1 — October 23: Accounts and rooms

**Status: Complete** — 16 JUnit tests passed. Browser checks covered registration, login, room creation, session restoration, and mobile navigation.

### Deliverables

- Java 17 and Spring Boot application.
- Account registration, password hashing, and JWT login.
- Authenticated public-room listing and creation.
- H2 demo profile and PostgreSQL schema with Flyway migrations.
- English web interface for registration, login, and rooms.
- Automated checks for authentication, validation, and room access.

### Acceptance checks

- `make demo` starts the application with a temporary database and JWT secret.
- A new user can register, sign in, view rooms, and create a public room.
- Unauthenticated requests to protected endpoints are rejected.
- Invalid input and duplicate resources return consistent API errors.
- `./mvnw verify` completes successfully.

## Day 2 — October 24: Messages and real-time delivery

**Status: Complete** — 48 JUnit tests passed across the application. Real Redis and browser checks covered two-account live chat, history pagination, recovery of 105 missed messages, retry deduplication, literal-text rendering, and mobile sending. Actual Redis ticket checks verified Origin rejection, replay rejection, and concurrent single-use consumption.

### Deliverables

- Persistent messages and cursor-based history pagination.
- Client-generated message IDs for idempotent retries.
- WebSocket connections using short-lived, single-use tickets and same-origin checks.
- Redis Pub/Sub for delivery across application instances.
- Message composition, display, reconnection, and history recovery in the web interface.
- Tests for message persistence, pagination, retries, and connection authorization.

### Acceptance checks

- Two signed-in users can exchange messages in the same public room.
- Refreshing the page restores messages from stored history.
- Retrying a send with the same client message ID does not create a duplicate.
- Reused or expired connection tickets and cross-origin handshakes are rejected.
- Reconnecting restores the current history page and leaves older messages accessible.
- Automated tests pass before the day's push.

### Verification

- `./mvnw -B -q clean verify`: 48 tests, no failures, errors, or skips.
- Real HTTP/WebSocket JUnit tests exercise committed-message delivery, replay rejection, Origin checks, and authentication expiry. Redis adapter unit tests use test doubles.
- Separate local checks used Redis 8.6.3 with H2, a packaged application, and Chromium. Both browser accounts received live socket events; reconnect recovery traversed multiple history pages without duplicate IDs.
- A frontend regression check used a controlled API to test a delayed history snapshot racing with a newer live event and a lost response after a stored send.
- PostgreSQL with two application instances, containers, and deployment configuration remain in Day 3's scope.

## Day 3 — October 25: Deployment and end-to-end verification

**Status: Pending**

### Deliverables

- Docker image and Compose setup for the application, PostgreSQL, and Redis.
- Kubernetes deployment examples, health probes, and autoscaling configuration.
- Integration checks using real PostgreSQL, Redis, and two application instances.
- Browser checks for the English interface, reconnect behavior, and mobile navigation.
- CI checks covering tests, container builds, and integration verification.
- API, deployment, and verification documentation.

### Acceptance checks

- The container image builds and the Compose services become healthy.
- A ticket issued by one instance can establish a connection on the other instance.
- A message sent through one instance reaches a user connected to the other.
- History and idempotent retries work against PostgreSQL.
- Browser checks cover registration, login, rooms, messages, reconnects, and mobile navigation.
- CI completes successfully for the final stage.
- Documentation distinguishes verified behavior from deployment examples that have only been checked statically.
