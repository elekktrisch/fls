---
id: J-13
title: Audit-log viewer (/system/logs)
epic: E-06
status: in_progress
started_at: 2026-07-20
journey0: false
carved: true
depends_on: [J-0]
rolls_up: [S-056, S-160]
acceptance:
  - "[happy] A club-admin opens /system/logs; a mutation performed earlier in the run appears as a row with action, target entity type, actor, occurredAt timestamp, and HTTP status."
  - "[happy] Filtering by action (e.g. UPDATE) and by target entity type narrows the list to matching rows; clearing filters restores the full list."
  - "[happy] Time-range filter (occurredFrom / occurredTo) narrows the list to events in range."
  - "[happy] Pagination: with more events than one page, advancing fetches the next offset (hasMore / nextOffset cursor); default page size is 50."
  - "[happy] Expanding a row shows the before/after state payload (diff for UPDATE, after-only for CREATE, before-only for DELETE)."
  - "[key-error] A plain PILOT is denied the screen (guard redirects home), the nav entry is absent, and the endpoint returns 403."
  - "[edge] Tenant isolation: a club-A admin sees only club-A audit events, never club-B's (structural @TenantId) — asserted at the real-idp two-club gate."
  - "[key-error] Append-only (S-160): an UPDATE against t_mutation_audit_event as the app DB role fails permission denied — proven by a server IT (skip-with-fail-loud if the test infra can't split roles)."
screen: /system/logs   # replacing legacy flsweb system/logs/
headless_pulled_in: none new — the audit read-endpoint (S-027) already exists; the append-only DB-role split (S-160) homes on this screen's gate as a server IT
migration: N/A — audit trail is app-generated (live events); legacy SystemData (SMTP/base-URL) → infra config, not a domain entity. Audit-history import (legacy AuditLogs → LEGACY_MIGRATED) is J-21's cutover-importer job, not this journey.
parity_test: alpenflight/web/e2e/tests/system/audit-log.spec.ts   # + real-idp two-club gate spec
adr_refs: [0007, 0008, 0022]
---

## Tasks

- [x] **T-01 — spec stub + J-13 proof-gallery scaffold.** Author `web/e2e/tests/audit-logs/audit-logs-list.spec.ts` stub (mock `/api/v1/admin/audit-events`; testids + flow, thin assertions committing the screen shape: table, row, filter controls, row-detail region). Scaffold the per-journey gallery page for J-13 + link it from the persistent index (glanceable slot from task 1). [NG8113-DEADIMPORT] already fixed in tree — delete its `_BOYSCOUT.md` bullet only.
- [x] **T-02 — scope per-push gate to J-13 (real-idp); prior journeys mock-IdP.** Verify current `ci.yml` scoping first (J-29 shipped real-idp/nightly fully-green). Only J-13's own spec runs the heavy real-idp lane per-push; prior journeys mock-IdP; full cross-journey real-idp stays nightly + §4 gate. Fold [MAINTAINABILITY-TOOLING — Qodana baseline backfill] only if a CI cycle allows.
- [x] **T-03 — audit-logs feature scaffold (route + guard + nav + store).** `features/audit-logs/audit-logs.routes.ts` (`/system/logs`, `clubAdminGuard`, showNavBar) + lazy-load into `app.routes.ts`; nav entry in `nav-sections.ts` `MASTERDATA_CLUB_ADMIN_ITEMS` + `MASTERDATA_PATHS` (`_helpers/nav.ts`); `audit-logs.store.ts` Signal Store over `AuditEventsService.listAuditEvents` (filter + cursor state {pageOffset, pageSize:50, items, hasMore, nextOffset}, loading/error); minimal `list/audit-logs-list.page.ts` shell.
- [x] **T-04 — audit-logs list table.** `audit-logs-list.page.ts` renders `af-data-table` (`total=null`, custom cursor pagination — Next gated by `hasMore`, advance `pageOffset += pageSize`, page size 50) with columns: action, target entity type, actor, occurredAt, HTTP status; loading + empty states.
- [x] **T-05 — filter controls.** Action select (`AuditAction` enum), target-entity-type, time-range (occurredFrom/occurredTo) wired to store; applying narrows, Clear restores full list.
- [x] **T-06 — row-detail expansion.** Expandable row → before/after payload: field diff for UPDATE, after-only for CREATE, before-only for DELETE. Small detail sub-component + page wiring + testids.
- [ ] ~~**T-07**~~ (split — 10 seams, ops-sensitive) →
- [x] **T-07a — S-160 DB-role split (infra + config + CI + prose).** Keep existing `alpenflight` role as MIGRATOR; add `alpenflight_app` (broad DML; INSERT,SELECT-only on `t_mutation_audit_event`, no UPDATE/DELETE) via new `V54__*` migration (grants + REVOKE + DEFAULT PRIVILEGES for future tables; app-role password via Flyway placeholder, not hardcoded). Boot the app on the app role (main datasource) while Flyway runs as migrator (`spring.flyway.{url,user,password}`); wire `docker-compose.yml` + `ci.yml` (Flyway-as-migrator, app-boot-as-app) + `application-dev/prod.yml`. Helm/k8s N/A (none exist). Drop "deferred" from S-027 threat-row (d) (`implemented/S-027-*.md:70-73`).
- [ ] **T-07b — S-160 append-only proof IT (real, not skipped).** Provision the second app-only role in `PostgresTestContainerLifecycle` (container mode); server IT opens a connection AS the app role, `UPDATE t_mutation_audit_event` → assert `permission denied` (SQLState 42501). Must run FOR REAL in CI (security seam — [[feedback_safety_claim_needs_negative_test]]); skip-with-fail-loud only as the local-external-PG fallback, citing S-160.
- [ ] **T-08 — S-104 endpoint role-matrix test.** Server test on `/api/v1/admin/audit-events`: PILOT → 403, CLUB_ADMINISTRATOR → 200, SYSTEM_ADMINISTRATOR → 200. This endpoint only.
- [ ] **T-09 — thicken e2e + real-idp two-club gate (e2e-driver).** Spec performs a real mutation first (live audit event), then opens `/system/logs` and asserts row fields, action + target-type filters (+ clear), time-range, cursor pagination (default 50, advance via nextOffset — seed >50 events server-side if needed), row-detail diff (UPDATE/CREATE/DELETE). Author real-idp two-club tenant-isolation + pilot-denied gate (guard redirect + nav absent + 403). Paired legacy↔AlpenFlight screenshots + pass video → J-13 gallery. [REALIDP-FLAKE-QUARANTINE] — verify current gate state; quarantine only what still flakes.

