# Chat WebSocket authentication rollout

Chat now authenticates the STOMP `CONNECT` frame using its existing
`Authorization: Bearer <access token>` header. The SockJS HTTP handshake is
transport setup only; it grants no messaging identity. Missing/invalid credentials
are rejected before broker subscriptions and application messages. Existing
group-membership checks still run after authentication. Origin restrictions stay
in effect. Clients must not place access tokens in URLs.

## Compatibility and prerequisites

- JWT keys, token format, HTTP login/refresh APIs and persistent data do not change.
- The previous portal already sends the STOMP Authorization header, including on
  reconnect. It works with the new backend, though its old URL still exposes tokens.
- The new portal cannot connect to the old backend, which requires handshake
  credentials. Upgrade the backend first, then the frontend; refresh old browser
  tabs to complete the removal of query tokens.
- Inventory custom chat clients. Clients using only query/header authentication at
  the HTTP handshake must add the STOMP header before rollout. Do not upgrade until
  those clients have been verified. HTTP handshake identity is no longer sufficient.
- Keep VPN-first exposure and TLS. Transport setup is unauthenticated, so existing
  proxy connection/resource limits still matter. This change does not introduce
  cookies, change access-token session lifetime, or adopt the separate gateway.

## Operator sequence and recovery

1. Record the running backend/frontend revisions and images and keep their rollback
   artifacts. Retain normal database/files backups; no data conversion is required.
2. In a disposable Linux environment, verify old frontend-style CONNECT headers
   against the new backend, new token-free clients over WebSocket and SockJS XHR,
   missing/malformed/expired credentials, private messaging and group isolation.
3. Upgrade backend instances first. Confirm chat reconnect and login still work.
4. Upgrade frontend assets and refresh browser tabs. Inspect sanitized request URLs
   for absence of `token` parameters; never capture actual bearer credentials.
5. If chat fails, stop rollout. Restore old frontend assets **before** restoring old
   backends, or restore the pair together during a maintenance window. New frontend
   with old backend is unsupported. No database rollback is needed for this change.

Old access logs may already contain credentials; this change cannot erase their
historical exposure. Retain operator log-access/retention policy. No production
rollout or production-client verification is claimed by repository tests.

The Linux integration suite uses synthetic existing-format JWTs and exercises
both real SockJS WebSocket and XHR transports. Unit regressions cover expired and
wrongly signed tokens and unauthenticated SEND/SUBSCRIBE. See Spring's
[STOMP token authentication guidance](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html).
