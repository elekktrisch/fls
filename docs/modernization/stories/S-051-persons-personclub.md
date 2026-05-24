---
id: S-051
title: Persons CRUD + PersonClub many-to-many
epic: E-06
status: in_progress
started_at: 2026-05-23
depends_on: [S-048, S-047]
acceptance:
  - `Person` (cross-tenant; **no `@TenantId`**) + `PersonClub` (aggregate-internal child under Person; `@TenantId` on `club_id`; M:N junction with `member_number`, `member_state_id`, role flags, notification prefs) ported. No new Flyway migration — V2 schema is sufficient.
  - One-transaction create flow: `POST /api/v1/persons` accepts an inline `initialClubMembership` block; service writes Person + first PersonClub in one tx.
  - Add-existing-person flow: `POST /api/v1/persons/lookup` (exact-match — `email` OR `(firstname, lastname, birthday)` triple only; never prefix-search), rate-limited; followed by `POST /api/v1/persons/{personId}/clubs` to attach to caller's tenant.
  - Per-tenant list (`GET /api/v1/persons`) goes through `Person JOIN PersonClub` so Hibernate `@TenantId` filters automatically; `personRepository.findAll()` is banned.
  - Person edit screen shows the caller-tenant's PersonClub row only (CLUB_ADMINISTRATOR) — other memberships surface as an opaque `inOtherClubsCount`. SYSTEM_ADMINISTRATOR sees all memberships via `/api/v1/admin/persons/{id}` if and only if the cross-cutting path is needed for cutover (defer otherwise).
  - 404 (not 403) when CLUB_ADMINISTRATOR loads a Person whose only PersonClub is in another tenant.
  - `GET /api/v1/club/member-states` listitem endpoint shipped (tenant-scoped reference-data precedent for the form `<af-select>`).
  - Per-tenant new e2e spec at `alpenflight/web/e2e/tests/persons/persons-add-modal.spec.ts` passes (legacy `e2e/tests/masterdata/persons-add-modal.spec.ts` stays the parity oracle for modal mechanics + persistence — route shape is greenfield).
estimate: L
adr_refs: [0005, 0008, 0022, 0023]
parity_test: alpenflight/web/e2e/tests/persons/persons-add-modal.spec.ts
parity_excluded:
  - Legacy `/api/v1/persons/page/{skip}/{take}` POST envelope — new stack uses `GET /api/v1/persons?…` per ADR 0005.
  - Modal reached from `/masterdata/users/{id}/edit` (legacy quirk) — new-stack modal lives on the persons surface (see Open design questions for exact placement).
  - `Person.IsFastEntryRecord` — dropped from DTO/UI; V2 column retained for cutover ease.
refined: true
refined_at: 2026-05-23
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 106
github_pr: 107
---

## Context
**Sacred-cow shape** — Person/PersonClub split underpins multi-club pilot rosters. A Person can belong to multiple clubs via multiple PersonClub rows; a Flight's crew (S-058) references a Person from possibly a different club than the flight's operating tenant. The cross-tenant root + tenant-scoped child pattern is intentional and **unique to Person** — no other planned aggregate replicates it.

## Acceptance criteria
See frontmatter.

## Tasks
Superseded by acceptance criteria + design notes.

## Pickup notes

**Backend test wallclock budget (operator directive 2026-05-24):** local
`./gradlew check` runs ~5min per pass and the implement loop burned ~20min
across 4 full passes. Survives the session reset because future iterations
on this story (or sibling backend stories) should:

1. **Skip local `gradlew check`** for per-iteration verification. Use
   `./gradlew test --tests 'ch.alpenflight.<changed-package>.*'` instead
   (~30s for the persons module, ~60s with arch tests). Rely on remote CI
   for the full-suite + arch + Modulith + OpenAPI-snapshot validation.
2. **Gradle parallelism flags now on** in `alpenflight/server/gradle.properties`:
   `org.gradle.parallel=true` + `org.gradle.caching=true`. `maxParallelForks=2`
   on the `test` task was already configured. Configuration cache is off
   pending Spring Boot 4 + Flyway plugin compatibility (test with
   `--configuration-cache` to re-evaluate).
