---
id: S-053
title: Flight types + flight cost balance types CRUD
epic: E-06
status: in_progress
started_at: 2026-05-24
depends_on: [S-050]
acceptance:
  - `FlightType` (tenant-scoped per V3 `operating_club_id`) ported with full field set including `is_for_glider/tow/motor` flags + `is_check_flight`/`is_passenger_flight`/`is_solo_flight`/`is_flight_cost_balance_selectable`/`is_for_aircraft_reservation_type` + role booleans + `min_nr_of_aircraft_seats_required`.
  - `FlightCostBalanceType` (system-global reference per V3 — no tenant column) ported with `code` + `is_for_glider/tow/motor` flags + `person_for_invoice_required`. Aggregate enforces the V3-documented at-least-one-flag invariant (was the dropped `ck_fcbt_at_least_one_flag` CHECK).
  - REST surface: `/api/v1/flight-types/**` CLUB_ADMINISTRATOR per S-159; `GET /api/v1/flight-cost-balance-types` open to any authenticated principal (S-047 reference pattern); sysadmin CRUD for FCBT at `/api/v1/admin/flight-cost-balance-types/**` (defer if no current consumer).
  - 404-not-403 on cross-tenant FlightType detail reads.
  - List/edit screens; flag-based filtering matches legacy UI.
  - New Playwright spec at `alpenflight/web/e2e/tests/masterdata/flight-types-crud.spec.ts` (greenfield — no legacy oracle spec).
estimate: S
adr_refs: [0005, 0008, 0022, 0023]
parity_test: alpenflight/web/e2e/tests/masterdata/flight-types-crud.spec.ts
parity_excluded:
  - Legacy `e2e/tests/masterdata/29-flight-type-crud.spec.ts` — doesn't exist in legacy; the new spec is the contract.
  - `FlightCostBalanceType.FlightCostBalanceTypeId int` PK — new stack uses UUID (V3 already created the table this way; `legacy_int_id SMALLINT` preserved for cutover lookup).
  - `min_nr_of_aircraft_seats_required` legacy `0`-treated-as-null ambiguity — new DTO rejects `0` at the boundary; `null` is the only "no constraint" wire form.
  - `FlightCostBalanceType.IsActive` legacy soft-deactivate flag — V3 schema dropped it; FCBT mutation is full CRUD with physical DELETE gated by FK RESTRICT from consumers (no `is_active` toggle ships).
  - Legacy `FlightCostBalanceTypeName` (max 100, user-display) + `Comment` (max 500, internal) columns collapsed into a single `description` (max 200) per V3. S-058 picker UI will bind to `description`; cutover importer concatenates the two if the operator wants a richer string.
  - `FlightType.isForAircraftReservationType` form-checkbox NOT surfaced in the S-053 edit UI (DTO field round-trips, defaults to false on create). S-068 AircraftReservation ships the user-facing toggle when the feature consumer arrives.
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 108
github_pr: 109
---

## Context
Both entities are referenced by Flight (S-058) + AccountingRuleFilter (S-072) — pre-req for E-07 + E-09. Two entities, **two different tenant treatments**: FlightType is tenant-scoped (`operating_club_id` `@TenantId`); FlightCostBalanceType is system-global reference (no tenant column). Implementer must NOT mirror-blast a single shape across both — see Design notes.

<!-- modernize-refine: start -->

## Design notes

**One module, two aggregates.** `ch.alpenflight.flighttypes/{domain,application,web,infra}/` per ADR 0023 — both roots co-edit in the legacy admin UI, share fixtures, FCBT is too small to justify a second module. Domain has `FlightType` (root, `@TenantId` on `operatingClubId`, `updatable=false`) + `FlightCostBalanceType` (root, no `@TenantId`); typed-ids `FlightTypeId` (`ft-` prefix) + `FlightCostBalanceTypeId` (`fcb-` prefix) per ADR 0019.

**FlightCostBalanceType = JPA entity with UUID PK.** V3 already shipped the table with UUID; killing it now needs a V-bump. Consumer FK shape (S-058 Flight, S-072 AccountingRuleFilter) takes the UUID; orval generates a typed id at the wire. `legacy_int_id SMALLINT` preserved for the cutover importer's lookup. (See Open Question for the enum-vs-entity alternative.)

**Domain methods.** FlightType: `register / rename / updateFlags / updateBalanceSelectable / softDelete`. FCBT: `register / rename / updateFlags` (constructor + `updateFlags` enforce the at-least-one-of `is_for_*` invariant per V3 comment line 199). Flags otherwise independently composable (no DB CHECK on FlightType flag combinations).

**REST surface.**
- `GET/POST/PUT/DELETE /api/v1/flight-types[/{id}]` — `hasRole('CLUB_ADMINISTRATOR')`; 404-not-403 on cross-tenant detail; soft-delete on DELETE.
- `GET /api/v1/flight-cost-balance-types` — `isAuthenticated()` (S-047 cross-tenant ref pattern).
- `/api/v1/admin/flight-cost-balance-types/**` — `hasRole('SYSTEM_ADMINISTRATOR')`. Defer until a current consumer demands it; flag in story body when shipped.

