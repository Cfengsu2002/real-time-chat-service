# Relay Chat

[![CI](https://github.com/Cfengsu2002/relay-chat/actions/workflows/ci.yml/badge.svg)](https://github.com/Cfengsu2002/relay-chat/actions/workflows/ci.yml)

A real-time chat application built with Java 17, Spring Boot, native WebSocket, Redis Pub/Sub, and PostgreSQL.

## What works now

- Register an account and sign in with JWT authentication.
- List public rooms and create a new room after signing in.
- Send messages, browse older conversations, and retry interrupted sends without creating duplicates.
- Receive live messages over WebSocket, with short-lived, single-use connection tickets.
- Reconnect automatically and recover missed messages from stored history.
- Synchronize live delivery across application instances with Redis Pub/Sub.
- Run locally with H2, or connect to PostgreSQL with Flyway migrations.

All rooms are public to signed-in users.

## Run locally

Requires Java 17, OpenSSL, and Make. Maven Wrapper downloads Maven on the first run.

```bash
git clone https://github.com/Cfengsu2002/relay-chat.git
cd relay-chat
make demo
```

Open [localhost:8080](http://localhost:8080), register an account, and join a room. Use a second browser profile to try a conversation between two accounts. The demo uses an in-memory H2 database and local message delivery, so it needs no Redis server and its data is cleared when the application stops. `make demo` generates a random JWT secret unless you provide one.

## Use PostgreSQL and Redis

Start PostgreSQL and Redis, create a database and user, then set the connection details:

```bash
export JWT_SECRET="$(openssl rand -hex 48)"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/relay"
export SPRING_DATASOURCE_USERNAME="relay"
export SPRING_DATASOURCE_PASSWORD="your-database-password"
export SPRING_DATA_REDIS_HOST="localhost"
export SPRING_DATA_REDIS_PORT="6379"
export APP_ALLOWED_ORIGINS="http://localhost:8080,http://127.0.0.1:8080"
./mvnw spring-boot:run
```

`JWT_SECRET` must contain at least 32 UTF-8 bytes. Flyway applies the database schema on startup. Use a fresh database for this rebuild; the previous version's database schema is different.

For multiple instances, share the PostgreSQL database, Redis server, JWT secret, and allowed-origin configuration. PostgreSQL stores the conversation history; Redis carries live notifications and connection tickets. Redis Pub/Sub is transient, so clients recover missed notifications through the history API.

The default allowed origins are the two local URLs shown above. Set `APP_ALLOWED_ORIGINS` to your site's exact origin when using another port or deploying behind HTTPS.

## Tests

```bash
./mvnw verify
```

## Roadmap

| Date | Stage | Status |
| --- | --- | --- |
| October 23, 2026 | Accounts, JWT login, and public rooms | Complete |
| October 24, 2026 | Message history, WebSocket, and Redis synchronization | Complete |
| October 25, 2026 | Docker, Kubernetes examples, and end-to-end checks | Pending |

See [the three-day roadmap](docs/ROADMAP.md) for the scope and acceptance checks for each stage.

HTTP endpoints and the WebSocket protocol are documented in [API.md](docs/API.md). See [Architecture](docs/ARCHITECTURE.md) for storage, delivery, and recovery decisions.
