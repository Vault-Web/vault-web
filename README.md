# Vault Web

Vault Web is a self-hosted dashboard for authentication, communication, and
connected services. This repository contains the Spring Boot backend and Angular
frontend. Production deployment is maintained separately.

## Local development

### Requirements

- Docker with Docker Compose for PostgreSQL and pgAdmin
- Java 21 or later for the backend
- Node.js and npm for the frontend

Clone the repository and start the local database services:

```bash
git clone https://github.com/Vault-Web/vault-web.git
cd vault-web
docker compose up -d
```

The repository includes a `.env` file for Docker Compose. Before starting the
backend, ensure its database settings in
`backend/src/main/resources/application.properties` match the values in `.env`.
PostgreSQL is available on `localhost:<DB_PORT>` and pgAdmin at
<http://localhost:8081>.

For HTTPS development, set `useHttps = true` in
`frontend/src/environments/environment.ts`, then run the backend and frontend in
separate terminals:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

```bash
cd frontend
npm install
npm start
```

The frontend is then available at <https://localhost:4200>. The development
certificate is self-signed, so your browser may show a certificate warning.

For HTTP development, tests, and build commands, see the
[backend](./backend/README.md) and [frontend](./frontend/README.md) guides.
For setup issues, see [common problems](./common_problems.md).

## Deployment

Production uses the separate [deploy repository](https://github.com/Vault-Web/deploy),
which contains the Docker Compose stack and service submodules. Its README covers
host requirements, environment configuration, TLS and routing, backups, and
ongoing operations.

On a host prepared as described there, clone the stack into `/opt/deploy` and
create its environment file:

```bash
cd /opt
git clone --recurse-submodules https://github.com/Vault-Web/deploy.git
cd deploy
cp -n .env.example .env
```

Set the required values in `.env` as documented in the deploy guide, then start
and check the stack:

```bash
docker compose -f docker-compose.deploy.yml up -d --build
docker compose -f docker-compose.deploy.yml ps
```

The `docker-compose.yml` in this repository starts only the local PostgreSQL and
pgAdmin services; use the deploy repository for the production stack.

## Contributing

See the [Vault Web contributing guide](https://github.com/Vault-Web/.github/blob/main/CONTRIBUTING.md)
for the contribution workflow. In this repository, `good first issue` and
`help wanted` identify work open to contributors. Issues marked `agent-ready`
may be handled by an automated agent; agent pull requests require maintainer
review before merging.

## License

See [LICENSE](./LICENSE).
