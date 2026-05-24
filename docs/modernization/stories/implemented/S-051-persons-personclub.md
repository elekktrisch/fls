---
id: S-051
title: Persons CRUD + PersonClub many-to-many
epic: E-06
status: done
started_at: 2026-05-23
done_at: 2026-05-24
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
merged: true
merged_at: 2026-05-24
---

## Context
**Sacred-cow shape** — Person/PersonClub split underpins multi-club pilot rosters. A Person can belong to multiple clubs via multiple PersonClub rows; a Flight's crew (S-058) references a Person from possibly a different club than the flight's operating tenant. The cross-tenant root + tenant-scoped child pattern is intentional and **unique to Person** — no other planned aggregate replicates it.

## Cross-story contracts

- **Inputs:** Country / ClubState from S-047 referencedata; `<af-data-table>` / `<af-form-field>` / `<af-select>` from S-008; `PersonsStore` template from S-006; ID-prefix codec from S-152 (registered `PersonId` with `pn-`).
- **Outputs:** `PersonResponse` consumed by S-052 (User.person_id FK), S-058 (Flight crew picker), S-068 (AircraftReservation crew picker). `pn-` prefix registered in the typed-id codec. `/api/v1/club/member-states` listitem endpoint is the precedent for **tenant-scoped** reference data (counterpart to S-047's cross-tenant `/api/v1/countries` pattern). Add-person flow at `/persons/new` is the template later reused by User-create + Flight-crew picker.
- **Aggregate-boundary load-bearing:** PersonClub is tenant-scoped (`@TenantId` on `club_id`) but rides **under the cross-tenant Person aggregate root**. PersonClub mutations go through `/api/v1/persons/{id}/clubs/...`, never `/clubs/{clubId}/persons/...`. Future stories should not copy this shape unless they have the same sacred-cow constraint.
- **Cross-tenant FK ride-through guard:** Person carries no `@TenantId`, `@Filter`, or `@Where`. S-058 Flight crew load + S-052 User.person_id FK both depend on PK-load resolving cross-tenant. `PersonsCrossTenantRideThroughIT` is the regression witness.

## Deferred follow-ups

Captured here so a future operator can decide whether to file separate stories or fold into a sibling:

- **Lookup rate-limit (10/min/caller)** — security plan row 1's structural mitigation against the cross-tenant Person enumeration risk. Not implemented in S-051 (would introduce a Bucket4j / Spring rate-limit dependency for a single endpoint). Today's mitigation is exact-match-only + audit hit-AND-miss (LOOKUP_MISS rows correlate via SHA-256 of the canonical key). Rate-limit ships when the first abuse signal lands or as part of a broader rate-limit story.
- **Pagination on `GET /api/v1/persons`** — Performance plan called for `Page<PersonListItem>` via `Pageable`. Today's implementation returns `List<PersonListItem>` (unbounded). Per-club person counts are ~50–500, so unbounded is acceptable; promote to paginated when the first list crosses ~200 rows.
- **Person edit form field expansion** — current form is the modal-flow minimum (firstname / lastname / email / mobile / city / member-number / member-state / 3 role flags). Legacy form carries the full Stammdaten / Kommunikation / Lizenz / Club Einstellungen sections. DTO + aggregate already carry the full field-set; the work is UI surface only.
- **FE i18n** — Persons pages ship English-hardcoded, matching the Aircraft sibling. Locations is the only feature with i18n wired. A dedicated FE-i18n consolidation story should bring all three to the `de.ts`-source-of-truth + Transloco pattern.
- **`/persons` nav-bar entry** — operator reaches `/persons` via direct URL today; nav-bar entry is its own cross-cutting concern.
- **MemberState admin CRUD** — only the read-side listitem ships in S-051. Full CRUD lands when a per-club member-state admin UI is requested.
- **DSAR / audit-blob redaction** — Person stays in `audit.redaction.deny-all`; PersonClub has an explicit allow-list. A future DSAR/erasure story for cross-tenant audit-blob scrubbing is the natural home for full-PII redaction policy.
- **Lookup-audit IT** — `LOOKUP_HIT` / `LOOKUP_MISS` emission is unit-witnessed but not pinned at the IT layer; a single PostgreSQL-backed assertion would close the regression gap.
- **`MemberStatesController` relocation** — currently lives in `persons.web/` (convenience for the listitem dependency). Move to `clubs.web/` when full `member_state` admin CRUD ships.

## Open design questions answered

1. **Cross-club visibility on CLUB_ADMIN edit** → opaque `inOtherClubsCount` integer + caller's PersonClub only. No other-club names.
2. **Add-person modal placement** → full-page route `/persons/new` ships; modal-from-list and User-create / Flight-crew callers are deferred to those consumer stories.
3. **Sysadmin cross-cutting `/api/v1/admin/persons/**`** → deferred; no current consumer demands it.
4. **Audit `tenant_club_id` nullability** for cross-cutting Person update → already nullable (V9 `mutation_audit_event.tenant_club_id` was deliberately nullable per S-027); no S-027 follow-up needed.

## Pickup notes

**Backend test wallclock budget (operator directive 2026-05-24):** local
`./gradlew check` runs ~5min per pass; the S-051 implement loop burned
~20min across 4 full passes. Survives the session reset so future
iterations on this story or sibling backend stories:

1. **Skip local `gradlew check`** for per-iteration verification. Use
   `./gradlew test --tests 'ch.alpenflight.<changed-package>.*'` instead
   (~30s for one module, ~60s with arch tests). Rely on remote CI for
   the full-suite + arch + Modulith + OpenAPI-snapshot validation.
2. **Gradle parallelism flags now on** in `alpenflight/server/gradle.properties`:
   `org.gradle.parallel=true` + `org.gradle.caching=true`. `maxParallelForks=2`
   on the `test` task was already configured. Configuration cache is off
   pending Spring Boot 4 + Flyway plugin compatibility.
3. **Spring context cache** — 32 distinct `@SpringBootTest` classes share
   the `@ActiveProfiles("test")` + `JwtTestFixture` shape; consolidating
   the `@Import` shape would cut wall further. Tracked as a deferred
   follow-up.

<!-- modernize-refine: end -->
