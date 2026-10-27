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

## Upgrading existing poll data

Before deploying the poll vote uniqueness fix to an existing database, stop all
backend instances and run [sql/pollVoteUniqueness.sql](sql/pollVoteUniqueness.sql)
against the application database. The script backfills `poll_votes.poll_id` from
each vote's option and adds the unique constraint on `(poll_id, user_id)`.
Hibernate's `ddl-auto=update` cannot backfill this required column. New databases
get the column and constraint from the entity mapping.

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
