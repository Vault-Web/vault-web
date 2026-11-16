# Project workspace rollout and rollback

## Scope and permissions

Projects are private metadata in four additive `workspace_*` tables. Existing
users, Cloud bytes, shares, groups, tokens and chat history keep their contracts.
Owners manage all roles/delete; admins manage metadata, references and ordinary
members; members read and leave. Owners cannot all leave or demote themselves:
membership mutations acquire the same database project-row lock. Archive blocks
ordinary edits/member/reference changes; reopen, leave and owner deletion remain
available. At most 100 members and 50 references per project; activity returns the
latest 50 events. Activities record project/member/link/discussion-reference
changes, not Cloud edits or messages sent outside the project API. Future task,
wiki/calendar/automation tables can reference project IDs and reuse these checks.

References are canonical owner-root-relative paths, not stable filesystem IDs.
Moves/deletes yield unavailable references until an admin relinks them. Path reuse
can point to a replacement: do not treat project links as immutable documents.
Only resources in the linking caller's own Cloud storage can initially be linked.
Other members need an existing **exact-resource** Cloud share with `VIEW`; ancestor
folder shares/subpath expansion are not yet supported. Each overview resolves
references against Cloud using the requesting user's actual Authorization header,
never the owner's credentials or an automatically minted token. Unavailable
references return only a link ID, without owner/path/type/share metadata. Cloud
must still authorize every subsequent read; project membership grants no access.
Cloud resolution errors, absence, revocation and downtime fail closed. File actions
save bytes with an inert download type; folders show the accessible page count.
Use the existing Cloud page for full editing/navigation.

An optional discussion references an existing group. The setter must belong to
it; overview returns its ID only to existing group members and the existing chat
API independently checks membership. Project deletion never deletes that group.
PATCH applies supplied metadata fields independently: omitted/null fields preserve
stored values, an empty description clears it, and `clearDiscussion: true` explicitly
removes a discussion reference. Renaming by an admin outside the discussion group
preserves its hidden ID. Setting a new ID and clearing it together is rejected.

## Prerequisites and compatibility

1. Human merge order: portal PR #420 contains the previously diagnosed baseline
   security-context test repair. Do not duplicate it or claim unrelated PRs' remote
   backend CI green until the prerequisite is integrated and revalidated.
2. Inventory deployed backend/frontend and Cloud API revisions. Confirm existing
   `/api/folders/path`, `/api/files/view` (Range supported), and received-share,
   shared-view/content endpoints. Test owner, recipient, revoked share and offline
   cases with synthetic staging accounts. No gateway adoption is required.
3. Take a protected PostgreSQL backup and record old image/revision references.
   Test restoring that backup in staging. Project tables contain private metadata.
4. Apply `docs/migrations/project-workspaces.sql` manually in staging first, then
   production during a maintenance window using the authorized operator process.
   Confirm no tables already exist; the script deliberately fails instead of
   silently accepting an incompatible partial schema. Transaction failure rolls
   back the complete addition. Do not rely on Hibernate `ddl-auto=update` as the
   migration procedure; existing runtime settings are unchanged by this PR.
5. Deploy backend before frontend. Old frontends ignore new endpoints; old backends
   ignore additive tables. Keep a single backend revision while enabling projects.
   Simultaneous metadata edits are last-write-wins; membership owner safety is
   serialized across instances by PostgreSQL row locks.
6. Verify private permissions, concurrent last-owner changes, archive/reopen,
   unavailable links, independent chat membership and existing login/Cloud flows.

No migration/deployment is automatic or claimed performed on production.

## Failure recovery and rollback

Before frontend activation, a failed migration transaction leaves old data intact.
If backend startup/verification fails after migration, restore the old backend
image and keep the added tables. Roll frontend back first, then backend. Old
applications must continue reading existing synthetic users/groups unchanged;
the disposable Linux PostgreSQL migration regression checks old/new JPA startup,
old rows and retained new tables during rollback. Retain project tables and their
backups so re-upgrade preserves project state. **Do not drop tables automatically.**
Deleting a project is permanent for project metadata; recovery requires a protected
backup/staging extraction, not application rollback. If later removing the feature
entirely, stop project writers, export/back up the four tables, and have an operator
review explicit resource/activity/member/project drop statements in that order.
Cloud files and groups never participate in a destructive project cascade.

## Local Linux checks

Run Maven `spotless:check verify` with the test profile; focused integration tests
cover private membership, roles, archive, deletion and concurrent last-owner safety.
Run frontend npm ci, Prettier, spec typecheck, production build, project UI/service
regressions and current Cloud CI regressions. Run the pinned OpenAPI fixture.
Run the disposable PostgreSQL old-schema/additive-schema/new-app/old-app rollback
validation. No production credentials or data belong in any of these checks.
