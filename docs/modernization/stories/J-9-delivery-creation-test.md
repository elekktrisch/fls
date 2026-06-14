---
id: J-9
title: Delivery creation test (rules-engine proof)
epic: E-09
status: in_progress
started_at: 2026-06-14
journey0: false
carved: true
depends_on: [J-8, J-2]
rolls_up: [S-073, S-074, S-075, S-076, S-077, S-079, S-107]
acceptance:
  # ≥feature — the rules engine (the sacred cow) proven through its test-harness screen
  - "[happy] /deliverycreationtests list renders the club's tests (name, active, last-result) tenant-scoped; reachable via the masterdata 'Masterdata' nav dropdown (chrome-reachable — spec ENTERS via the dropdown, per J-8 T-22 nav grouping)"
  - "[happy] create a test: pick a Flight → 'Create test delivery' DRY-RUNS the engine WITHOUT persisting (generateExampleDelivery) → fills the expected DeliveryItem set; save → appears in list; reload round-trips"
  - "[happy] run a test: the engine runs against the stored flight + compares to the expected set → Success when they match; matched AccountingRuleFilter ids link to /accountingrules/:id (J-8)"
  - "[key-error] run a test whose expected set DIFFERS from the engine output → Failure with a cell-level diff (which DeliveryItems differed) — the operator's daily rule-tuning tool"
  - "[happy] FlightTime decrement loop produces TIERED DeliveryItems (e.g. first 30min @ rate A, next 30 @ rate B, remainder @ rate C → 3 items) — the sacred-cow R3 mechanism, bit-exact parity with legacy"
  - "[happy] pipeline stages: IgnoreFlight (DoNotInvoice match → NO delivery); Recipient (first-match-wins → sets recipient); single-pass types (landing/start tax, instructor/fuel/VSF fee) emit one item each"
  - "[happy] glider→tow recursion via TowFlightId — the tow flight's rule output rolls into the glider flight's delivery"
  - "[edge] cross-tenant GET of another club's test → 404 (tenant isolation)"
  - "[edge] every mutation emits an audit event (rules config drives every invoice) — ControllerAuditCoverage + audit-row assertion"
  # done-bar — the combinatorial regression corpus (S-107, C11)
  - "[happy] a combinatorial corpus of representative flight×rule cases reproduces the legacy engine output bit-exact (IT-level; the regression proof the harness exists to run)"
screen: /deliverycreationtests (list + edit + diff) — replacing legacy masterdata/deliveryCreationTests/
headless_pulled_in: the rules engine (S-073–077, IgnoreFlight/Recipient/FlightTime-loop/EngineTime-loop/single-pass/tow-recursion) — homed by the deliveryCreationTests harness (test affordance; the real product consumer is J-10 Deliveries)
migration: N/A — harness entities are greenfield; the engine reads MIGRATED Flights (J-2) + AccountingRuleFilters (J-8) as inputs
parity_test: alpenflight/web/e2e/tests/accounting/delivery-creation-test.spec.ts
mock_test: alpenflight/web/e2e/tests/accounting/   # per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly
adr_refs: [0005, 0008, 0022, 0027]
---

## Context

Proves the **sacred cow** — the billing rules engine that turns a flight + the J-8
`AccountingRuleFilter` rows into `DeliveryItem`s (the invoice lines). J-9 ports the
engine (S-073–077) and homes it in the legacy `deliveryCreationTests` regression
harness: a screen where an admin picks a flight, dry-runs the engine to capture the
expected output, then re-runs it to assert the engine still reproduces it. Proving the
engine in isolation here is the thinnest way to validate it before J-10 (Deliveries)
persists its output as invoice drafts.

## Spec must assert

Grounded in legacy `flsweb/src/masterdata/deliveryCreationTests/` + the engine at
`flsserver/src/FLS.Server.Service/Accounting/RuleEngines/DeliveryItemRulesEngine.cs`
(+ `AccountingRuleFilterFactory.cs`, `RuleBasedAccountingRuleFilterDetails.cs`):

- **Harness screen** (`deliveryCreationTests-edit.html` + `DeliveryCreationTestsEditController.js`):
  list (name/active/last-result, tenant-scoped) + edit (pick `FlightId`, `IsActive`,
  the expected `DeliveryItems` JSON). Two actions: **Create test delivery** = dry-run
  `generateExampleDelivery(flightId)` (engine runs, NOT persisted) fills the expected set;
  **Run test** = `runTest(id)` runs the engine + diffs vs expected → `LastTestSuccessful`
  + `LastTestResultMessage` (the diff) + `LastTestMatchedAccountingRuleFilterIds` (clickable → J-8).
