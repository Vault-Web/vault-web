# Generated API clients

The frontend keeps generated API clients separate from handwritten application
services. Cloud generation is the first slice; a future core client belongs in
its own namespace and is not part of this change.

## Cloud contract source

The provider-owned, revision-addressable OpenAPI export is tracked by
[Vault-Web/cloud-page#192](https://github.com/Vault-Web/cloud-page/issues/192).
That export is not available yet, so this branch intentionally does not contain
a fabricated schema, generated DTOs, or a migration of the folder-listing flow.

When the provider export is published, add the exact immutable provider
revision and spec path (or immutable artifact plus digest) here and place the
export at:

`src/app/api/contracts/cloud/openapi.yaml`

Do not use a mutable `main`/`latest` URL or a live production
`/v3/api-docs` endpoint as a build input. Contract changes must be reviewed
and pinned as part of the consumer update.

## Toolchain

- Generator: OpenAPI Generator `typescript-angular`
- Generator image: `openapitools/openapi-generator-cli:v7.26.0`
- Angular target: `19.2.0`
- TypeScript target: `5.7.2`
- Zone.js target: `0.15.0`

The image tag and generator options are committed/pinned in this repository.
Docker is required to run the generator locally and in CI.

## Commands

From `frontend/`:

- `npm run api:generate` regenerates the checked-in Cloud client.
- `npm run api:check` regenerates into a temporary directory and fails if the
  checked-in generated output differs.

Both commands require the provider contract at the path above. The check must
be added to frontend CI together with the first immutable contract pin, so the
consumer CI never depends on an unversioned provider endpoint.

## Integration rules

- Generated sources live under `src/app/api/generated/cloud`; do not edit
  generated files manually.
- Keep `CloudService` as the handwritten application wrapper.
- Use Angular `HttpClient` and the existing interceptor chain. Never attach
  credentials to arbitrary provider URLs.
- Keep the base URL deployment-configurable through the existing environment
  configuration.
- Migrate only the folder-listing request and its DTOs in the first slice.
  Preserve path normalization, pagination, cancellation and established error
  behavior; leave unrelated Cloud endpoints and the core API untouched.
- A generated TypeScript client catches consumer compile-time drift, but does
  not alone prove backend backward compatibility. Provider and consumer
  contract updates must be coordinated and reviewed together.
