---
id: J-8
title: Accounting rule filters
epic: E-09
status: in_progress
started_at: 2026-06-13
journey0: false
carved: true
depends_on: [J-1]
rolls_up: [S-072]
acceptance:
  # ≥60% feature — the AccountingRuleFilter config screen, full CRUD, tenant-scoped
  - "[happy] /accountingrules list renders the club's rule filters (name, type, active) tenant-scoped; a 'Reports'-style nav entry under masterdata reaches it (chrome-reachable — the spec ENTERS via nav, not bare goto)"
  - "[happy] create a new filter via the edit form → it appears in the list; reload round-trips every field"
  - "[happy] selecting an AccountingRuleFilterType DRIVES the conditional sections — article-target (Article + DeliveryLineText + AccountingUnitType) vs recipient-target (member number) vs aircraft-filter predicates vs no-landing-tax sections show/hide per the chosen type (the crux of the legacy form)"
  - "[happy] the predicate match-lists round-trip — immatriculations, start-types, flight-type-codes, start-locations, landing-locations, club-member-numbers — EACH with its 'use for ALL except listed' toggle (the include/exclude inversion persists)"
  - "[happy] aircraft-filter type: flight-duration range (min/max seconds) + threshold text persist; flight-kind flags (glider/towing/motor) persist"
  - "[key-error] required fields (filter type, name) block Save with debounced as-you-type inline errors (built on the J-6b liveFieldErrors bar from the start — NOT the legacy touched-gated pattern)"
  - "[edge] cross-tenant GET of another club's filter → 404 (tenant isolation)"
  # done-bar — migration + audit
  - "[happy] a migrated legacy AccountingRuleFilter renders in the owning club's list with its predicate config intact (fanout real-export parity)"
  - "[edge] every mutation emits an audit event (S-072 emphasis: rule changes affect every subsequent invoice) — verified at IT level via ControllerAuditCoverage + an audit-row assertion"
screen: /accountingrules (list + edit) — replacing legacy masterdata/accountingRules/
headless_pulled_in: none (this is the CONFIG surface; the rules ENGINE that consumes these filters is J-9)
migration: AccountingRuleFilter (+ reference data AccountingRuleFilterType, AccountingUnitType, FlightCrewType) — legacy flsserver accounting tables
parity_test: alpenflight/web/e2e/tests/accounting/accounting-rules-edit.spec.ts
mock_test: alpenflight/web/e2e/tests/accounting/   # journey-under-work's own mock-auth specs (T-02: per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly)
adr_refs: [0005, 0008, 0022, 0027, 0024]
---

## Context

The configuration surface for the sacred cow (the billing rules engine, S-072).
A club admin defines `AccountingRuleFilter` rows — predicates that decide which
flights match a billing rule and what article/recipient the rule produces. The
rules engine (J-9) instantiates `Rule` objects from these rows at runtime, so
getting the form fields + the filter-type-driven conditional sections right is
the whole job. First E-09 (accounting/billing) journey — bootstraps the
`AccountingRuleFilter` aggregate, its reference-data types, and the per-journey
migration mapper. Greenfield in AlpenFlight (no existing code).

**No design reference exists** — `docs/modernization/design-reference/` has no
`screens-accounting*.jsx`. Build to the legacy form structure (below) + ADR 0024
visual conventions (slate/sharp/flat, sentence-case, Lucide, `af-` primitives).
The legacy design intent is documented in `flsserver/doc/Invoice-Rule-Editor-Form-Design.vsdx`
+ `InvoiceRuleFilters.xlsx` — consult at ship time.

## Spec must assert

Grounded in legacy `flsweb/src/masterdata/accountingRules/` (read at carve):