## Context

The mutation-audit trail (`t_mutation_audit_event`, S-027) already has a fully-built read
endpoint (`GET /api/v1/admin/audit-events`) and a generated TS client — but **no UI**. J-13
ships the admin-facing audit-log viewer: the diagnostics surface that replaces the legacy
log4net "system logs" screen, a semantic upgrade to real who-changed-what forensics rather
than log-file dumps. Club-admins get the first user-facing window on their tenant's mutation
history.

## Spec must assert

Happy path + error cases (frontmatter ACs), grounded in the existing contract:

- **Endpoint** — `AuditAdminController` `GET /api/v1/admin/audit-events`
  (`server/.../audit/web/AuditAdminController.java:29-57`). Optional filters:
  `occurredFrom`, `occurredTo`, `action` (`AuditAction` enum), `targetEntityType`,
  `actorUserId`, `pageSize` (default 50), `pageOffset`. Returns `AuditEventPage`
  = `{ items: AuditEventRow[], hasMore, nextOffset }` (`AuditQueryService.java:40-57`).
  Row: `id, occurredAt, actorUserId, actorKeycloakSub, tenantClubId, action,
  targetEntityType, targetEntityId, requestId, beforeState(map), afterState(map),
  failed, systemActor, httpStatus, failureReason` (`AuditQueryService.java:59-77`).
- **Role gate** — `@PreAuthorize hasAnyRole('CLUB_ADMINISTRATOR','SYSTEM_ADMINISTRATOR')`
  (`AuditAdminController.java:31`), per-tenant via Hibernate `@TenantId`. Cross-tenant
  SYSTEM_ADMINISTRATOR aggregation is deferred to S-023 `UnscopedTenantContext` (homed on
  J-14), so J-13 ships the **club-admin own-tenant** view; a pure-sysadmin principal (no
  clubId claim) is out of scope this journey.
- **Generated client** — `AuditEventsService.listAuditEvents(params)`
  (`web/src/app/api/generated/audit-events/audit-events.service.ts`, explicit
  `operationId=listAuditEvents` → stable naming, no positional-`getN` rider trigger).
- **Legacy parity (do NOT port)** — legacy `system/logs/` (`flsweb/src/system/logs/`)
  rendered a flat log4net `SystemLog` table (LogId/EventDateTime/LogLevel/EventType/
  Logger/Message/UserName) via `GET /api/v1/systemlogs/overview`. S-056 intentionally
  **replaces** that with the mutation-audit trail — the log4net table is not migrated.
