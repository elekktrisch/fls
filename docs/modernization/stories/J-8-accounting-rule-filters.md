---
id: J-8
title: Accounting rule filters
epic: E-09
status: todo
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