**FCBT mutation lifecycle.** No soft-delete (V3 has no `deleted_on` on this table). DELETE is physical; `ON DELETE RESTRICT` from consumer FKs prevents accidental orphaning. Service translates the resulting `DataIntegrityViolation` → 409 `FLIGHT_COST_BALANCE_TYPE_IN_USE`. FlightType keeps the standard soft-delete pattern from S-050.

**Per ADR 0022 directive 2.** No new CHECK, no trigger, no DB enum. The at-least-one-flag invariant on FCBT lives as an aggregate method per V3:199 comment. **No deviation proposed.**

**Frontend.** `alpenflight/web/src/app/features/flight-types/{list,edit,routes,store}/` mirroring `aircraft/`. Two Signal Stores: `FlightTypesStore` (CLUB_ADMIN CRUD) + `FlightCostBalanceTypesStore` (cached cross-tenant ref read; S-047 pattern; cleared on `session.{logout,tenantSwitch}`).

**Integration.**
- *Inputs:* `<af-data-table>`/`<af-form-field>`/`<af-input>` from S-008 (`<af-checkbox>` if extracted by the pending boyscout — else inline `<input type="checkbox">`); typed-id codec from S-152; roles from S-022 / S-159.
- *Outputs:* `FlightType` consumed by S-058 (Flight.flight_type_id FK, `ON DELETE RESTRICT`), S-068 (AircraftReservation), S-072 (AccountingRuleFilter). `FlightCostBalanceType` consumed by S-058 + S-072.

**Audit.** Add `FlightType` + `FlightCostBalanceType` to `application.yml audit.redaction.entities` allow-list — no PII, all fields safe-to-log. Extend `AuditRedactionCoverageTest.AUDITED_PACKAGE_ROOTS` with `ch.alpenflight.flighttypes.domain`. Tenant-rules.yaml `FlightCostBalanceTypes` block needs `emits_audit: true` added (matches FlightTypes).

**Boyscout bundle (this PR).** Per operator directive: include the CI rename + auto-commit-on-drift workflow changes from `~/.claude/projects/-c-Users-roman-IdeaProjects-fls/memory/pending-boyscout-followups.md` (recovers what was reverted out of S-051 to unblock CI). **Must push from host** (operator's PAT with `workflow` scope) — sandbox OAuth-App push silently gates `pull_request` CI on the branch, as it did for the entirety of S-051's finalize loop.

## Edge cases & hidden requirements

- **Two entities, two tenancies — do not mirror-blast.** FlightType `@TenantId`; FCBT cross-tenant. Wrong copy-paste trips the S-024 sweep + the `@TenantId` resolver.
- **`min_nr_of_aircraft_seats_required` semantics.** Legacy treats `NULL` and `0` identically. New DTO: accept `null` only ("no constraint"); reject `0` with 400 at the boundary; aggregate accepts integer ≥ 1 OR null. Documented as the new-stack divergence in `parity_excluded`.
- **`is_flight_cost_balance_selectable` ↔ FCBT picker coupling.** When a FlightType has this flag true, the edit UI surfaces an FCBT picker filtered by matching `is_for_glider/tow/motor` overlap. Pre-requires the FCBT list endpoint shipped here. QA covers a "create FlightType with flag=true + FCBT picker shows only overlap-matching FCBTs" test case.
- **`is_for_aircraft_reservation_type` stays.** No consumer in S-053; S-068 (AircraftReservation) needs it. Don't drop as YAGNI.
- **FlightType name uniqueness per tenant.** Partial unique on `(operating_club_id, flight_type_name) WHERE deleted_on IS NULL` — verify present in V3; if missing, add as the story's only schema bump (and that's a V-bump consideration). Soft-delete-then-recreate-same-name must succeed (matches S-050 Aircraft pattern).
- **Sysadmin negative-test on FlightType writes.** Per S-159, SYSTEM_ADMINISTRATOR is denied `/api/v1/flight-types/**` writes — explicitly tested (`@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` alone is insufficient if role hierarchy regresses).
- **FCBT enum-vs-entity.** Operator decision in Open Questions; defaults to entity per V3.

## Security plan

### Threat model
| Risk | Severity | Mitigation in S-053 |
|---|---|---|
| SYSTEM_ADMINISTRATOR writes per-club FlightType (S-159 sacred-cow regression) | Med | Explicit deny IT — sysadmin token receives 403 on every `/api/v1/flight-types/**` write. Mirrors the S-159 strip pattern. |
| Soft-delete FlightType referenced by an active Flight (S-058) or AccountingRuleFilter (S-072) leaves orphan refs | Med | `ON DELETE RESTRICT` at DB + service translates `DataIntegrityViolation` → 409 `FLIGHT_TYPE_IN_USE`. Same shape for FCBT physical DELETE. |
| FCBT mutation reaches a non-sysadmin via path confusion | Med | Read controller mounted at `/api/v1/flight-cost-balance-types` exposes GET only; admin controller mounted at distinct `/api/v1/admin/flight-cost-balance-types/**` with class-level `hasRole('SYSTEM_ADMINISTRATOR')`. |

