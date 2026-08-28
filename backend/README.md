# Backend

## Responsibilities

The backend provides the core services for the Vault Web ecosystem. It is a
Spring Boot application responsible for:

- user authentication and JWT-based security
- user and session management
- chat, groups, polls, and collaboration features
- REST APIs consumed by the frontend
- persistence using PostgreSQL

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