- **Append-only (S-160)** — split `alpenflight_migrator` (DDL+DML, Flyway) / `alpenflight_app`
  (DML) DB roles; grant the app role only `INSERT, SELECT` on `t_mutation_audit_event`; an
  IT asserts an `UPDATE` as the app role → `permission denied` (skip-with-fail-loud if the
  Testcontainers infra can't split roles — S-160 AC).

## Notes

- **No design-reference screen exists** for system/logs (`design-reference/` has
  entry/home/logbook/reservations/misc/public only; `screens-misc` = Aircraft + Members).
  Build to ADR 0024 — the `<af-data-table>` organism (sortable + paginated + filterable) is
  the natural host; sentence-case, slate neutrals, sharp radius.
- **Nav placement** — club-admin surface → append the `/system/logs` entry to
  `MASTERDATA_CLUB_ADMIN_ITEMS` (`nav-sections.ts:21-28`), like Users / Accounting rules
  (all CLUB_ADMINISTRATOR-gated). Route gate: `tenantRequiredGuard` + club-admin — **not**
  `sysadminGuard` (audit is a club-admin own-tenant surface, not a cross-tenant one).
- **SystemData decision** (roadmap named `migration: SystemData`) — legacy `SystemData` is
  one global config row (BaseURL, sender emails, SMTP flags): deployment/infra config, not
  domain data. Per directive 2 it lives in Spring/env in AlpenFlight (no `SystemData` entity
  exists). So **no editable SystemData CRUD is built** (anti-pattern); migration N/A. If the
  operator wants a read-only "System info" strip (app version / env / base-URL) on this
  route, that's a thin ≤40% add — `/do-ship` sizes it; default is to skip and lead with the
  audit viewer.
- **Likely task seams** (non-binding, seam-granularity):
  - `system` feature folder — `system.routes.ts` + `audit-log.page.ts` (list) +
    `audit-log.store.ts` (Signal Store over `AuditEventsService`, filter + pagination state)
    + row-detail expansion.
  - `nav-sections.ts` — add the `/system/logs` entry to the club-admin group.
  - Server IT (S-160) — DB-role split (compose + CI + Helm) + append-only `permission denied` IT.
  - e2e — `alpenflight/web/e2e/tests/system/audit-log.spec.ts` (mock inner-loop) + real-idp
    two-club tenant-isolation + pilot-denied gate spec.
- **Riders folded into this journey's gate** (from `_BOYSCOUT`, ≤40% debt budget — `/do-ship`
  sizes each):
  - **[REALIDP-FLAKE-QUARANTINE]** (J-12b-window retro, carried forward here) — 4 chronic
    real-idp specs flake the cross-journey gate; J-13 runs that gate → stabilize or
    quarantine. **NOTE:** J-29 shipped "real-idp/nightly fully-green", so some of these 4 may
    already be fixed — `/do-ship` verifies the *current* gate state first and only quarantines
    what still flakes.
  - **[NG8113-DEADIMPORT]** (J-12b-window retro, carried forward) —
    `flights/edit/flight-conflict-prompt.component.ts:41` unused `AfButtonComponent` import →
    build warning on every web build. A 1-line boyscout fix in the ≤40% slot (clears a warning
    J-13's own web build emits).
  - **[MAINTAINABILITY-TOOLING — Qodana baseline backfill]** — J-13 touches CI/web (a
    maintainability slot); backfill the real Qodana baseline over the placeholder if a CI
    cycle allows.
  - Standing debt riders (WORKFLOW-SLIM, COMMENT-STRIP, HISTORY→GIT) ride the ≤40% budget as
    capacity allows.
  - **S-104** (per-endpoint permission boundaries) — assert *this* endpoint's role matrix
    (pilot denied / club-admin allowed / sysadmin), do not build the full L catalog.

## Assumptions made

1. `next` = J-13 — first roadmap row whose `depends_on` are all done (J-0 shipped). The
   roadmap order has drifted (many J-2x / J-9b / J-1x inserted) but J-13 is the first uncarved
   forward journey with satisfied deps.
2. `SystemData` → infra config (migration N/A), as above.
3. Cross-tenant sysadmin audit aggregation deferred to S-023 (J-14); J-13 = club-admin
   own-tenant view.
4. Audit-history migration (legacy `AuditLogs` → `LEGACY_MIGRATED`) is J-21's cutover-importer
   job, not J-13; the viewer proves over live app-generated events.
5. The stranded `do-retro/J-12b-window` riders are carried forward on this branch (its do-ship
   `SKILL.md` half is already on `main` as a superset; only the 2 `_BOYSCOUT` riders needed
   rescuing).
