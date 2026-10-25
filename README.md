# Real-Time Chat Service

[![CI](https://github.com/Cfengsu2002/real-time-chat-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Cfengsu2002/real-time-chat-service/actions/workflows/ci.yml)

**Say hello. Drop offline. Pick up where you left off.**

A real-time chat application built with Java 17, Spring Boot, and PostgreSQL.

Public rooms, live conversations, and a way back when your connection has other plans.

## The interesting parts

| A small plot twist | What happens next |
| --- | --- |
| Two people connect to different app instances. | Redis Pub/Sub carries live messages between them. |
| One browser goes offline. | A fresh connection and cursor-based history recover missed messages. |
| A send succeeds, but its response disappears. | Retrying the same client message ID returns the saved message without creating a duplicate. |
| The conversation gets longer. | Indexed PostgreSQL storage and cursor pagination keep older messages accessible. |

## Under the hood

- JWT authentication with BCrypt password hashing.
- Public rooms with persistent messages and cursor-based history.
- Live notifications across application instances through Redis Pub/Sub.
- Short-lived, single-use WebSocket tickets and explicit Origin checks.
- Automatic reconnection, missed-message recovery, and idempotent send retries.
- Responsive English interface with safe plain-text message rendering.
- Docker Compose deployment and Kubernetes examples with health probes and autoscaling configuration.

## Run with Docker

Requires Docker Compose and OpenSSL.

```bash
git clone https://github.com/Cfengsu2002/real-time-chat-service.git
cd real-time-chat-service
umask 077
printf 'JWT_SECRET=%s\nPOSTGRES_PASSWORD=%s\n' \
  "$(openssl rand -hex 48)" "$(openssl rand -hex 24)" > .env
docker compose up -d --build --wait
```

Open [localhost:18080](http://localhost:18080), create an account, and join a room. PostgreSQL and Redis run on the internal Compose network. Named volumes retain data when containers restart.

To try delivery between two application instances:

```bash
docker compose --profile scale up -d --build --wait
```

Open [localhost:18081](http://localhost:18081) in a separate browser profile and sign in with another account. Both instances share the same database, Redis server, and JWT secret.

For a little live demo: take one browser offline, send a few messages from the other, then reconnect. The conversation should pick up where it left off.

Stop the stack with `docker compose --profile scale down`. See [.env.example](.env.example) for port and origin configuration.

## Run without containers

Requires Java 17, OpenSSL, and Make:

```bash
make demo
```

Open [localhost:8080](http://localhost:8080). This mode uses an in-memory H2 database and local message delivery; data is cleared on shutdown.

For PostgreSQL and Redis configuration, see [Deployment](docs/DEPLOYMENT.md).

## Checks

```bash
./mvnw verify
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tests/requirements.txt
python -m playwright install chromium
# Start the two-instance Compose stack first.
python tests/integration/check_chat.py
python tests/browser/check_browser.py
```

CI runs the Java tests, builds the container, checks two instances against PostgreSQL and Redis, exercises the desktop and mobile interfaces, and validates the Kubernetes manifests.

## Documentation

- [HTTP API and WebSocket protocol](docs/API.md)
- [Architecture and recovery decisions](docs/ARCHITECTURE.md)
- [Docker and Kubernetes deployment](docs/DEPLOYMENT.md)
- [Verification commands and scope](docs/VERIFICATION.md)
- [Development roadmap](docs/ROADMAP.md)
