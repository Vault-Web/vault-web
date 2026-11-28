# Backend

## Responsibilities

The backend provides the core services for the Vault Web ecosystem. It is a
Spring Boot application responsible for:

- user authentication and JWT-based security
- user and session management
- chat, groups, polls, and collaboration features
- REST APIs consumed by the frontend
- persistence using PostgreSQL

## Group WebSocket authorization

STOMP group subscriptions require an authenticated principal and current group
membership. Wildcard subscriptions are rejected because broker patterns could
otherwise bypass the group boundary. A rejected subscription receives an error
only in the requesting session; other tabs and valid subscriptions stay connected.

Each outgoing group message (including deletion, typing and future subtopics)
checks current membership for the recipient's authenticated session immediately
before the outbound handler runs. This also covers messages waiting in the
outbound executor after a member leaves or is removed. Broadcast sender headers
are not used as the recipient identity. Missing session identity or a failed
membership lookup drops the group message; private destinations are unaffected.
Session identity tracking is cleared on STOMP or transport disconnect.
Direct client `SEND` frames to group broker destinations are rejected, even for
members, so they cannot bypass validation in the `/app/chat.*` handlers.
Server-side publication and the existing frontend application routes are unchanged.

The delivery check performs a database membership lookup per recipient/message;
it deliberately does not cache a positive authorization across membership changes.
Already delivered data cannot be revoked, and an individual socket write already
in progress cannot be recalled. A blocked subscription can resume delivery after
membership is granted again; leaving does not disconnect the socket.

## Requirements

To develop or run the backend independently, you'll need:

- Java 21 or later (Java 24 is also supported)
- PostgreSQL database instance
- Maven (or use the included Maven Wrapper)

## Required configuration

The backend signs JWTs with three secrets. There are no defaults: it refuses to start
unless each one is set, is at least 32 bytes long, and is not one of the values that used to be
committed to this repository.

| Environment variable | Property | Used for |
| --- | --- | --- |
| `JWT_SECRET` | `jwt.secret` | Access tokens. Must be identical to the secret configured in cloud-page. |
| `JWT_REFRESHSECRET` | `jwt.refreshSecret` | Refresh tokens |
| `JWT_MEDIASECRET` | `jwt.mediaSecret` | Short-lived media URLs |

Generate a value for each and export them before starting the backend:

```bash
export JWT_SECRET=$(openssl rand -hex 32)
export JWT_REFRESHSECRET=$(openssl rand -hex 32)
export JWT_MEDIASECRET=$(openssl rand -hex 32)
./mvnw spring-boot:run
```

> **Existing deployments:** the previous defaults were public in the repository history, so any
> deployment that ever ran with them must be treated as compromised. Set new values and rotate
> them (this invalidates existing sessions).

## Running the backend

The backend can run in **HTTP** or **HTTPS** mode depending on your development workflow.

### HTTP mode (API testing only)

Use HTTP mode for backend development and API testing over plain HTTP on localhost.

```bash
./mvnw spring-boot:run
```

Once started, the backend is available at:

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

### HTTPS mode (full-stack development)

Use HTTPS mode when developing together with the Angular frontend, as it
requires HTTPS for secure cookies and JWT authentication.

Start the backend with the `dev` profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Once started, the backend is available at:

- API: https://localhost:8080
- Swagger UI: https://localhost:8080/swagger-ui.html

> **Browser warning:** Your browser will display a warning about the self-signed
> development certificate. This is expected for local development. Accept the
> warning to continue.
>
> **Database configuration:** Ensure the database values in
> `src/main/resources/application.properties` match those defined in the
> repository `.env` file.

### Timezone configuration

On some systems, the backend may fail to start because PostgreSQL rejects a
deprecated JVM timezone identifier during startup.

If you encounter an error like:

```text
FATAL: invalid value for parameter "TimeZone"
```

See `../common_problems.md` for platform-specific troubleshooting and startup
guidance.

## Database migrations

Vault Web runs versioned PostgreSQL migrations with Flyway before Hibernate
validates the schema. The application fails startup if a migration or validation
fails. New databases are initialized automatically.

For the first upgrade of an existing Vault Web database without Flyway history:

1. Take a verified PostgreSQL backup and stop the old backend. Confirm that the
   target database is the Vault Web database and already has the current schema
   apart from the chat deletion flag. Apply the poll upgrade below first if it
   has not already been applied.
2. Start the upgraded backend once with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`
   in the backend process environment. With Docker Compose, pass it to the
   `vault-web-backend` service through a temporary Compose override.
3. Confirm successful startup, then remove the override. All subsequent starts
   use the recorded migration history.

This upgrade records the legacy schema as version 1 and backfills the `deleted`
flag for existing chat messages as version 2. Messages that already have a
deletion flag keep their current value. Add future schema changes as a new
versioned migration under `src/main/resources/db/migration`.

## Upgrading existing poll data

Before deploying the poll vote uniqueness fix to an existing database, stop all
backend instances and run [sql/pollVoteUniqueness.sql](sql/pollVoteUniqueness.sql)
against the application database. The script backfills `poll_votes.poll_id` from
each vote's option and adds the unique constraint on `(poll_id, user_id)`.
This manual upgrade is for databases that predate the poll vote uniqueness fix.
The Flyway baseline includes the column and constraint for new databases.

Check for existing duplicates before running the script:

```sql
SELECT option.poll_id, vote.user_id, COUNT(*) AS vote_count,
       ARRAY_AGG(vote.id ORDER BY vote.id) AS vote_ids
FROM poll_votes AS vote
JOIN poll_options AS option ON option.id = vote.poll_option_id
GROUP BY option.poll_id, vote.user_id
HAVING COUNT(*) > 1;
```

Resolve any duplicates according to the intended vote before applying the script.
The script runs in a transaction and fails if duplicates remain; it does not
delete votes. Apply it once, before restarting the backend with the new code.

## Running tests

Run the backend test suite using the Maven Wrapper:

```bash
./mvnw test
```

CI also runs `PostgresMigrationTest` against PostgreSQL 16. To run it locally,
set `VAULT_TEST_POSTGRES_URL`, `VAULT_TEST_POSTGRES_USER` and
`VAULT_TEST_POSTGRES_PASSWORD` for a disposable PostgreSQL database. The test
creates and removes isolated schemas in that database.

## Package structure

The backend source code is organized into the following packages:

```text
src/main/java/vaultWeb
├── config
│   └── websocket        # WebSocket configuration
├── controllers          # REST API controllers
├── dtos
│   ├── dashboard        # Dashboard-related data transfer objects
│   └── user             # User-related data transfer objects
├── exceptions
│   └── notfound         # Exception handling for missing resources
├── models
│   └── enums            # Application enums
├── repositories         # Database access layer
├── security
│   ├── annotations      # Security-related annotations
│   ├── aspects          # Security aspects
│   └── exception        # Security exception handling
└── services
    └── auth             # Authentication-related services
```
