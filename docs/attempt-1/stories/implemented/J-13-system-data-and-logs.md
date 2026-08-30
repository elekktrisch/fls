---
id: J-13
title: Audit-log viewer (/system/logs)
epic: E-06
status: done
started_at: 2026-07-20
done_at: 2026-07-20
journey0: false
carved: true
depends_on: [J-0]
rolls_up: [S-056, S-160]
acceptance:
  - "[happy] A club-admin opens /system/logs; a mutation performed earlier in the run appears as a row with action, target entity type, actor, occurredAt timestamp, and HTTP status (failure-only — success rows show '—', see Decisions)."
  - "[happy] Filtering by action (e.g. UPDATE) and by target entity type narrows the list to matching rows; clearing filters restores the full list."
  - "[happy] Time-range filter: a future from-bound empties the audit list through a real occurredFrom request, and clearing the filter restores the list. NOT proven — that the filter keeps an in-range row beside an out-of-range row that drops; the control has date granularity, so all rows of one run share one date."
  - "[happy] Pagination: with more events than one page, advancing fetches the next offset (hasMore / nextOffset cursor); default page size is 50."
  - "[happy] Expanding a row shows the before/after state payload (diff for UPDATE, after-only for CREATE, before-only for DELETE)."
  - "[key-error] A plain PILOT is denied the screen (guard redirects home), the nav entry is absent, and the endpoint returns 403."
  - "[edge] Tenant isolation: a club-A admin sees only club-A audit events, never club-B's (structural @TenantId) — asserted at the real-idp two-club gate."
  - "[key-error] Append-only (S-160): an UPDATE against t_mutation_audit_event as the app DB role fails permission denied — proven by a server IT."
screen: /system/logs   # replaces legacy flsweb system/logs/
migration: N/A — audit trail is app-generated (live events); legacy SystemData → infra config (no domain entity). Audit-history import is J-21's cutover job.
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts
adr_refs: [0007, 0008, 0022]
---

## Contract

- **Endpoint** — `AuditAdminController GET /api/v1/admin/audit-events`, `@PreAuthorize hasAnyRole('CLUB_ADMINISTRATOR','SYSTEM_ADMINISTRATOR')`, per-tenant via Hibernate `@TenantId`. Optional filters `occurredFrom/occurredTo/action/targetEntityType/actorUserId/pageSize(50)/pageOffset`; returns `AuditEventPage {items, hasMore, nextOffset}`. Cross-tenant sysadmin aggregation is deferred to S-023 (J-14) — J-13 is the club-admin own-tenant view. Read endpoint + generated `AuditEventsService.listAuditEvents` client pre-existed (S-027).
- **Screen** — `features/audit-logs/` (route `/system/logs`, `clubAdminGuard` → non-admins redirect to `/start`; nav entry in `MASTERDATA_CLUB_ADMIN_ITEMS`). Signal Store over the generated client (filter + cursor state). List renders as an accessible `<ul>` (row expansion needs a full-width detail region `af-data-table` can't host); cursor pagination (page 50, Next gated by `hasMore`); row-detail before/after diff.
- **Append-only (S-160)** — the existing `alpenflight` role is the Flyway MIGRATOR; a new `alpenflight_app` login role (the app's runtime datasource) has broad DML but only INSERT,SELECT on `t_mutation_audit_event` (V54). An IT connects as the app role and asserts UPDATE/DELETE → SQLState 42501, run for real in CI container mode.

## Decisions / parity exclusions

- **SystemData → infra config, no CRUD** (directive 2). Legacy `SystemData` (BaseURL, sender emails, SMTP flags) is one global config row → Spring/env, not a domain entity. No editable SystemData screen; migration N/A.
- **Legacy `system/logs/` is REPLACED, not ported** (S-056). The legacy log4net `SystemLog` dump is superseded by the mutation-audit trail — a semantic upgrade, no parity match, no legacy table migrated.
- **httpStatus is a FAILURE-ONLY column** (operator decision 2026-07-20). Success mutations write the audit row via an AFTER_COMMIT listener with `httpStatus=null` by design (decoupled from the HTTP response); only failures carry a status. The viewer renders the status when present and `'—'` for success rows; the AC's "HTTP status" is asserted on a failed row (a real DELETE→404).
- **V54 role split is privilege-aware, fails CLOSED.** It provisions the split only when the migrator has CREATEROLE/superuser (compose/CI/prod), else no-ops with a NOTICE. If the split doesn't run, the `alpenflight_app` login role is never created and the app (which boots as that role) can't start — a loud deploy failure, never a silent full-CRUD grant. The `forbidden-migration-patterns` relaxation is keyed to `V54__` only.

## Tasks

- [x] T-01 spec stub + J-13 proof-gallery scaffold
- [x] T-02 scope per-push gate to J-13 (verify-only; scoping already correct post-J-29)
- [x] T-03 audit-logs feature scaffold (route + clubAdminGuard + nav + Signal Store)
- [x] T-04 list table (5 columns + cursor pager + loading/empty)
- [x] T-05 filter controls (action / target-type / time-range / clear)
- [x] T-06 row-detail expansion (UPDATE diff / CREATE after / DELETE before)
- [x] T-07a S-160 DB-role split (V54 + config + ci.yml, migrator/app)
- [x] T-07b S-160 append-only proof IT (real 42501 in CI container mode)
- [x] T-08 S-104 endpoint role-matrix (PILOT 403 / admin 200 / sysadmin 200)
- [x] T-09 real-idp two-club gate spec + gallery (5 cases, all ACs)
- [x] T-10 nav-sections.spec cross-consumer fix (/system/logs)

## Outcome

Club-admins get the first user-facing window on their tenant's mutation history at `/system/logs`, replacing the legacy log4net dump with real who-changed-what forensics over `t_mutation_audit_event`. J-13 shipped the viewer (list / filters / time-range / cursor pagination / before-after row detail), the S-160 append-only DB-role split (proven by a real 42501 IT), and the endpoint role matrix. Real-idp two-club tenant-isolation + pilot-403 green at the gate; the proof gallery renders the pass videos. migration N/A → no fanout gate. PR #242.