3. **Spring context cache:** 32 distinct `@SpringBootTest` classes share
   the `@ActiveProfiles("test")` + `JwtTestFixture` shape; a future
   consolidation (hoist `@Import(JwtTestFixture)` onto a shared base or
   move to `application-test.yml`) would cut wall further. Tracked as a
   deferred follow-up — not in S-051 scope.

<!-- modernize-refine: start -->

## Design notes

**Module layout** (`ch.alpenflight.persons/{domain,application,web,infra}/` per ADR 0023; `package-info.java` per package):
- `domain/` — `Person` aggregate root, `PersonClub` aggregate-internal child, `PersonRepository` interface, domain exceptions (`PersonNotFoundException`, `DuplicateClubMembershipException`, `CrossTenantMembershipBlockedException`). Spring-web-free, Jackson-free.
- `application/` — `PersonsService`, `PersonDtos` (records), `PersonMapper`, `MemberStateSlice` (slim read-side for the listitem endpoint; keeps `member_state` infra package-private until full CRUD lands).
- `web/` — `PersonsController`, `MemberStatesController`, `PersonsExceptionHandler`. (`PersonsAdminController` only if cutover forces it; see Open Questions.)
- `infra/` — `JpaPersonRepository` extends domain interface + `JpaRepository<Person, UUID>`; `JpaMemberStateRepository` (package-private read-only projection).

**Aggregate.** `Person` = root, no `@TenantId`. `PersonClub` = `@Entity` with `@TenantId` on `clubId` (`updatable=false`); `@OneToMany(mappedBy="person", cascade=ALL, orphanRemoval=true)` from Person. Hibernate's tenant filter automatically scopes the `personClubs` collection on PK loads — CLUB_ADMIN sees only their tenant's child rows; sysadmin via `Tenants.runAs(null)` sees all. Document the pattern in `package-info.java` so it's not copied where unwanted.