- **List** (`accountingRuleFilters-table.html`): the club's filters, tenant-scoped; create/edit/delete entry points.
- **Edit form** (`accountingRuleFilters-edit.html` + `AccountingRuleFiltersEditController.js`): the load-bearing contract is the **`AccountingRuleFilterTypeId`-driven conditional visibility**:
  - core fields always shown: filter-type (selectize), `Active`, `StopRuleEngineWhenRuleApplied`, `RuleFilterName`, `Description`, glider/towing/motor flags.
  - `targetTypeArticleVisible()` (filter type ∉ {5,10} per legacy `:235`) → Article picker + `DeliveryLineText` + `AccountingUnitType`.
  - `targetTypeRecipientVisible()` → recipient member-number picker.
  - `isRuleTypeAircraftFilter()` → flight-duration range (`Min/MaxFlightTimeInSecondsMatchingValue`) + `ThresholdText`.
  - `isRuleTypeNoLandingTax()` → its own sections.
  - match-lists, each with a `UseRuleForAll<X>ExceptListed` invert toggle: `MatchedAircraftImmatriculations`, `MatchedStartTypes`, `MatchedFlightTypeCodes`, `MatchedStartLocations`, `MatchedLdgLocations`, `MatchedClubMemberNumbers`.
- The variable predicate set per filter type → persist as a **jsonb `filter_config`** column on the aggregate (S-014 schema is `implemented/`; the jsonb `@JdbcTypeCode` pattern is already in the codebase — audit module). The aggregate validates structural invariants; business rules (which fields a type requires) live on the aggregate (ADR 0022 §2), NOT in the DB.
- **Exact filter-type → section mapping + the rules-engine field semantics** are the load-bearing behavior the implementer can't fully derive — **dispatch `legacy-oracle` at ship time** for the `AccountingRuleFilterType` enum values (what 5/10 mean) + the `filter_config` field contract per type.

## Notes

**Migration:** AccountingRuleFilter + the 3 reference-data types (filter-type,
unit-type, crew-type). Per-journey mapper; the predicate columns fan into the
jsonb `filter_config`. The match-lists reference aircraft immatriculations,
locations, flight-type codes, articles (S-054 backend `implemented/`), and
club-member numbers **by value/id** — it's a config surface that reads those as
reference data; it does NOT need their full CRUD screens (so `depends_on: [J-1]`
holds; Locations/FlightTypes/Articles reference data is already migrated/seeded).
A UNIQUE on (club, filter name) likely needs a real-producer collision IT
(legacy may lack it — confirm with `legacy-oracle`; ship a round-trip IT so it
reds in `check`, not the fanout).

**Seam hints (non-binding, one seam each):** `AccountingRuleFilter` aggregate +
repository (jsonb `filter_config`, `@TenantId`, JPA-first per ADR 0027 — NO
JdbcTemplate); `AccountingRuleFilterController` + DTOs (every mutation `@AuditedBy`/
reaches `AuditTrail.record` — `ControllerAuditCoverageTest`); reference-data
endpoints (filter-types / unit-types / crew-types); the `accounting` SPA feature
folder (signal store + list page + edit page with the conditional sections);
the per-journey migration mapper + legacy seed; the parity spec.

**Build-it-right-the-first-time (fold the shipped J-26 infra — ≤40% riders):**
- The new edit form uses the **J-6b `liveFieldErrors` as-you-type bar** + bound
  `[errors]` from the start (don't replicate the legacy touched-gated pattern —
  every other form was just migrated off it in J-26 T-10/11/12).
- The new store uses the **T-22/23 shared `shared/util/form/` helpers**
  (`classifyApiError` + `withOptionals`) so it lands low-CRAP, not a new hotspot.
- IT seeding via **production code + minted ids** (the J-26 T-19 TwoClubFixture
  pattern), never raw-JDBC seed.

**Cross-cutting riders to fold per `_BOYSCOUT.md` (sized at ship time, ≤40%):**
Qodana whole-program unused-code tool (maintainability-tooling, this journey's
40% slot — report-only + baseline + Spring-awareness); the 3 KC-26-upgrade-drift
nightly reds (login `ui_locales`, register verify-mail, token-lifecycle
silent-refresh — could ride here or its own KC-26 slice); the gallery re-arch
slice; orval explicit `operationId`s; e2e prettier/tsc normalization;
clubadmin4 + V29 removal. `/do-ship` adjudicates which fit this gate.

