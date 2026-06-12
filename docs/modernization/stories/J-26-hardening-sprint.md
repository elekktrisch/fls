---
id: J-26
title: Hardening sprint — validation bugfixes, UX corrections, JDBC retirement, redundancy purge
epic: cross-cutting (E-06/E-07/E-08 surfaces; E-13 proof infra)
status: in_progress
started_at: 2026-06-12
journey0: false
carved: true
depends_on: [J-7]
rolls_up: []   # operator-directed hardening — absorbs _BOYSCOUT riders, not S-stories
acceptance:
  # ——— User-facing share: BUGFIXES + UX CORRECTIONS (all currently-broken behavior; no new features) ———
  - "[happy] Person edit round-trips the membership fields — memberNumber, memberState, isGlider/Motor/TowPilot — through Save → reload (today the form hydrates + toasts success but silently DROPS them: data loss; PUT /persons/{id}/clubs/current exists, is never called)"
  - "[key-error] Flight-type duplicate FlightCode → inline 409 on the flightCode field (today a raw 500, reproducing the legacy bug); the 409 routes by problem-detail `field` — name vs code no longer collapse onto flightTypeName"
  - "[key-error] Flight-type Instructor × Observer both set → blocked by a client cross-field validator AND rejected by a domain XOR guard in FlightType.updateFlags (ADR 0022 §2 — domain is the must-have; today NO layer enforces it)"
  - "[key-error] Club duplicate clubKey → 409 labeled on the clubKey field (today mislabeled as a slug error on the wrong field — ClubsService maps ANY DataIntegrityViolation → SlugAlreadyExists)"
  - "[happy] af-field-errors renders TRANSLATED messages — no raw `common.errors.*` i18n key visible anywhere (today the key renders verbatim)"
  - "[happy] As-you-type debounced (~200ms) inline validation live on EVERY edit form (the J-6b bar, today wired only on reservation-edit + planning-edit) and the ~30 silent af-form-field `[errors]` bindings bound; spec asserts the representative trio aircraft / person / flight-type"
  - "[edge] Flight edit has client required validators (flightDate/aircraft/pilot), Save gated on form validity; the dead never-invoked FlightValidator is wired or deleted"
  - "[edge] Reservation Save disable state never disagrees with form validity (the async second-crew-validator race: button shows enabled while form.invalid, click dead-ends)"
  - "[happy] Profile Account languageId required validator restored (legacy parity regression)"
  # ——— Tech-debt share: proven by build/suite/gallery, behavior-neutral ———
  - "[debt] Main-code JDBC/native SQL = register-only: LanguageCodeLookup, JpaClubStateRepository, JpaCountryRepository, PlanningDayPersistenceProbeImpl converted to JPA/domain ports; EVERY remaining register entry re-affirmed with current rationale or retired (ADR 0027 shrinking list)"
  - "[debt] Shared IT seeding helpers (TwoClubFixture, TenantScopedRowBuilders, Sweep factories) seed via production code (reflection only for non-settable attrs, ADR 0027 §3) — converts the bulk of the 83 raw-JDBC test files transitively"
  - "[debt] Redundancy purge: lifecycle @MappedSuperclass verdict EXECUTED (extract or ADR-recorded decline — stop re-litigating in cpd-baseline); cpd-baseline ratchets DOWN from 4883; fallow dead code removed (3 files, 6 deps, 1 unresolved import); the shared form↔request + errorPatch helper extraction absorbs the aircraft/users/flights/persons edit-page hotspots (CRAP ≤ threshold)"
  - "[infra] A red gate is diagnosable + honest: test-results/** uploaded on failure BEFORE the gallery step mutates the dir; the deployed gallery guard asserts staged == rendered for BOTH sides of every declared shot-pair; the nightly alpenflight-e2e-real-idp.yml is FIXED (setup-java — red 12 nights straight on JVM 11 vs Gradle 9.4.1, the cross-journey regression never ran) and dispatched GREEN on this branch"
  - "[regression] Full-unskip suite green at the gate — every refactor (JDBC retirement, helper conversion, dedup extraction) is behavior-neutral, proven by the existing specs + ITs passing unchanged"
