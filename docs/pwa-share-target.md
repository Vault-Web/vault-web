# Installable app and mobile share handoff

Build with `cd frontend && npm ci && npm run build`. The post-build script emits
`vault-worker.js` with a content-derived cache version and an explicit allowlist
of hashed bundles, the static index, manifest, icons and share-storage helper.
Serve the entire `dist/frontend/browser` directory on the existing portal origin.
The root-path deployment contract is unchanged. HTTPS (or localhost for tests)
is required. Keep the VPN-first access model and existing API authorization.

In supporting Chromium browsers, install from the browser menu. Open the installed
app online once so its service worker controls subsequent share navigations.
Android Chromium can then receive supported files, titles, text and URLs through
the system share sheet. Platform/browser support varies; iOS share-target receipt
is not supported by this implementation. Normal browser Cloud uploads remain
available. No custom install banner or native app integration is required.

## Privacy and confirmation

Only static app-shell assets are cached. Runtime configuration, authenticated API
responses, public share responses and uploaded content are always network-only.
Offline navigation may show the shell; Cloud operations still require connectivity.
No full offline editing or background upload is provided.

Shares use a POST intercepted by the worker, never URL-encoded private content.
The worker limits the multipart body to 51 MiB before parsing and the files to
10 / 50 MiB. Text fields are limited to 100,000 characters each. It retains only
the newest draft in a dedicated same-origin IndexedDB store, with a 30-minute
expiry. Expired records are removed on access/worker activation, or by the visible
handoff timer; storage may remain physically on disk until the app next runs.
Site-storage clearing/uninstallation removes drafts. A second share replaces the
stored draft; an already-open review retains its in-memory copy until its expiry.

The handoff is accessible while signed out, and offers a constrained login return
to the same opaque draft ID. It shows the current account and folder destination.
Only explicit confirmation starts uploads using the existing CloudService and
server-side permissions/quota checks. Files receive unique `shared-<draft-id>-`
names; titles, text and URLs become a literal `.txt` note, with no HTML rendering
or automatic external navigation. Partial failures retain the draft and list
completed uploads; retries keep the same destination and skip completed files.
Account changes require a new share, including changes during folder requests.
Temporary storage retains destination ownership/path and completed filenames so
failed refresh/login can return to the draft and resume only remaining uploads.
Discarding a draft never deletes Cloud files.

## Rollout and rollback

No database/API/token migration or gateway adoption is needed. This introduces
browser-local temporary storage and an origin-wide worker. Before rollout, back
up the previous frontend artifact and verify the static server serves the worker
as JavaScript, the manifest and icons directly, and navigation fallback as index.
Do not cache `vault-worker.js` or `runtime-config.local.js` at the proxy/CDN; worker
registration uses `updateViaCache: none`. Deploy all generated assets atomically,
keeping the preceding hashed bundles while older tabs are open. Existing tabs
retain their old worker until closed; new tabs eventually receive the new shell.
Verify existing login, public shares and Cloud uploads before general installation.

To roll back, restore the previous artifact **and retain a retirement worker at
`/vault-worker.js`** (copy `frontend/scripts/retire-vault-worker.js` there). It must activate (`skipWaiting`), delete only
`vault-web-shell-*` caches, delete `vault-web-share-drafts`, and unregister itself.
Notify installed users to close/reopen tabs or clear this site's storage; merely
removing the worker file does not remove an installed worker or offline shell.
Pending drafts will be lost on retirement; completed server uploads are unaffected.
Do not delete unrelated origin storage/authentication. No production rollout or
rollback has been executed by these tests.

## Verification

Automated: build/format/spec typecheck, share-component regressions, real Chromium
service worker installation, multipart file/text receipt (including `@` and `^`
in filenames), confirmed uploads preserving file bytes, temporary draft expiry,
static offline shell and network-only API/runtime configuration checks via
`node scripts/test-pwa.mjs` after `npm run build`. Run the existing Cloud regression
contract and pinned OpenAPI fixture as well.

Manual before rollout: on Android Chromium over the normal VPN/HTTPS origin,
install, close/reopen in standalone mode, exercise Cloud/receive shortcuts, share
an image and PDF from another app, and share a URL/text. Check signed-out login
return, nested folder selection, discard, quota/error retry, offline receipt then
online upload, expired draft, and a second share. Verify icon masking on the home
screen and task switcher. Repeat desktop Chromium installation; verify ordinary
login/Cloud on browsers without share-target support. These real-device checks
are rollout prerequisites, not claimed automated results.

Reference: [MDN share_target](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Manifest/Reference/share_target)
(POST multipart handling and browser support, consulted 2026-11-16).