**Domain methods on `Person`** (per ADR 0022 directive 2 — business rules on aggregate, not schema):
- `register(...)` factory; `rename(...)`; `updateContact(...)`; `updateLicences(...)` — identity / contact / licence edits.
- `joinClub(clubId, memberNumber, memberStateId, roleFlags, notificationPrefs, isActive)` — adds a child; rejects duplicate-alive (`DuplicateClubMembershipException` — the `ux_person_club_alive` partial unique is the structural safety net).
- `rejoinClub(...)` — reactivates the soft-deleted PersonClub for the same `(person, club)` pair (preserves history; satisfies the partial unique). Default behavior of `joinClub` when a soft-deleted row exists for the pair.
- `updateClubMembership(clubId, ...)` / `leaveClub(clubId, ...)` (soft-delete the child).
- `softDelete(userId, clock)` — CLUB_ADMIN call rejects if Person has active PersonClub in other tenants (would orphan another tenant's records) → `CrossTenantMembershipBlockedException` → 409. Sysadmin cross-cutting hard-delete (if shipped) cascades via `orphanRemoval`.

**REST surface (canonical — Option A).**
- `GET /api/v1/persons` (list-in-tenant; JPQL constructor projection joining `person` × `person_club`; `Page<PersonListItem>`, default `size=50`, cap 200).
- `POST /api/v1/persons/lookup` (exact-match add-flow target; see Security plan for shape).
- `POST /api/v1/persons` (create Person + first PersonClub in caller's tenant; one tx).
- `GET|PUT /api/v1/persons/{id}` (CLUB_ADMIN scoped; sub-resource semantics).
- `POST /api/v1/persons/{id}/clubs` (attach existing Person to caller's tenant).
- `PUT|DELETE /api/v1/persons/{id}/clubs/current` (mutate / soft-delete the caller-tenant PersonClub).
- `GET /api/v1/club/member-states` (tenant-scoped listitem — pattern precedent for future per-club reference reads; counterpart to S-047's cross-tenant `/api/v1/countries`).

**DTOs.** Two Java records, immutable, `psn_` prefix-encoded `PersonId` at the boundary (raw UUID `PersonClubId` — internal entity, no prefix per V2 ID-strategy):
- `PersonResponse` — Person identity + `List<PersonClubSummary>` (caller-visible memberships; Hibernate filter scopes it automatically) + `inOtherClubsCount` (opaque cross-club count for CLUB_ADMIN; for sysadmin the list is exhaustive and the count is 0).
- `PersonListItem` — slim (name, contact, caller-tenant's `memberNumber` + `memberStateId` + top role flags).
- Mapper does not branch on role — it iterates `person.getPersonClubs()` after Hibernate has scoped it.

**Cross-tenant query discipline (the "L").** Two legal read paths only:
- (a) `findById(UUID)` — cross-tenant by PK; used by Flight crew load (S-058), Users (S-052 — `user.person_id` FK), sysadmin admin path.
- (b) `SELECT p FROM Person p JOIN p.personClubs pc` — Hibernate auto-appends `pc.club_id = :currentTenant` because PersonClub carries `@TenantId`. The list-in-tenant query MUST use this shape. Verify via SQL-log capture in one integration test; S-024 leakage CI re-verifies per-repo.

**Frontend.** `alpenflight/web/src/app/features/persons/{list,edit,routes,store}` mirroring `aircraft/`. One `PersonsStore` (Signal Store, S-006 template). List page → `<af-data-table>`. Edit page → `<af-form-field>` + `<af-input>` + `<af-select>` for country (S-047 ReferenceDataStore) + member_state (new tenant-scoped `MemberStatesStore` keyed by `clubId`). Add-person flow lives on `/persons/new` as a full route (deep-linkable for sysadmin); modal placement on the list toolbar vs. only inside the User-create / Flight-crew flows → Open Question.

**Per ADR 0022 directive 2.** No new CHECK constraints, no triggers. V2's `ck_person_email_*_shape` retained as input-shape defense-in-depth (already documented). PersonClub role flags are independently composable (no business CHECK to add). `joinClub` invariant ("max one alive PersonClub per pair") is structural via the existing `ux_person_club_alive` partial unique. **No deviation proposed.**

**Integration.**
- *Inputs:* Country / ClubState from S-047 referencedata; `<af-data-table>`, `<af-form-field>`, `<af-select>` from S-008; `PersonsStore` template from S-006; ID-prefix codec + `TypedIdJacksonModule` from S-152 — register `PersonId` with `psn_`.
- *Outputs:* `PersonResponse` consumed by S-052 (User.person_id), S-058 (Flight crew), S-068 (AircraftReservation crew). `/api/v1/club/member-states` becomes the tenant-scoped listitem pattern (counterpart to S-047's cross-tenant pattern). Add-person modal reusable from S-052 + S-058.

**Alternatives.** (a) PersonClub as Club-aggregate child — rejected (breaks cross-tenant Person edit). (b) Composite (person_id, club_id) PK — rejected (V2 chose surrogate for Spring Data uniformity). (c) Defer add-person modal to S-052 — rejected (AC requires here).

## Edge cases & hidden requirements

- **Cross-club visibility on CLUB_ADMIN edit.** Recommended default: caller's PersonClub row + opaque `inOtherClubsCount` integer; no other-club names. Sysadmin sees all. (See Open Questions for alternatives.)
- **Lookup is cross-tenant enumeration risk.** Legacy substring filter would turn CLUB_ADMIN into a global address-book. Exact-match only (email OR `(firstname, lastname, birthday)` triple); reject partial triples at the DTO; cap results at ≤ 5; audit hit AND miss.
- **One-transaction Person + PersonClub create.** Person insert has no `@TenantId` so JPA passes through; PersonClub insert reads `@TenantId` from SecurityContext. `POST /api/v1/persons` body carries optional inline `initialClubMembership`; service wraps both in one `@Transactional`.
- **Aggregate-boundary surprise (easy to get wrong).** `person_club` is tenant-scoped but lives **under the cross-tenant Person aggregate root** (per V2 §3 comment). API consequence: PersonClub mutations go through `/api/v1/persons/{personId}/clubs/...`, never `/clubs/{clubId}/persons/...`.
- **`member_state` dependency.** PersonClub.member_state_id needs a working dropdown. Ship a read-only `GET /api/v1/club/member-states` listitem here (tenant-scoped reference-data pattern); full member_state admin CRUD deferred to a follow-up. `person_category` is not referenced by PersonClub — out of scope.
- **Person prefix `psn_`** at REST/JSON boundary (aggregate root). PersonClub stays raw UUID.
- **Soft-delete semantics.** `leaveClub` soft-deletes the PersonClub (`deleted_on`). `softDelete(Person)` by CLUB_ADMIN rejects with 409 if Person has active PersonClub in any other tenant (cross-tenant safety); sysadmin hard-delete cascades. Re-join after soft-delete reactivates the prior row rather than inserting a fresh one (preserves history; satisfies `ux_person_club_alive`).
- **PII surface vs. S-027 audit.** Audit `before`/`after` captures Person snapshots verbatim. Accepted for S-051; flag a future DSAR/erasure story for cross-tenant audit-blob scrubbing.
- **Audit `tenant_club_id` on cross-cutting sysadmin Person rename.** Should resolve to NULL. Verify S-027's column is nullable; if not, file as an S-027 follow-up (see Open Questions).
- **Pagination shape.** `GET /api/v1/persons?page=&size=&...` per ADR 0005. Legacy `/page/{skip}/{take}` POST envelope dropped — parity spec maps observable behavior, not URL shape.
- **Cross-tenant FK regression guard.** No `@TenantId`, no `@Filter`, no `@Where` on `Person`. Explicit unit + IT for "Person loads by PK from a different SecurityContext tenant" so a future refactor can't silently break multi-club rosters (sacred-cow guard for S-058 Flight crew).
- **`Person.is_fast_entry_record`** dropped from new DTO/UI (legacy modal-stub flag); V2 column retained for cutover.

## Security plan

### Threat model
| Risk | Severity | Mitigation in S-051 |
| --- | --- | --- |
| Cross-tenant Person enumeration via add-existing lookup → global PII directory for any CLUB_ADMIN. | **Critical** | `POST /api/v1/persons/lookup` exact-match only (`lower(email)` OR `(lower(firstname), lower(lastname), birthday)` triple); no prefix / `LIKE` / `q=` parameter accepted; per-caller rate-limit (10/min); audit hit AND miss (negative response is information). |
| CLUB_ADMIN A mutates shared Person → club B sees the change silently (sacred cow). | High (accepted) | Audit row written under modifying tenant; cross-club notification deferred to a future story. |
| 404 vs 403 on `/persons/{id}` for Person whose only PersonClub is in another tenant → 403 leaks existence. | High | `@personAccess.hasPersonInTenant(#id)` evaluator returns false → controller maps to **404**. |
| Native-SQL bypass on `person` / `person_club`. | Med | No native SQL in this story; if added, register per S-011 escape-hatch (S-024 CI grep enforces). |

### Authorization
- `GET /api/v1/persons` → `hasRole('CLUB_ADMINISTRATOR')`; JOIN through PersonClub so Hibernate filters automatically.
- `GET|PUT /api/v1/persons/{id}` → `hasRole('CLUB_ADMINISTRATOR') and @personAccess.hasPersonInTenant(#id)` (404 on false).
- `POST /api/v1/persons` + `POST /api/v1/persons/lookup` + `POST /api/v1/persons/{id}/clubs` → `hasRole('CLUB_ADMINISTRATOR')`.
- `PUT|DELETE /api/v1/persons/{id}/clubs/current` → same as detail-read predicate.
- SYSTEM_ADMINISTRATOR stripped from `/api/v1/persons/**` per S-159; any cross-tenant Person ops go through `/api/v1/admin/persons/**` — out of scope for S-051 unless cutover requires it (Open Question).

### Input validation
- Lookup DTO: cross-field validator rejects partial triples (`firstname` without `lastname+birthday`); bare `q=` / `prefix=` absent from the schema.
- PersonClub role flags + `member_number` uniqueness re-checked on aggregate (business validator surfaces typed 409, not raw FK bubble).
- Birthday + medical-expiry ranges live as VO invariants on Person (V2's removed `ck_person_birthday_not_future` per ADR 0022).

### PII handling
Every Person column is FADP direct-identifier. Never logged at INFO+ (`AuditPayloadTurboFilter` from S-027 enforces); transit TLS at reverse proxy. Audit `before`/`after` store full PII verbatim (admin-readable anyway) — flag future DSAR/redaction story for cross-tenant audit-blob scrubbing.

### Audit-log events
- `PERSON_CREATED` / `PERSON_UPDATED` / `PERSON_DELETED` (target=Person id, tenant=modifying club).
- `PERSON_CLUB_JOINED` / `PERSON_CLUB_UPDATED` / `PERSON_CLUB_LEFT` (target=PersonClub id, tenant=current club).
- `PERSON_LOOKUP_HIT` / `PERSON_LOOKUP_MISS` (target=lookup-key hash, tenant=caller's club, before/after null) — explicit because the negative response is itself disclosure.

### Cross-tenant leakage
S-024's reflective sweep auto-iterates `person_club` (TENANT_SCOPED). Person enters S-024's `kind: cross-tenant` positive sweep — assert (a) multi-club Person PK-load succeeds from either tenant (sacred-cow regression), (b) the PersonClub join path from tenant A returns zero rows for a Person whose only memberships are in tenant B. Add `PersonsCrossTenantNotFoundIT` extending `CrossTenantNotFoundContract` for the 404-not-403 IDOR witness.

### OWASP applicability
- **A01 Broken Access Control** — `@personAccess.hasPersonInTenant` + 404-not-403 evaluator.
- **A04 Insecure Design** — exact-match lookup + rate-limit + both-paths audit prevents the global-PII-directory failure.
- **A09 Logging & Monitoring** — lookup-miss audit closes negative-response leak; full-PII audit accepted, DSAR story flagged.

## Test plan

### Pyramid
- **Unit (domain): ~6** — `joinClub` rejects 2nd active row for same `clubId`; `joinClub` reactivates a soft-deleted row for the same pair (NOT a fresh row); `leaveClub` is soft-delete; role-flag setters independent; `softDelete` rejects when active PersonClub exists in another tenant; `rename` trims/validates.
- **Integration (Postgres testcontainer + mock-auth JWT helper + `TwoClubFixture`): ~10** — see Specific cases.
- **E2E (Playwright, compose stack): 1 spec** at `alpenflight/web/e2e/tests/persons/persons-add-modal.spec.ts`.
- **Vitest (FE logic only, no DOM): 2** — `persons.store.spec.ts` (entityMap on create/update/leave) + one validator spec if member_number shape is enforced.

### Specific cases (happy / edge / error)
- **Cross-tenant ride-through positive guard** (`PersonsCrossTenantRideThroughIT`): seed Person in club A; `Tenants.runAs(null)` loads by PK and returns the row — sacred-cow regression guard for S-058. Tell S-024 owners to extend the leakage sweep to include `person_club` and EXCLUDE `person` (`kind: cross-tenant` in tenant-rules.yaml).
- **Privacy projection**: GET Person as CLUB_ADMIN A when Person ∈ (A, B) → response contains only A's PersonClub + `inOtherClubsCount=1`. GET as CLUB_ADMIN A when Person ∈ B only → 404.
- **Sysadmin cross-cutting read** (if path shipped): `/api/v1/admin/persons/{id}` returns all memberships; regular `/api/v1/persons/{id}` rejects sysadmin per S-159.
- **Add-existing flow**: Person + PersonClub in B; CLUB_ADMIN A `POST /persons/lookup` (exact email) → 1 candidate; A `POST /persons/{id}/clubs` → 2nd PersonClub in A; B's row untouched + invisible to A.
- **Identity update on shared Person**: A renames Person ∈ (A, B); both clubs see new name; `mutation_audit_event` row emitted with `tenant_club_id=A`.
- **Soft-delete refusal**: CLUB_ADMIN A `DELETE /persons/{id}` while active PersonClub in B exists → 409. Allowed once only A remains.
- **member_state listitem**: CLUB_ADMIN A sees only A's `member_state` rows.
- **Audit `tenant_club_id` nullability**: sysadmin cross-cutting Person rename emits with `tenant_club_id=NULL`. If S-027's column is NOT NULL, IT fails — flag as S-027 follow-up at PR time.
- **Lookup miss audit**: `POST /persons/lookup` with no hit emits `PERSON_LOOKUP_MISS` row.

### Fixtures
Reuse `TwoClubFixture` (existing) + one Person seeded into both clubs via `Tenants.runAs(A)` / `Tenants.runAs(B)` `joinClub` calls. No new fixture framework.

### Parity strategy
Legacy `e2e/tests/masterdata/persons-add-modal.spec.ts` is the oracle for *modal mechanics + persistence*, not for route or URL envelope. New spec maps: legacy "open modal from User-edit" → new "open modal from persons surface" (placement per Open Questions); legacy `POST /persons/page/{skip}/{take}` filter → new `GET /persons?lastname=`; legacy `#Firstname`/`#Lastname`/`#Email` id selectors → new `data-testid="firstname-input"` etc. (greenfield gets the testids the legacy lacks). Cutover gate: new spec green vs new stack; legacy spec stays green vs legacy.

## Performance plan

### Hot paths
`GET /api/v1/persons` (per-tenant list via `Person JOIN PersonClub` — Hibernate auto-appends `pc.club_id = ?`); `GET /api/v1/persons/{id}` (1 Person + 1-N PersonClub); `POST /api/v1/persons/lookup` (`lower(email) = ?` on Person — uses `ix_person_email_priv_lower`); `POST /api/v1/persons` create-and-attach (single tx).

### N+1 risks
List endpoint MUST use JPQL constructor projection `new PersonListItem(...)` directly from the JOIN — never load entities then map (10 × 5 → 50 SELECTs from lazy `personClubs`). Detail endpoint uses `@EntityGraph(attributePaths="personClubs")` or `JOIN FETCH`; no lazy access outside the `@Transactional` boundary.

### Indexes
**None new.** V2 ships `ix_person_club_club_person (club_id, person_id) INCLUDE (member_state_id, …)` covering the list join; `ix_person_email_priv_lower` for lookup; `ux_person_club_alive` for the M:N uniqueness. If implementer needs a new index, flag as deviation — AC doesn't require sorted pagination.

### Pagination
`Page<PersonListItem>` via Spring Data `Pageable`, default `size=50`, cap `size ≤ 200`. Same slim-projection endpoint reused by S-058 (Flight crew picker).

### Latency / caching
Inherits S-011's p95 < 100ms budget; trivially met at ~50–500 PersonClub rows per club; no story-specific perf test (covered by S-108 baseline). No server-side cache (mutation-heavy). FE `ReferenceDataStore` (S-047) caches Country; new `MemberStatesStore` keyed by `club_id`.

## Open design questions

1. **Cross-club visibility on CLUB_ADMIN Person edit.** Default proposed: opaque `inOtherClubsCount` integer + caller's PersonClub. Alternatives: (a) no signal at all (no leak that the Person exists elsewhere; harder UX), (b) club names list (privacy regression). Pick.
2. **Add-person modal placement.** Default: full-page route `/persons/new` shipped; modal also reachable from the `/persons` list toolbar. Alternative: modal only inside User-create (S-052) / Flight-crew (S-058) flows — `/persons/new` as the only persons-surface entry. Trade-off: shipped-here-modal forces the modal's UX to be settled in S-051; deferred-modal leaves the persons list with a "new" button that routes to a full page only.
3. **Sysadmin cross-cutting `/api/v1/admin/persons/**`.** Defer entirely until cutover demands it, or ship a thin GET/DELETE here? Defer recommended (no current consumer); S-052 may re-open.
4. **Audit `tenant_club_id` nullability** for cross-cutting Person update. If S-027's column is NOT NULL, file as S-027 follow-up before merging this story; if already nullable, no action.

<!-- modernize-refine: end -->
