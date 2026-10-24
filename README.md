# Relay Chat

[![CI](https://github.com/Cfengsu2002/relay-chat/actions/workflows/ci.yml/badge.svg)](https://github.com/Cfengsu2002/relay-chat/actions/workflows/ci.yml)

A small chat project built with Java 17 and Spring Boot. The current stage covers accounts, JWT login, and public rooms.

## What works now

- Register an account and sign in with JWT authentication.
- List public rooms and create a new room after signing in.
- Use the English web interface to manage your session and rooms.
- Run locally with H2, or connect to PostgreSQL with Flyway migrations.

All rooms are public to signed-in users.

## Run locally

Requires Java 17, OpenSSL, and Make. Maven Wrapper downloads Maven on the first run.

```bash
git clone https://github.com/Cfengsu2002/relay-chat.git
cd relay-chat
make demo
```

Open [localhost:8080](http://localhost:8080), register an account, and create a room. The demo uses an in-memory H2 database, so its data is cleared when the application stops. `make demo` generates a random JWT secret unless you provide one.

## Use PostgreSQL

Create a PostgreSQL database and user, then set the connection details:

```bash
export JWT_SECRET="$(openssl rand -hex 48)"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/relay"
export SPRING_DATASOURCE_USERNAME="relay"
export SPRING_DATASOURCE_PASSWORD="your-database-password"
./mvnw spring-boot:run
```

`JWT_SECRET` must contain at least 32 UTF-8 bytes. Flyway applies the database schema on startup.

## Tests

```bash
./mvnw verify
```

## Roadmap

| Date | Stage | Status |
| --- | --- | --- |
| October 23, 2026 | Accounts, JWT login, and public rooms | Complete |
| October 24, 2026 | Message history, WebSocket, and Redis synchronization | Pending |
| October 25, 2026 | Docker, Kubernetes examples, and end-to-end checks | Pending |

See [the three-day roadmap](docs/ROADMAP.md) for the scope and acceptance checks for each stage.

Account and room endpoints are documented in [API.md](docs/API.md).