screen: cross-cutting — hardening of shipped screens (/persons, /flighttypes, /clubs, /flights, /reservations, /profile + shared form infra); no new route
headless_pulled_in: none new
migration: N/A — no new entity
parity_test: alpenflight/web/e2e/tests/real-idp/hardening-J26.spec.ts
mock_test: alpenflight/web/e2e/tests/(forms/validation-hardening|persons/persons-edit-membership)   # provisional — T-02 finalizes the stems per the J-6b regex convention
adr_refs: [0022, 0027, 0026, 0008, 0021]
---

## Context

Operator-directed sprint (2026-06-12): **no new features — bugfixes, corrections,
UX and tech-debt only**, in production AND test code. This deliberately inverts
the 60/40 journey rule for one sprint; the user-facing share is the accumulated
P0/P1 validation bugfixes (one of them real data loss) + the as-you-type UX bar
J-6b set but only wired on two forms. The debt share executes the standing
ADR-0027 JDBC retirement, the redundancy riders, and the two
[NEXT-JOURNEY PRIORITY] proof-infra riders — plus the newly-diagnosed nightly
real-idp breakage (red since ≥2026-06-01, unnoticed: missing setup-java).

## Spec must assert

Two new specs, both ENTERING through the chrome (nav → form, never bare goto —
do-ship done-bar):

- **`forms/validation-hardening.spec.ts` (mock inner loop):** the four
  [key-error] fixes above as inline-field assertions (duplicate flightCode →
  flightCode field; XOR blocked; clubKey 409 on clubKey; translated error text);
  the as-you-type trio (aircraft/person/flight-type) shows debounced inline
  errors while typing + clears on valid; flight-edit Save disabled until
  required fields present; reservation Save never enabled while invalid.
  Grounded in `docs/modernization/form-validation-parity-audit.md` (legacy =
  minimum bar, operator 2026-06-09).
- **`real-idp/hardening-J26.spec.ts` (parity_test, heavy chain):** a REAL
  principal edits a Person's membership (memberNumber + role toggle) → Save →
  re-open → values persisted server-side (the data-loss fix, over the real
  backend + Keycloak — no page.route, no @mocked seams); plus one real-chain
  duplicate-flightCode 409 assert (FE→real endpoint→real constraint→inline).
- **Regression posture:** the debt half has NO new spec of its own — its proof
  is the FULL existing suite (ITs + mock + real-idp + fanout) green on the
  final sha, jobs verified EXECUTED (do-ship §4), after the native-SQL
  deletions, helper conversion, and extractions.

No design reference applies (no new screen; existing screens keep their shipped
ADR-0024 structure).

## Notes

**Seam hints (non-binding, one seam each):** persons.store update → wire
`PUT /persons/{id}/clubs/current` (endpoint exists, `PersonsController.java:139-144`);
FlightTypesExceptionHandler DIVE→409 discrimination + service pre-check + store
409 field-routing (`flight-types.store.ts:229-236`); FlightType.updateFlags XOR
+ client cross-field validator; ClubsService DIVE discrimination (`ux_club_key`
vs `ux_club_slug`); af-field-errors transloco; per-form `liveFieldErrors`
adoption + `[errors]` bindings (one sweep, decomposed per form);
flight-form client validators + FlightValidator wire-or-delete; reservation-edit
Save-disable vs async validator; profile-account languageId;
LanguageCodeLookup → the RM-4 `Language` entity; JpaClubStateRepository +
JpaCountryRepository → JPQL/derived; PlanningDayPersistenceProbeImpl → a
reservations-module count port (`@NamedInterface`, per the register's own
Remove-when); register re-affirm pass (AircraftReservationConflictProbeImpl —
decide keep-GiST vs JPQL and RECORD it; ShowcaseSeeder; JpaPersonRepository
cross-tenant check; the four structurally-pre-tenant stays); TwoClubFixture /
TenantScopedRowBuilders / Sweep-factory production-code seeding (+ class-unique
club ids per the RM-5 convention); lifecycle @MappedSuperclass extract-or-ADR;
FE form↔request + errorPatch helper extraction; fallow dead-code deletion;
ci.yml/fanout test-results upload-on-failure (pre-gallery); fanout add_shot →
screenshots.json → generator single source of truth + deployed both-sides
guard; alpenflight-e2e-real-idp.yml setup-java 21 (mirror ci.yml) + dispatch
on this branch to re-baseline 12 days of unrun cross-journey regression.

