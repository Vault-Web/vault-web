# Cloud file save contract

The editor distinguishes creating a file from deliberately editing an existing file.

| Action                                | HTTP request                 | If-Match |
| ------------------------------------- | ---------------------------- | -------- |
| Create or upload an own file          | POST /api/files/upload       | omitted  |
| Edit an own file                      | POST /api/files/upload       | \*       |
| Create or upload into a shared folder | POST /api/shares/{id}/upload | omitted  |
| Edit an existing shared file          | PUT /api/shares/{id}/edit    | \*       |

Shared edits use the original share-relative path and filename. An individually shared file uses an empty path. Normal uploads and new editor files must never opt into replacement.

## Backend dependency and rollout

The duplicate-name rejection is implemented by [cloud-page PR #186](https://github.com/Vault-Web/cloud-page/pull/186), not by this frontend change. The current backend main branch still permits replacement on an upload without If-Match.

Deploy this frontend before enabling the backend duplicate-upload rejection. Otherwise an older frontend can receive conflicts when saving existing own files or files in shared folders. Do not mark [cloud-page issue #134](https://github.com/Vault-Web/cloud-page/issues/134) resolved until the backend fix is deployed and the combined behavior is verified.

If-Match: \* is an existence precondition for an intentional replacement. It does not compare versions and does not detect another user's intervening edits. Optimistic concurrency would require reading, retaining and submitting a concrete ETag and is outside this duplicate-upload fix.

## Automated regression checks

Run from frontend:

```sh
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/cloud/cloud-file-save.spec.ts
```

These tests use the real CloudService and ResourceShareService with Angular's HttpTestingController. They verify editor routing, multipart data, conditional headers, success messages, rename ordering and retention of unsaved content on 409/412 failures. Frontend CI runs this contract suite independently of the known failures in unrelated legacy tests. HTTP mocking does not prove server-side preservation of file contents.

## Combined API acceptance check

Against the backend containing PR #186, use disposable files and verify:

1. Create a new own file, edit it and read it back.
2. Upload different content with the same filename without If-Match: expect 409 and verify the original bytes are unchanged.
3. Repeat the conflict check when creating a file through the editor.
4. Edit an existing file in a shared subfolder and an individually shared file with VIEW and EDIT permissions.
5. Upload a duplicate into a shared folder: expect 409 and verify the owner's original bytes are unchanged.
6. Remove a target after opening it, then save: expect a failed precondition and preserve the editor's unsaved content.
7. Verify a revoked share or a recipient without EDIT cannot modify the owner's file.

The frontend and backend changes can be reviewed separately, but the issue is complete only after this combined check passes. Do not interpret a green frontend build or mocked HTTP test as evidence that the backend is deployed.