## Going-in state (verified at ship time — carve's "greenfield" was wrong on the migration substrate)

The V4 migration (`reservations_planning_accounting`) built the accounting **substrate ahead**, but it was never wired to a domain/UI/consumer and the fanout has never exported it:

- ✅ **Schema** (V4): `t_accounting_rule_filter` (`operating_club_id` `@TenantId`, `filter_config JSONB`, `article_target`/`recipient_target` `VARCHAR(50)`, `is_charged_to_club_internal`, `sort_indicator`), GIN index on `filter_config`, **identity UNIQUE `ux_arf_club_sort_partial` on (operating_club_id, sort_indicator) WHERE deleted_on IS NULL** (← the collision-IT seam, NOT name — legacy has no name UNIQUE and `RuleFilterName` is nullable per oracle). Reference tables `t_accounting_rule_filter_type` + `t_accounting_unit_type`.
- ✅ **Mapper** `migration-bundle/.../accounting/AccountingRuleFilterMapper.java` (folds all 10 `Matched*`/`UseRuleForAll*ExceptListed` pairs → `filter_config` `{useAllExcept, matched[]}`; flags/ranges/threshold too) + unit test; registered `KnownMappers:71`; `EntityType.ACCOUNTING_RULE_FILTER(Group.ACCOUNTING)`.
- ⚠️ **Reference seed incomplete:** V4 seeds filter-types `{10,20,30,40,50,60,70,80}` — **missing 5 (DoNotInvoice) + 55 (StartTax)** (real enum per oracle). A real club's type-5/55 row → FK 23503 at fanout.
- ❌ **Producer SELECT binding absent** — no `ACCOUNTING_RULE_FILTER` in `MapperLegacyBindings`; entity sits in `MapperBindingContractTest.KNOWN_UNBOUND:102`. Mapper has never run against the real MSSQL schema (authored ≠ proven).
- ❌ **`DeliveryLineText` + `RecipientName` dropped** by the mapper (not in `buildFilterConfig`, no column) — must find a home in `filter_config` so the form round-trips them (AC: "reload round-trips every field").
- ❌ **Domain aggregate / service / controller / reference endpoints / web feature** — none. This is the bulk of the build.

**Legacy parity facts (oracle, load-bearing):** filter-type ids → visible sections — article-target (∉{5,10}), recipient-target (==10), aircraft-filter duration/threshold (==30), no-landing-tax (==20); 10 match-lists each `{useAllExcept default true, matched[]}` (personCategories is **dead/commented-out** in the legacy form → migrate data, build no control); cross-tenant Update/Delete is a **legacy tenant-leak BUG** → new stack scopes by `@TenantId`, cross-tenant mutation → 404; per-type required-field rules are **NEW** business logic (legacy enforces only name client-side) → keep minimal: name + filter-type required (on the aggregate, ADR 0022 §2), no per-type target requirement (legacy permits empty targets).

## Tasks

Decomposed per do-ship §2 (one seam each; ≤8 files/≤5 new; ≥60% feature / ≤40% tech-debt riders = T-08, T-15). Migration journey → done-bar needs a green real-export fanout (T-10 binding + T-14 fanout assertion).

