# Generated API clients

The frontend keeps generated API clients separate from handwritten application
services. Cloud generation is the first slice; a future core client belongs in
its own namespace and is not part of this change.

## Cloud contract source

Cloud Page publishes a revision-addressable OpenAPI export
([provider PR #216](https://github.com/Vault-Web/cloud-page/pull/216)).
The consumer integration and migration of the folder-listing flow are tracked in
[Vault Web #408](https://github.com/Vault-Web/vault-web/issues/408).

For that integration, add the exact immutable provider
revision and spec path (or immutable artifact plus digest) here and place the
export at:

`src/app/api/contracts/cloud/openapi.yaml`

Do not use a mutable `main`/`latest` URL or a live production
`/v3/api-docs` endpoint as a build input. Contract changes must be reviewed
and pinned as part of the consumer update.

## Toolchain

- Generator: OpenAPI Generator `typescript-angular`
- OpenAPI Generator CLI and generator release: `7.26.0`
  (`openapitools/openapi-generator-cli@sha256:a304ddf1e2e5f24f68fa3153568d6174cea4959d09aa8e3db6d526fd0782326d`; multi-platform image-index digest).
- Angular target: `21.2.26`
- TypeScript target: `5.9.3`
- RxJS target: `7.8.2`
- Zone.js target: `0.15.0`

The Docker image is pinned by its immutable multi-platform OCI image-index
digest, not by a mutable tag. The index digest resolves to the platform-specific manifests while keeping the
selected image content fixed. The generator options are committed in
`openapi/cloud-generator-config.yaml`. Docker is required to run generation
locally and in CI.

## Commands

From `frontend/`:

- `npm run api:generate` regenerates the checked-in Cloud client.
- `npm run api:check` regenerates into a temporary directory and fails if the
  checked-in generated output differs.

The fixture regression test generates into an isolated temporary output
directory; it never removes or rewrites the checked-in generated client. Both
commands require the provider contract at the path above. The check must
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