**Ride-if-budget (ordered; unfinished items STAY in _BOYSCOUT):** e2e prettier
normalization (~42 files); e2e tsc-strictness (~23 errors); orval explicit
operationIds (kill positional getN); clubadmin4 + V29 removal; op-field-mutate
assertion; planning `:410` warm-nav reopen; producer-dedupe soft-delete guard
comment/filter; ingest constraint-name surfacing (dev/test); fanout
reporting-migration-parity assert + stale step name; CI fail-aggregate;
nightly-red visibility (the 12-day unnoticed red — surface scheduled-run
failures on the gallery index or via notification).

**Excluded (recorded, not silently dropped):** P4 `/validate` pre-check
endpoints (new endpoint surface ≈ new feature — deferred); the full gallery
re-arch beyond the staged==rendered structural fix (next journeys' 40%
budgets, per operator); JIT-username multi-IdP security review; S-189 +
CLUB-pgcopy (migration scope, pre-J-21).

## Tasks

- [ ] **T-01** — spec stub + proof page: author `forms/validation-hardening.spec.ts` + `real-idp/hardening-J26.spec.ts` structure/selectors/flow (thin assertions, enter via nav) AND scaffold the J-26 per-journey gallery page + link it from the persistent index.
- [ ] **T-02** — per-push gate scope: finalize `mock_test:` stems (J-6b regex convention) + verify BOTH ci.yml derive steps resolve J-26 (mock filter + `parity_test` real-idp spec, `is_baseline=false`); actionlint.
- [ ] **T-03** — fix nightly `alpenflight-e2e-real-idp.yml`: add setup-java 21 (mirror ci.yml), then `gh workflow run` it on THIS branch and verify JOB-level it reaches the Playwright specs — 12 days of unrun cross-journey regression re-baselined; any latent red becomes a new T-NN.
- [ ] **T-04** — persons membership data-loss fix: wire `PUT /persons/{id}/clubs/current` into the persons.store update path (fields already hydrated, request omits them) + mock spec case asserting memberNumber/state/role-toggle round-trip.
- [ ] **T-05** — flight-type duplicate FlightCode: `@ExceptionHandler(DataIntegrityViolationException)` discriminating `ux_flight_type_club_code` → 409 `field=flightCode` (mirror LocationsExceptionHandler) + `findActiveByCode` pre-check + store/page 409 field-routing (name vs code) + IT + spec case.
- [ ] **T-06** — flight-type Instructor×Observer XOR: domain guard in `FlightType.updateFlags` (must-have, ADR 0022 §2) + client cross-field validator + IT + spec case.
- [ ] **T-07** — club duplicate clubKey: `ClubsService.persist()` DIVE discrimination `ux_club_key` vs `ux_club_slug` → clean clubKey 409 + spec case.
- [ ] **T-08** — `af-field-errors` transloco translation (renders raw i18n key today) + profile-account `languageId` required validator + spec asserts.
- [ ] **T-09** — reservation-edit Save-disable binding vs async second-crew validator race: disable state must track form validity; spec case.
- [ ] **T-10** — as-you-type sweep A: aircraft + article + club edit forms — bind the silent `[errors]` fields (P3) + adopt debounced `liveFieldErrors` (P2); spec asserts aircraft as representative.
- [ ] **T-11** — as-you-type sweep B: flight-type + location (incl. IOP rows) + person edit forms — same; spec asserts person + flight-type.
- [ ] **T-12** — as-you-type sweep C: planning-setup + user (+ roles-≥1 live) + profile 4 tabs — same.
- [ ] **T-13** — flight edit: client required validators (flightDate/aircraft/pilot) + Save gated on validity + the dead `FlightValidator` wire-or-delete VERDICT recorded; spec case.
- [ ] **T-14** — JDBC: `LanguageCodeLookup` → the RM-4 `Language` JPA repo (delete JdbcTemplate).
- [ ] **T-15** — JDBC: `JpaClubStateRepository` + `JpaCountryRepository` native → JPQL/derived queries.
- [ ] **T-16** — JDBC: `PlanningDayPersistenceProbeImpl` → a reservations-module count port (`@NamedInterface`, per the register's own Remove-when) + retire the `planning-day-reservation-count` register entry + Modulith/arch guards green.
- [ ] **T-17** — register re-affirm pass: conflict-probe keep-GiST-vs-JPQL decision RECORDED; ShowcaseSeeder + persons cross-tenant entries re-affirmed or retired; register doc current.
- [ ] **T-18** — IT seeding: `TenantScopedRowBuilders` (+ `TenantScopedEntityCatalog`) → production-code seeding (reflection only for non-settables); full `check` green on LAN PG.
- [ ] **T-19** — IT seeding: `TwoClubFixture` → production-code seeding, consumers compile-compatible; full `check` green.
- [ ] **T-20** — IT seeding: Sweep factories → production code; inventory remaining direct-JDBC test files, leftovers recorded per-touch in `_BOYSCOUT.md`.
- [ ] **T-21** — lifecycle `@MappedSuperclass` verdict EXECUTED: extract the shared softDelete + saved-event base across the 5 aggregates (or ADR-recorded decline); cpd-baseline ratchets DOWN from 4883.
- [ ] **T-22** — FE hotspot extraction 1: shared form↔request + `errorPatch` helper; absorb `aircraft-edit.page.ts` + `users-edit.page.ts`/`users.store.ts`.
- [ ] **T-23** — FE hotspot extraction 2: `flights-edit.page.ts finalSubmit` + `flight-form.defaults.ts applyLastContextThenPrefs` (CRAP 210) + `persons-edit.page.ts hydrate` onto the helper.
- [ ] **T-24** — fallow dead code: 3 unused files + 6 unused deps + 1 unresolved import deleted; fallow snapshot improves.
- [ ] **T-25** — proof infra: upload `test-results/**` (error-context.md + trace.zip) as a failure artifact in ci.yml proof + fanout BEFORE the gallery step mutates the dir.
- [ ] **T-26** — proof infra: staged==rendered single source of truth (fanout `add_shot` json emission ↔ `generate-gallery.mjs` pairing) + deployed-journey guard asserts BOTH sides of every declared pair render.
- [ ] **T-27** — thicken both specs to full real assertions; gallery pairing complete; clear every shipped `_BOYSCOUT.md` bullet; full-unskip prep for the gate.

## Assumptions made

1. The 60/40 rule is inverted for THIS sprint by explicit operator direction
   ("no new features… tech-debt, cleanup and ux only"); the user-facing share
   is bugfixes/UX corrections, still provable by one green Playwright run.
2. Multi-screen by design — hardening sprints span shipped screens (J-6b
   precedent); the one-screen rule applies to feature journeys.
3. The nightly real-idp fix RIDES this journey (no-tiny-stories); main's
   nightly stays red until merge — operator may request a hotfix PR instead.
4. Journey id J-26 (next free; J-8…J-25 assigned). Ships before J-8 — order ≠ id.