- **The engine pipeline** (the load-bearing parity, `docs/legacy/server.md §3`): IgnoreFlight
  (DoNotInvoice) → Recipient (first-match-wins) → the **FlightTime decrement loop** (R3: each
  matching tier emits a `DeliveryItem` + decrements `ActiveFlightTime`; loop ends when no rule
  matches the remainder) → EngineTime loop → single-pass types → glider→tow recursion via
  `TowFlightId`. **Bit-exact parity** — S-074: translate the loop line-by-line, do NOT rewrite
  from understanding; a legacy bug is documented + raised, never silently fixed (customer invoices
  depend on current behavior).
- **Exact loop semantics + the rule-type → emission contract + first-match-wins ordering** are
  the load-bearing behavior the implementer can't derive — **dispatch `legacy-oracle` at ship time**
  for the engine (the J-8 oracle already flagged these three files as J-9's core).

## Notes

**Headless homing:** the rules engine is headless; the `deliveryCreationTests` harness is its
screen home (the test-affordance tier of the homing order — there's no product screen for the
engine itself until J-10's Deliveries). The harness IS the proof surface.

**Dependency boundary (S-078 is J-10, not here):** J-9 produces `DeliveryItem`s into an in-memory
accumulator (`RuleBasedDeliveryDetails`) for the dry-run + compare; it does NOT need the persisted
`Delivery` aggregate (that's J-10). The harness persists only its own `DeliveryCreationTest` +
`DeliveryCreationTestItem` (the expected-payload entities). So `depends_on: [J-8, J-2]` holds —
the engine reads J-8 `AccountingRuleFilter.filter_config` + J-2 `Flight`/`FlightCrew` as inputs.

**Seam hints (non-binding, one seam each):** the `RuleBasedDeliveryDetails` accumulator +
`DeliveryItem` value object; `IgnoreFlight` + `Recipient` engine stages (S-073); the **FlightTime
decrement loop** (S-074 — its own seam, highest-risk, line-by-line port + side-by-side review);
EngineTime loop (S-075); single-pass rule types (S-076); glider→tow recursion (S-077); the
`DeliveryCreationTest` aggregate + repo (`@TenantId`, JPA-first per ADR 0027); the run + dry-run
endpoints (`/deliverycreationtests/:id/run`, `/example/:flightId`); the `accounting` SPA harness
(list + edit + the **diff-rendering UI**); the combinatorial corpus ITs (S-107, C11); the parity spec.

**Migration:** N/A (harness). The engine consumes migrated Flights + J-8 filters; no new mapper.
Confirm at ship time whether legacy `DeliveryCreationTest` runs in a job or only on-demand (S-079
open item) — if on-demand only, the harness is greenfield-create, no migration.

**Cross-cutting riders to fold (`_BOYSCOUT.md`, ≤70% burndown window):** this journey LEADS with
the rules engine — the highest-risk, bit-exact feature in the rewrite — so it is **feature-heavy by
nature**; `/do-ship` should fold only the burndown riders that touch J-9's surface (COMMENT-STRIP
per-touch, HISTORY→GIT, HELPER-PRUNE, and J-9's slice of GALLERY-SIMPLIFY since it produces a gallery)
and **defer the heavy infra rewrites (full GALLERY-SIMPLIFY, WORKFLOW-SLIM sharding) to a lighter
burndown journey (J-10/J-11)** — the ≤70% debt is a ceiling, not a floor. Build the new screen + engine
to the new discipline from the start: **why-only comments, self-explanatory code, contract-only journey
body** ([[feedback_self_explanatory_no_history_comments]]); the new harness form on the J-6b
`liveFieldErrors` bar + the shared `shared/util/form/` helpers; the nav entry nests under the J-8
"Masterdata" dropdown (`enterViaNav`).

## Going-in state + operator decisions (ship-time, verified)

**Substrate (V4, like J-8's accounting):** `t_delivery` / `t_delivery_item` / `t_delivery_creation_test` (+`_item`) schemas ALL exist; `DeliveryMapper`+`DeliveryItemMapper` authored + registered but `KNOWN_UNBOUND` (no producer binding — **J-10's**, leave them). **All domain code + the rules engine are ABSENT** — J-9 builds them. The engine reads J-8 `AccountingRuleFilter.filter_config` + J-2 `Flight` (towFlightId, crew).

**Engine pipeline ORDER = the legacy CODE order (NOT doc §3 / the carve):** IgnoreFlight(5) → Recipient(10) → DeliveryDetails → DeliveryItem[ NoLandingTax(20) → FlightTime(30) loop → EngineTime(80) loop → InstructorFee(40) → **tow-recurse** → AdditionalFuelFee(50) → StartTax(55) → LandingTax(60)+OnStartLocation → VsfFee(70)+OnStartLocation ]. FlightTime loop: `min < active ≤ max` (min-EXCL/max-INCL), bill `active−min`, reset active→min; min=0 → bill all. Zero→none; tier-gap→silent unbilled remainder (legacy, reproduce); dup-article→add-to-existing line; InstructorFee always-new-line.

**Operator decisions (2026-06-14, sacred-cow scoping):**
- **Credits DEFERRED.** J-9 ports the **pure decrement path** (seed no credits; engine ignores PersonFlightTimeCredit balances/DiscountInPercent/over-credit split/transaction writes). The credit sub-engine is a **named follow-up journey** (file via `/do-plan` — own corpus + the `PersonFlightTimeCreditTransaction` side-effects).
- **Filter order = `ORDER BY sort_indicator, id`** (deterministic; documented divergence from legacy's no-ORDER-BY bug, which made recipient first-match + tier order depend on clustered-PK/GUID order).

## Tasks

Per do-ship §2 (one seam each). J-9 LEADS with the rules-engine feature; the ≤70% burndown riders ride it (WORKFLOW-SLIM early — it greens main's red `alpenflight e2e` + speeds J-9's own gate). Migration: no new mapper (Delivery mappers are J-10's; harness greenfield) — the done-bar proves the engine over MIGRATED J-2 flights + J-8 filters (T-19 fanout).

- [x] **T-01** — spec stub `e2e/tests/accounting/delivery-creation-test.spec.ts` (list / edit / dry-run / run+diff; ENTERS via the Masterdata nav dropdown) + scaffold the per-journey gallery page (current-journey-only model). *(e2e + gallery)*
- [x] **T-02** — gate scoping: J-9 `mock_test`/`parity_test` frontmatter so per-push runs only J-9's specs. *(ci.yml + frontmatter)*
- [ ] **T-03** — *(≤70% rider — WORKFLOW-SLIM; greens main)* shard the mock chromium suite into a parallel `--shard=i/n` matrix (`reporter: blob` + a `merge-reports` deploy job, each shard <5min) + move `workers` top-level in `playwright.config.ts` (+`--workers=1` real-idp); keeps the 5-min ceiling, greens `alpenflight e2e`. *(ci.yml + alpenflight-e2e.yml + playwright.config)*
- [ ] **T-04** — *(≤70% rider — HELPER-PRUNE)* delete the 3 verified-redundant `@helper` cases in `forms/validation-hardening.spec.ts` (covered by FlightTypeDuplicateCodeIT / ClubsControllerIT / FlightTypeDomainTest). *(spec)*
- [ ] **T-05** — engine value objects: `DeliveryItemDetails` + `RuleBasedDeliveryDetails` accumulator (in-memory; `GetUnitQuantity`/`GetUnitTypeString` Min/Sec/Ldgs/StartOrFlight conversions) + domain unit tests. *(accounting/domain)*
- [ ] **T-06** — the matching framework (`BaseAccountingRule`-equiv): AND of aircraft-type bitmask + immat + start-type + flight-type(+extend) + start/ldg locations + crew(member#/crew-type/member-state) + homebase, over a Flight × FilterConfig; the `useAllExcept × empty/non-empty` matrix. *(accounting/domain)*
- [ ] **T-07** — *(S-073)* IgnoreFlight(5) short-circuit + Recipient(10) first-match-wins (`stopRuleEngineWhenApplied`) + the 2 FlightCostPaidBy fallback rules + unit tests. *(accounting/domain)*
- [ ] **T-08** — *(S-074 — HIGHEST RISK, line-by-line + side-by-side review)* the FlightTime decrement loop (min-excl/max-incl Between; bill active−min, reset→min; tiered/zero/gap/dup-article) + unit tests. *(accounting/domain)*
- [ ] **T-09** — *(S-075)* the EngineTime decrement loop (engine-counter delta; same loop; no credit branch) + unit tests. *(accounting/domain)*
- [ ] **T-10** — *(S-076)* single-pass rule types: NoLandingTax(20)+suppression flags, LandingTax(60)+OnStartLocation, StartTax(55), InstructorFee(40 always-new-line), AdditionalFuelFee(50), VsfFee(70)+OnStartLocation + unit tests. *(accounting/domain — may overflow → split)*
- [ ] **T-11** — *(S-077)* glider→tow recursion via `towFlightId` (recurse the line-item pipeline on the tow flight, shared accumulator, position continuity) + unit tests. *(accounting/domain)*
- [ ] **T-12** — the engine orchestrator: runs the stages in the legacy CODE order; loads active filters `ORDER BY sort_indicator, id` (operator); credits-absent (operator); emits `RuleBasedDeliveryDetails` + matched-filter-ids. *(accounting/application)*
- [ ] **T-13** — `DeliveryCreationTest` aggregate + repo mapping the existing V4 `t_delivery_creation_test`(+item) (`@TenantId`, jsonb `expected_delivery`, the 9 `Ignore*` flags, `must_not_create…`, the `last_test_*` run-state) + domain tests. *(accounting/domain+infra)*
- [ ] **T-14** — `DeliveryCreationTestsService` + DTOs + controller (list/get/create/update/delete) + audit (ControllerAuditCoverage + AuditRedaction) + cross-tenant 404. *(accounting/application+web — may overflow → split CRUD vs endpoints)*
- [ ] **T-15** — the dry-run endpoint (`generateExampleDelivery`: run engine, return DeliveryItems, NO persist) + the run-test endpoint (run engine vs `expected_delivery`, persist `last_test_*`, the exact field-by-field diff gated by `Ignore*` flags, matched-rule-ids). *(accounting/application+web)*
- [ ] **T-16** — web feature scaffold: orval regen + `features/accounting/` deliverycreationtests route + store + list page + **nav entry under the Masterdata dropdown (chrome-reachable)**. *(web)*
- [ ] **T-17** — edit page: pick Flight → "Create test delivery" (dry-run) fills expected items → save/round-trip; "Run test" → Success/Failure; matched-rule links → `/accountingrules`; J-6b `liveFieldErrors`. *(web)*
- [ ] **T-18** — the **diff-rendering UI** (run-failure → which DeliveryItems differed; the operator's daily rule-tuning tool, S-079). *(web)*
- [ ] **T-19** — thicken the spec to full real assertions (tiered FlightTime items; IgnoreFlight→no delivery; recipient first-match; tow recursion; diff on mismatch; cross-tenant 404) — mock inner-loop + real-idp gate spec; point `parity_test` at the real-idp spec. *(spec)*
- [ ] **T-20** — *(S-107)* the combinatorial corpus (C11): representative flight×rule cases reproducing the legacy engine bit-exact, as ITs; + the **fanout assertion** running the engine/harness over MIGRATED J-2 flights + J-8 filters (the engine done-bar; no new mapper). *(IT + fanout)*

**Deferred (filed, not built here):** the **PersonFlightTimeCredit/discount sub-engine** → a new `/do-plan` roadmap journey (operator-chosen defer). The heavy **GALLERY-SIMPLIFY** multi-journey-plumbing deletion + the project-wide **COMMENT-STRIP**/**HISTORY→GIT** sweeps ride a lighter burndown journey (J-9 builds new code to the why-only/contract-only discipline from the start; per-touch strip only). LEGACY-BUG noted for the gate: tier-gap silent unbilled remainder — reproduced, surfaced to operator, not "fixed".

## Assumptions made

1. `depends_on: [J-8, J-2]` — the engine reads J-8 `AccountingRuleFilter` + J-2 `Flight` as inputs
   (both done/merged); S-078 `Delivery` persistence is J-10, so J-9 uses the in-memory `DeliveryItem`
   accumulator only (dry-run + compare), not the persisted aggregate.
2. One screen/route (`/deliverycreationtests`, list + edit + diff); the rules engine is headless,
   homed by this harness (no separate journey for the engine).
3. Migration N/A — harness entities greenfield; no per-journey mapper (the inputs are already migrated).
4. S-107's combinatorial corpus is the regression proof, run primarily at IT level (bit-exact vs the
   legacy engine) with one representative case driven through the e2e harness.
