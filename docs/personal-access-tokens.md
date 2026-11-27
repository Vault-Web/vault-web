# Personal access tokens (PATs)

Scoped, expiring, revocable credentials for CLI tools, backup clients and personal
integrations. Use them instead of reusing a browser session: a PAT reaches only the
operations its scopes allow, and it has its own lifecycle separate from interactive
sessions.

Issue: #407. Cloud provider work: Vault-Web/cloud-page#191.

## Token format

```
vw_pat_<publicId>_<secret>
```

| Part       | Details                                                                  |
|------------|--------------------------------------------------------------------------|
| `vw_pat_`  | Fixed prefix, so leaked tokens are easy to spot and scanners can find them |
| `publicId` | 12 random base62 characters. A non-secret lookup id that never authorizes anything on its own |
| `secret`   | 32 bytes from `SecureRandom`, base64url without padding (43 chars)       |

- Only a SHA-256 digest of the full token is stored (`personal_access_token.token_hash`).
  The raw token appears exactly once, in the `201` creation response, which is sent with
  `Cache-Control: no-store`.
- Verification looks the token up by `publicId` and then compares digests in constant time.
  An unknown id is still compared against a dummy digest, so timing doesn't reveal which
  ids exist.
- Send it only as `Authorization: Bearer vw_pat_...`. Tokens are never accepted in query
  strings or cookies, and never on WebSocket/STOMP.

## Settings page

Signed-in users manage tokens at **Access Tokens** (`/access-tokens`): create a
named token with scopes, a 7/30/90/365-day expiry and the current password; copy the
secret from a one-time dialog (it is cleared from memory when the dialog closes);
list metadata (scopes, created, expires, last used, status) and revoke.

## Lifecycle API (interactive session required)

These endpoints all require a browser JWT. Requests made with a PAT are refused
(`403 INSUFFICIENT_SCOPE`), so **a PAT can never create, list or revoke PATs**, and it
cannot obtain refresh tokens or sessions either.

| Method & path                    | Purpose                                                                 |
|----------------------------------|-------------------------------------------------------------------------|
| `GET /api/auth/tokens/scopes`    | Supported scopes and the endpoints each one unlocks                     |
| `GET /api/auth/tokens`           | The caller's tokens (metadata only: no secret, no digest)               |
| `POST /api/auth/tokens`          | Create a token: `{name, scopes[], expiresInDays, currentPassword}`      |
| `DELETE /api/auth/tokens/{publicId}` | Revoke one of the caller's tokens (idempotent; another user's id returns `404`) |

Rules on creation:

- **Re-authentication:** `currentPassword` must match. A wrong password returns `401` and is
  recorded as a failed `PAT_CREATED` event.
- **Expiry is required:** `expiresInDays` must be 1–**365**. Tokens that never expire are
  not allowed.
- At most **20 active tokens** per user. The name is 1–64 characters.
- Scopes not in the table below are rejected with `400`, and that includes every `cloud:*`
  scope.

## Scope / endpoint matrix

Anything not listed is **denied for PAT callers** (`403 INSUFFICIENT_SCOPE`). That covers
routes that are otherwise public, plus admin operations, chat and groups, E2EE device keys,
polls, media and token lifecycle. The source of truth is
`vaultWeb.security.pat.PatEndpointRegistry`; keep this table in sync with it.

| Scope           | Allowed operations                                           |
|-----------------|--------------------------------------------------------------|
| `profile:read`  | `GET /api/dashboard/me`, `GET /api/auth/profile-picture`     |
| `security:read` | `GET /api/auth/security-activity`                            |

Each request still goes through the endpoint's normal ownership checks (for example,
"current user" resolution), so a PAT only ever acts as its owner.

Scopes that aren't available yet:

- `projects:read` / `projects:write`: not offered yet. Project endpoints (`/api/projects/**`)
  now exist but are denied for PATs until these scopes are defined in a follow-up.
- `cloud:read` / `cloud:upload` / `cloud:write`: **not supported**. See
  [Cloud](#cloud-not-supported-yet).

## Revocation, expiry and account changes

- Every PAT request checks the token against the database, with **no caching**. Revoking or
  expiring a token therefore rejects the very next request. If a verification cache is ever
  added, revocation must evict from it, and the tests in
  `PersonalAccessTokenIntegrationTest` must keep passing.
- **A password change revokes all of the user's PATs**, recorded as a `PAT_REVOKED_ALL`
  event.
- **Disabled accounts:** the `User` model has no enabled/locked state today, so this case
  doesn't arise yet. When one is added, `PersonalAccessTokenService.authenticate` must
  reject disabled owners.
- Every failure (malformed, unknown, wrong secret, revoked, expired) returns the same
  generic `401`.

## Throttling and last-used tracking

- A valid PAT is rate-limited per token (`app.pat.rate-limit-per-minute`, default 60) and
  per owner (`app.pat.owner-rate-limit-per-minute`, default 120). Over the limit it gets
  `429` with `Retry-After`. The limiter keys come from the *verified* token, so a client
  cannot spoof them. Invalid tokens still count against the global per-IP limiter.
- `lastUsedAt` is updated at most once every 5 minutes per token, with a conditional UPDATE,
  to keep writes bounded.

## Audit

The events `PAT_CREATED` (SUCCESS/FAILURE), `PAT_REVOKED` and `PAT_REVOKED_ALL` appear in
the security activity log. Events never include the token or its digest.

## Cloud (not supported yet)

Cloud Page validates JWTs on its own and doesn't accept PATs. Core **does not** turn a PAT
into a browser JWT, and doesn't forward PATs to Cloud. Before any `cloud:*` scope can be
enabled, core and cloud-page#191 have to agree on:

1. **Where opaque tokens are verified.** Options are a core introspection endpoint or a
   shared verifier, and either way it must not be a JWT-only filter.
2. **What reaches the resource server:** audience (`cloud`), scopes and principal.
3. **How expiry and revocation propagate,** including any provider-side cache TTL.
4. **Exact `upload`, `write` (overwrite) and `delete` semantics.**

Until then, the `audience` column is always `core`, and tokens with any other audience are
rejected.

## Rollout and rollback

1. Take a protected PostgreSQL backup. Apply `docs/migrations/personal-access-tokens.sql`
   manually in staging first, then in production. The script is additive (two new tables,
   no changes to existing ones) and fails instead of accepting a partial schema if the
   tables already exist. Do not rely on Hibernate `ddl-auto=update` as the migration
   procedure; runtime settings are unchanged by this feature.
2. Deploy the backend before the frontend. Old frontends never call `/api/auth/tokens`;
   old backends ignore the new tables.
3. Verify: create a token, call `GET /api/dashboard/me` with it, confirm a denied route
   returns `403 INSUFFICIENT_SCOPE`, revoke it, and confirm the next call returns `401`.
   Check that browser login, refresh and chat behave as before.

Rollback: roll back the frontend first, then the backend. Old backends do not read the
token tables, so outstanding tokens simply stop working. **Do not drop the tables
automatically**: keep them (and their backup) so a re-upgrade keeps users' token metadata.
To remove the feature entirely, have an operator drop `personal_access_token_scope` and
then `personal_access_token`. These tables hold only digests, never raw tokens.