- [x] **T-01** — Spec stub `e2e/tests/accounting/accounting-rules-edit.spec.ts` (screen shape, selectors, thin asserts) + scaffold the per-journey proof-gallery page + link from the persistent index. *(e2e + gallery)*
- [x] **T-02** — Scope the per-push gate to J-8's own spec; move prior journeys' real-idp specs to mock-IdP (full real-idp regression → nightly + the §4 gate). *(ci.yml + frontmatter)*
  - Scoping infra is already generic (J-5 T-14 + J-6 T-02b derive steps in `ci.yml`); verified for `integration/J-8`: (a) mock per-push derives `e2e/tests/accounting/` (`--project=chromium e2e/tests/accounting/ --list` → exactly J-8's 7 fixme cases, no prior-journey specs pulled in; fixme → skipped at runtime, not a no-spec run) — **fixed**: added the missing `mock_test:` frontmatter (J-8 was the only scoped journey lacking it, so it would have fallen back to the full chromium suite); (b) real-idp per-push falls back to the J-0 Locations baseline (`is_baseline=true`) — J-8's `parity_test:` is a `tests/accounting/` mock spec, not a `tests/real-idp/` clean-seed spec, so the derive's `*)` fail-safe pins the baseline today (T-14 adds the real-idp spec → auto-flips); (c) no prior journey's real-idp spec gates per-push — the `required` aggregator gates only the single derived `alpenflight-proof` spec + J-4's own profile spec; the full cross-journey `--project=real-idp` regression is nightly (`alpenflight-e2e-real-idp.yml`, cron) + the §4 do-ship gate.
- [x] **T-03** — Domain aggregate `AccountingRuleFilter` (server main): `extends SoftDeletableAggregate`, `@TenantId operating_club_id`, `filter_config` `@JdbcTypeCode(JSON)` typed config, `create()`/`update*()` factories, filter-type business rules (name + type required) on the aggregate; domain unit tests. *(domain)*
- [x] **T-04** — `AccountingRuleFilterRepository` port + `JpaAccountingRuleFilterRepository` (tenant-scoped list/by-id soft-delete-filtered finders, next `sort_indicator`). *(infra/repo)*
  - Rider (closes the T-03 leakage-sweep gap that surfaced on full `check`): registered `AccountingRuleFilter` in `TenantScopedRowBuilders` + new `AccountingRuleFilterSweepFactory`, bumped `TenantSweepFloorAndPinTest` floor 10→13, and added **`V41__accounting_tenant_fk_naming_convention.sql`** renaming `fk_arf_operating_club_id`→`fk_accounting_rule_filter_operating_club_id` (the V32/V33 precedent the sweep's fail-closed FK assertion requires). **NOTE for T-09: V41 is now taken — re-number T-09's seed migration to V42.**
- [x] **T-05** — `AccountingRuleFiltersService` + `AccountingRuleFilterDtos` (create/update/detail/list); `filter_config` (de)serialize to typed config; article/recipient target assignment by type; cross-tenant load → 404; `AuditTrail.record` on every mutation. *(application)*
  - Single `AccountingRuleFilterWriteRequest` (create==update field set per legacy edit form) + `AccountingRuleFilterDetail`/`...ListItem`; list `target` derived at read time per oracle (`{recipientName} ({memberNumber})` / `{articleNumber} ({deliveryLineText})`). Target-by-type + threshold/duration normalisation = legacy `$scope.save` parity (type 10 recipient / ∉{5,10} article / 5 DoNotInvoice). Cross-tenant load → `AccountingRuleFilterNotFoundException` (→404, T-06 maps). Audit on create/update/delete; `filter_config` redacted (`@AuditRedact` on the entity field + `AccountingRuleFilter` allow-list in application.yml; package added to `AuditRedactionCoverageTest.AUDITED_PACKAGE_ROOTS`). Service IT (TwoClubFixture minted ids): round-trip incl. filter_config + target-by-type, cross-tenant get/update/delete → NotFound, audit-row written w/ redacted config. Full `:server:check` green (cpdRatchet held at baseline via the single write-request + shared `applyTo`).
- [x] **T-06** — `AccountingRuleFiltersController` (CRUD + list) + per-feature exception handler (404/400); satisfy `ControllerAuditCoverageTest`; `@PreAuthorize` CLUB_ADMINISTRATOR. *(web)*
  - `accounting/web/AccountingRuleFiltersController` on `/api/v1/accounting-rule-filters` — GET list / GET {id} / POST 201+Location / PUT 200 / DELETE 204, every method `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` (this is a pure config screen → even reads are admin-gated, unlike FlightTypes). Each method carries an explicit `@Operation(operationId=…)` (`listAccountingRuleFilters` / `getAccountingRuleFilter` / `createAccountingRuleFilter` / `updateAccountingRuleFilter` / `deleteAccountingRuleFilter`) so the orval client gets named methods (J-3 rider; T-08 finishes project-wide). Delete passes `principalUserId(jwt)` (UserPrincipalLookup) for the audit user. `accounting/web/AccountingRuleFiltersExceptionHandler` (`@RestControllerAdvice(assignableTypes=...)`) maps `AccountingRuleFilterNotFoundException`→404, `InvalidAccountingRuleFilterException`→400 (RFC-7807 via `ProblemResponses`). `ControllerAuditCoverageTest` satisfied via the call chain (controller→service mutation→`AuditTrail.record`), no `@AuditedBy` needed. OpenAPI snapshot regenerated + committed (5 ops, 2 paths). Controller IT (full-boot HTTP, JwtTestFixture): list/create-201+Location/get round-trip/list-with-computed-target/update-200/delete-204→404/unknown-404/cross-tenant-GET-404/blank-name-400. Full `:server:check` green.
- [x] **T-07** — Reference-data endpoints: filter-types, accounting-unit-types, flight-crew-types (read-only GET from seeded tables/enum + DTOs). *(web/referencedata)*
  - Module home: all three are **system-global Flyway-seeded reference catalogs** (no `@TenantId`) — landed in the `referencedata` OPEN module (not `accounting`), reusing the shared `ReferenceDataService` + `ReferenceDataDtos` + `ReferenceDataMapper` to keep the file count down. New entities `AccountingRuleFilterType`/`AccountingUnitType` (V4 `t_accounting_*type`) + `FlightCrewType` (V3 `t_flight_crew_type` — table+seed ALREADY exist, no T-09 crew seed needed). All three already classified `kind: reference` in `tenant-rules.yaml` and asserted by `TenantCatalogConsistencyTest`; leakage sweep skips them (no `@TenantId`). One controller `referencedata/web/AccountingReferenceController` hosts all 3 GETs, gated `isAuthenticated()` (reference-endpoint convention — Country/FCBT — NOT the admin-gated AccountingRuleFilter CRUD). Stable operationIds `listAccountingRuleFilterTypes` / `listAccountingUnitTypes` / `listFlightCrewTypes`. Filter-type + unit-type DTOs expose `legacyId` (the load-bearing int the SPA drives sections off); crew-type also exposes `legacyId`. OpenAPI snapshot regenerated + committed (3 ops, 3 paths, all 3 schemas carry `legacyId`). Controller IT asserts each GET returns seeded rows with `legacyId` populated (filter-type RECIPIENT=10 first; unit-type MINUTES=10; crew-type 1..6,10) + 401 anon. Full `:server:check` green; CPD baseline 4827→5009 (+182 irreducible per-catalog entity/DTO boilerplate, rationale in cpd-baseline.txt).
- [x] **T-08** — *(≤40% rider — orval operationIds)* SATISFIED for J-8's surface by T-06/T-07: every accounting CRUD + reference endpoint carries an explicit `@Operation(operationId=…)` → the orval client emits named methods for everything the FE consumes (T-11 regenerates + verifies). The **project-wide** operationId pass (legacy `getN` endpoints the accounting FE does NOT touch) stays a `_BOYSCOUT.md` rider — risky whole-client churn, do in isolation, not in this already-large journey.
- [x] **T-09** — Additive migration **V42** (V41 taken by T-04's FK-rename): seed filter-types **5 (DoNotInvoice) + 55 (StartTax)** into `t_accounting_rule_filter_type` with pinned UUIDs + extend the `Coercions` legacy-int→UUID map so all 10 resolve; test asserts 10 types resolve. (flight-crew-types already seeded V3 — confirmed T-07, no work.) *(migration/seed)*
  - `V42__accounting_rule_filter_type_seed_donotinvoice_starttax.sql` adds the two rows with **pinned canonical UUIDs** (V4 `accounting_rule_filter_type` UUIDv7 family, offset 18_000): **legacy 5 `DO_NOT_INVOICE` = `019e2e15-2c00-7658-8000-000000004658`** (index 8), **legacy 55 `START_TAX` = `019e2e15-2c00-7659-8000-000000004659`** (index 9). Updated the canonical-UUID ground truth (`reference-seeds-canonical-uuids.json`) + the `GenerateCanonicalUuids` code array (APPENDED indices 8/9 — never reorder) in lockstep. **No `Coercions` map edit needed:** `legacyIntIdToUuidString(int)` is algorithmic (`new UUID(0, legacyInt)`), NOT a per-filter-type lookup — it already returns a value for 5/55; the real FK resolution is the ingest-side `ReferenceLookup` join `t_accounting_rule_filter_type WHERE legacy_int_id = <n>` (ux_arft_legacy_int_id point lookup), which only needed the seeded row, NOT a Coercions change. (That `ReferenceLookup` declaration on the mapper is T-10's producer-binding job — untouched here.) **Test (cheapest meaningful layer = the existing migration baseline IT):** extended `ReservationsBaselineIntegrationTest` to assert all **10** canonical codes + legacy_int_ids `{5,10,20,30,40,50,55,60,70,80}` are seeded; updated T-07's `AccountingReferenceControllerIT` for the new `legacy_int_id ASC` first row (DO_NOT_INVOICE/5). Full `:server:check` green (V42 applies clean; Flyway order/checksum + arch-guards + cpdRatchet + OpenApiSnapshot held).
- [x] **T-10** — Producer SELECT binding in `MapperLegacyBindings` (extract ArticleNumber from legacy `ArticleTarget` JSON, member-number from `RecipientTarget`, all `Matched*` + flags) + add `deliveryLineText`/`recipientName` to the mapper's `buildFilterConfig` (T-03 added them to the typed `FilterConfig`; mapper must emit them); remove `ACCOUNTING_RULE_FILTER` from `KNOWN_UNBOUND`; **real-producer collision/orphan round-trip IT** for `ux_arf_club_sort_partial` (sort_indicator dedupe/renumber) — reds in `check`, not the fanout. *(migration mapper + IT)*
  - **Producer binding** (`MapperLegacyBindings.ACCOUNTING_RULE_FILTER`): `FROM AccountingRuleFilters WHERE IsDeleted = 0`; `JSON_VALUE` extracts the scalar `ArticleTarget.ArticleNumber`/`RecipientTarget.PersonClubMemberNumber` → `article_target`/`recipient_target` and the descriptive `DeliveryLineText`/`RecipientName` → aliased cols the mapper folds into `filter_config`; `ROW_NUMBER() OVER (PARTITION BY ClubId ORDER BY <CASE NULL-first>, SortIndicator, CreatedOn, id) AS SortIndicator` renumbers per-club so `ux_arf_club_sort_partial` never 23505s; the `CASE WHEN SortIndicator IS NULL` lead-key makes NULL-first ordering dialect-portable (T-SQL NULLS-FIRST vs PG NULLS-LAST). 19-param `INSERT` matches `columns()`.
  - **Mapper reconciliation** (`AccountingRuleFilterMapper`): `buildFilterConfig` now parses each `Matched*` as a **JSON array** (`JsonConvert.SerializeObject(List<…>)` per legacy `:331-340`/`:240-250`), normalising every element (string/int/Guid) to a trimmed `String` — the prior `split(",")` corrupted JSON into one element. Emits `deliveryLineText`/`recipientName` (T-03 `FilterConfig`). Added `referenceLookups()` (`filter_type_id`→`t_accounting_rule_filter_type`, `accounting_unit_type_id`→`t_accounting_unit_type` by `legacy_int_id` — the T-09 finding; without it EVERY type 23503s) and `foreignKeyColumns()` (`operating_club_id`→CLUB, off-convention `@TenantId` — else `fk_accounting_rule_filter_operating_club_id` 23503). Removed `ACCOUNTING_RULE_FILTER` from `MapperBindingContractTest.KNOWN_UNBOUND`.
  - **Proof ITs:** `AccountingRuleFilterProducerDedupeIT` (runs the REAL bound SELECT against a legacy-shaped staging table: two collision rows + a NULL-sort row renumber to distinct 1..3, soft-deleted dropped, clubB partitioned separately, JSON_VALUE scalars extracted; PG-17-gated via `assumeTrue` since SQL/JSON `JSON_VALUE` is PG-17/MSSQL-only) + `AccountingRuleFilterMigrationRoundTripIT` (full handshake→bundle→ingest: `filter_type_id` resolves incl V42 type-5, targets land, `deliveryLineText`/`recipientName` round-trip in `filter_config`, match-list parses JSON not comma, two same-club rows land at distinct `sort_indicator` w/o 23505, tenant isolation). Allow-listed `accountingrulefilters` in `FixtureTableNamingConventionTest`. `:migration-bundle:check` + `:server:check` green (PG-15 LAN skips the JSON_VALUE IT; verified green on a forced PG-17 container).
- [x] **T-11** — Web feature scaffold `features/accounting/`: route + `accounting.store.ts` (load/create/update/delete via orval) + `accounting-list.page.ts` + **nav entry under masterdata (chrome-reachable — spec ENTERS via nav)**. *(web feature)*
  - Regenerated the orval client (`pnpm generate-api` off the committed `openapi.json`): new `accounting-rule-filters/` (NAMED methods `listAccountingRuleFilters`/`getAccountingRuleFilter`/`createAccountingRuleFilter`/`updateAccountingRuleFilter`/`deleteAccountingRuleFilter`) + `accounting-reference-data/` (`listAccountingRuleFilterTypes`/`listAccountingUnitTypes`/`listFlightCrewTypes`) services + model types (`AccountingRuleFilter{ListItem,Detail,WriteRequest,TypeResponse}`, `AccountingUnitTypeResponse`, `FlightCrewTypeResponse`, `FilterConfig`, `MatchList`); `model/index.ts` delta is purely additive — NO positional `getN` renumbering this round (orval fragility didn't bite). `AccountingStore` (signal store, low-CRAP via shared `classifyApiError` + a 3-rule `accountingErrorRules` table 409/403/404→kinds): `loadAll`/`getDetail`/`create`/`update`/`delete` + `loadFilterTypes` (reference catalog → `filterTypeNameById` map for the list type column + T-12's section-driving select). `accounting-list.page.ts` (columns name·type·active·target; `accounting-rules-table` / `accounting-rules-row-<id>` / `accounting-rules-new-button` testids matching the T-01 contract; row→edit nav). `accounting.routes.ts` (`''` list + `new`/`:id/edit` placeholder routes → `AccountingEditPlaceholderPage`, T-12 replaces) registered as `/accountingrules` in `app.routes.ts`. Nav entry `{ path: '/accountingrules', label: 'Accounting rules', icon: 'file-text' }` under `CLUB_ADMIN_SECTIONS` (server gates every CRUD endpoint as CLUB_ADMINISTRATOR → club-admin-only nav, unlike the open flight-types/aircraft entries) → testid `af-nav-section-/accountingrules`. i18n `accounting.*` scope added to de/en/fr/it (en `title`='Accounting rules' — the list test asserts the English h1 under the bare-goto navigator.language fallback). Flipped the **nav-reaches-list** + **list-renders** T-01 fixme tests to active; create/edit/sections/match-list/required/404 stay fixme for T-12/T-13/T-14. Bus events `accounting-rule-filter.{created,updated,deleted}` added. `pnpm preflight:web` green (lint/tsc/ng build/gallery/e2e). **File count note:** the seam is singular (feature scaffold+list+nav) but i18n parity (4 locale files) + the generated delta (excluded per task) inflate the raw touched count above the nominal ≤8.
- [x] **T-12** — `accounting-edit.page.ts` core + conditional sections (article-target / recipient-target / aircraft-filter duration+threshold / no-landing-tax show/hide per filter-type) + J-6b `liveFieldErrors` bar + `classifyApiError`/`withOptionals`. *(web edit — the crux)*
  - Replaced the T-11 placeholder with `AccountingEditPage` at `/accountingrules/new` + `/accountingrules/:id/edit`. Native `<select data-testid="accounting-rules-filter-type">` bound to a string `filterTypeLegacyId` control (Playwright `selectOption` needs a real `<select>`, not the `nz-select`/CDK overlay `af-select`); section visibility is driven off the selected type's **legacyId** via `computed()` blocks (`@if (showArticleTarget())` ∉{5,10} / `showRecipientTarget()`==10 / `showAircraftFilter()`==30 / `showNoLandingTax()`==20), matching `AccountingRuleFiltersEditController.js` predicate fns. Save normalisation mirrors legacy `$scope.save`: unlimited toggle clears min/max (`:142-145`) + derives `!(min>0||max<2147483647)` on load (`:65`), threshold nulled when its include toggle is off (`:139-141`), type-5 sends neither target, off-type targets cleared. Write request sends **both** `filterTypeId` (uuid, looked up from the selected legacyId via `store.filterTypes()`) **and** `filterTypeLegacyId`; `filterConfig` carries all **9 boolean flags** present (defaulted false → no `FAIL_ON_NULL_FOR_PRIMITIVES` 400) + the 10 match-lists at their `{useAllExcept:true, matched:[]}` default (T-13 fills them; loaded values round-tripped via `loadedMatchLists`). `withOptionals` collapses the optional text/target fields. Validation is the J-6b `liveFieldErrors` as-you-type bar (name + filter-type required, NOT touched-gated); Save `[disabled]` gated on a reactive `formStatus = toSignal(form.statusChanges)` signal (J-26 T-09), not `form.invalid`; server 409 (conflict) routed through the name field's `asyncErrors$` slot. Added `loadUnitTypes`/`accountingUnitTypes` to `AccountingStore` for the article-target AccountingUnitType select. i18n `accounting.edit.*` added to de/en/fr/it. Reconciled the T-01 spec mocks to the real client shape (filter-type catalog `{id,code,legacyId,name}`; Detail/WriteRequest/FilterConfig real shapes; section visibility off legacyId). **Activated 3 fixme tests**: create+round-trip, filter-type-drives-sections, required-field-inline-errors (5/7 active now pass; match-list + cross-tenant-404 stay fixme for T-13/T-14). `pnpm preflight:web` green.
- [ ] **T-13** — Match-lists sub-component: the visible predicate lists each with its "use for all except listed" invert toggle (immatriculations, start-types, flight-type-codes, start/landing locations, club-member-numbers, crew-types, homebase, member-states), round-tripping `{useAllExcept, matched[]}`. *(web match-list control)*
- [ ] **T-14** — Thicken the spec to full real assertions from the oracle (filter-type drives sections; match-lists round-trip; cross-tenant 404; migrated-filter renders with config intact) — mock inner-loop + real-idp gate spec; add the AlpenFlight-side accounting parity assertion to the fanout. *(spec + fanout)*
- [ ] **T-15** — *(≤40% rider — maintainability tooling)* Qodana whole-program unused-code: `qodana.yaml` + `qodana-scan` CI job **report-only**, committed baseline (~90% FP measured), Spring/JPA-aware linter, emit into the proof-gallery maintainability panel. *(Qodana rider — _BOYSCOUT J-26)*

**Deferred to the §4 gate / future slices (noted, not pre-committed):** the 3 KC-26 nightly reds (surface at the full real-idp regression; become tasks if they block the gate); the gallery re-arch slices; e2e prettier/tsc; clubadmin4+V29 removal. *(`_BOYSCOUT.md`)*

## Assumptions made

1. `depends_on: [J-1]` per the roadmap — the filter references aircraft/locations/
   flight-types/articles/persons as *reference data* (already migrated/seeded via
   J-0/J-1/J-2 + S-054 impl), not their CRUD screens, so it doesn't block on J-11.
2. One screen/route (`/accountingrules`, list+edit with conditional sections) —
   the intricacy is within one form, so it stays one journey (not a split).
3. The rules ENGINE (consuming these filters) is J-9, NOT in J-8 — J-8 is the
   config surface only (S-073–077 stay J-9).
4. jsonb `filter_config` for the variable predicate set (S-014 schema landed);
   structural invariants in schema, type-specific field rules on the aggregate.