### Authorization
- `GET /api/v1/flight-types[/{id}]` → `isAuthenticated()`. Reads are open to any authenticated principal so pickers on S-058 Flight / S-072 AccountingRuleFilter forms can fetch the catalogue without an elevated role.
- `POST | PUT | DELETE /api/v1/flight-types[/{id}]` → `hasRole('CLUB_ADMINISTRATOR')`; SYSTEM_ADMINISTRATOR explicitly denied per S-159.
- `GET /api/v1/flight-cost-balance-types` → `isAuthenticated()`.
- `/api/v1/admin/flight-cost-balance-types/**` (CRUD) → `hasRole('SYSTEM_ADMINISTRATOR')`.
- Cross-tenant FlightType detail/update/delete → service `loadOrThrow` returns 404, never 403 (IDOR contract).

### Input validation
- `FlightTypeCreate/UpdateRequest`: no `operatingClubId` / `clubId` / `id` field (records, immutable, tenant from SecurityContext).
- `minNrOfAircraftSeatsRequired` ≥ 1 when set (null = no constraint; 0 rejected at DTO).
- FCBT constructor + flag mutators enforce at-least-one `is_for_*` flag (V3 line 199 invariant; dropped CHECK).

### PII handling
N/A — neither entity carries PII. No `audit.redaction.deny-all`; allow-list every field.

### Audit-log events
- `FlightType.{Created,Updated,Deleted}` auto-emit via S-027 AOP (`emits_audit: true` already in tenant-rules.yaml).
- `FlightCostBalanceType.{Created,Updated,Deleted}` admin route auto-emits — add `emits_audit: true` on the FCBT reference override in tenant-rules.yaml; tenant_club_id = NULL (system-global), actor = sysadmin user id.

### Cross-tenant leakage
FlightType auto-iterated by S-024 leakage sweep (tenant-scoped). FCBT excluded (reference).

### OWASP applicability
- **A01 Broken Access Control** — tenant gate via `@TenantId` + 404-not-403 evaluator + sysadmin negative-test on FlightType writes + path separation for FCBT read vs admin.
- **A05 Security Misconfiguration** — DTOs have no `operatingClubId` settable; admin-route separation enforced in `SecurityConfig`.

## Test plan

### Pyramid
- **Unit (domain): ~4** — `register` rejects blank name; FlightType flags independently composable; FCBT constructor rejects all-false-`is_for_*`; `softDelete` idempotent.
- **Integration (Postgres testcontainer + `TwoClubFixture`): ~7** — FlightType CRUD round-trip; cross-tenant 404-not-403; soft-delete-then-recreate-same-name; 409 on FlightType soft-delete with referenced Flight (S-058 contract — `@Disabled` with story-ref if `flight.flight_type_id` column not yet present); FCBT GET allowed for any authenticated role (parameterised); FCBT admin CRUD round-trip (or `@Disabled` if route deferred); SYSTEM_ADMINISTRATOR gets 403 on FlightType write.
- **E2E (Playwright): 1 spec** at `alpenflight/web/e2e/tests/masterdata/flight-types-crud.spec.ts` — list seed, create, edit; backend mocked via `page.route`.
- **Vitest (FE logic): 1** — `flight-types.store.spec.ts` (entityMap on create/update/delete + bus emission).

### Specific cases
- Sysadmin denied on tenant-scoped FlightType write returns **403, not 404** — rejected at role gate before tenant resolution; do not conflate with the cross-tenant 404 contract.
- FCBT picker overlap-filter (when `is_flight_cost_balance_selectable=true` on the FlightType being edited).

### Fixtures
Reuse `TwoClubFixture`; pick a `TEST_KEY_PREFIX` not used by any existing IT (`IT_F_` is a candidate — verify before commit per the `AircraftsTenantIsolationIT` collision precedent). Seed 1-2 FlightTypes per club via JDBC; seed FCBT rows via Flyway/JDBC at suite startup.

## Performance plan
(N/A — small story, no hot paths. Inherits S-011's p95 < 100ms budget; trivially met at ~20-50 FlightType rows per club.)

## Open design questions

1. **FCBT shape — entity (V3 default) or Java enum?** V3 already shipped the table with UUID PK + `legacy_int_id` lookup, so entity is the path-of-least-resistance. **But** the legacy set is small bounded (~5-10 rows, never user-extended), and an enum kills the dead-CRUD admin surface entirely. Operator decision: keep entity + ship sysadmin CRUD (default), or drop the table at S-053 via V-bump + Java enum + retrofit consumer FKs as `@Enumerated(STRING)`. Recommend keep — V-bump cost outweighs the dead-CRUD risk this early.
2. **FCBT sysadmin admin route — ship now or defer?** No current consumer demands it (S-058/S-072 haven't shipped). Default: defer; add later when the first consumer needs a way to maintain the catalog. Operator confirms.

<!-- modernize-refine: end -->

## Notes
Small surface; nothing tricky beyond the two-tenancies trap and the V3-mandated FCBT entity shape. Code-wise this is a sibling of S-050 Aircraft.
