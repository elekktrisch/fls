---
id: J-27
title: Migration-fidelity sprint — drive the fanout fully green
epic: E-02
status: in_progress
started_at: 2026-06-19
journey0: false
carved: true
depends_on: [J-10]
rolls_up: []          # repairs already-merged migrated parity (J-0c/J-8/J-9); no horizontal story
acceptance:
  - "[happy] The proof-fanout's `Run AlpenFlight parity specs` step runs ALL 7 migrated-data specs GREEN over the single real exported bundle (3 are red today: J-0c, J-8, J-9)."
  - "[migration/parity] club-B admin sees its OWN fanned-out copy of the migrated Location under the random name (fan-out-migration-parity.spec.ts:167)."
  - "[migration/parity] the migrated `FlightTime: Glider per minute` AccountingRuleFilter renders with predicate intact: articleTarget='5001', filterConfig.deliveryLineText='Glider flight minutes', list target column='5001 (Glider flight minutes)' (accounting-rules-parity.spec.ts:524)."
  - "[migration/parity] the engine, run over a migrated glider flight, emits the migrated FlightTime filter's article-5001 line — unitType='Minuten', quantity=47 (delivery-creation-test-parity.spec.ts:577)."
  - "[edge] the per-club fanned-out copies stay edit-isolated (distinct rows); the gallery renders each migrated club's data per-club."
screen: none — no new screen; the migrated data renders on the existing /locations, /accountingrules, /deliverycreationtests screens (the parity specs drive those).
headless_pulled_in: none
migration: N/A — repairs migrated parity of already-built mappers (Location, AccountingRuleFilter, the FlightTime engine path); no new mapper.
parity_test: alpenflight/web/e2e/tests/real-idp/{fan-out-migration-parity,accounting-rules-parity,delivery-creation-test-parity}.spec.ts (the migrated-data blocks), gated by the proof-fanout `Run AlpenFlight parity specs` step.
adr_refs: [0008, 0003, 0022]
---

## Context

J-10's gate ran the real-bundle fanout end-to-end for the first time (it fixed the
legacy builds + the ingest-409 that had silently skipped it on every prior branch),
revealing that **the merged migration journeys' migrated done-bars were hollow all
along**: 3 of the 7 AlpenFlight parity specs are red over real migrated data. Every
future migration journey (J-11, J-10b, J-1 re-prove, J-21) inherits this broken gate.
This journey drives the fanout fully green so the migration promise — *a migrated club
sees its own real legacy data, correctly* — is true and glanceable per-club in the
gallery. Operator-sanctioned tech-debt (J-10 retro, 2026-06-19); rides the debt-burndown
window. No new AlpenFlight feature; the user-visible value is migrated data rendering
correctly. [[feedback_verify_infra_is_run_not_just_authored]] [[feedback_demonstrable_proof_prefer_ui]]

## Spec must assert

The contract is the proof-fanout **`Run AlpenFlight parity specs`** step
(`alpenflight-proof-fanout.yml:780-817`, `J0C_BUNDLE_SOURCE=real`) going fully green —
the 7 migrated-data specs run over the SINGLE real exported bundle. Three are red today:

1. **J-0c — Location fan-out render** (`fan-out-migration-parity.spec.ts:167`). club-B's
   admin can't locate its OWN fanned-out copy of the migrated Location by name
   (`locationIdByName` returns falsy). Prove club-B's distinct fanned-out row renders
   under the same random name (club-A already passes at :146).
2. **J-8 — AccountingRuleFilter predicate** (`accounting-rules-parity.spec.ts:524`). The
   migrated `FlightTime: Glider per minute` filter renders but its `filter_config` didn't
   round-trip: assert `articleTarget==='5001'`, `filterConfig.deliveryLineText==='Glider
   flight minutes'`, and the derived list target `'5001 (Glider flight minutes)'`. This is
   the T-10 reconciliation point — the producer SELECT's `JSON_VALUE` scalar extraction of
   the legacy `ArticleTarget` + the `buildFilterConfig` `DeliveryLineText` fold.
3. **J-9 — article-5001 engine** (`delivery-creation-test-parity.spec.ts:577`). The engine
   dry-run over a migrated glider flight must emit the migrated filter's article-5001 line
   (`unitType==='Minuten'`, `quantity===47`). **Downstream of #2** — if the filter's
   predicate didn't migrate intact it can't match the flight. Fix the mapper (#2) first;
   verify the migrated filter's predicate/scope actually applies over the migrated §5
   historical glider flight (T-07 already ruled out a read race — the ingest polls to
   COMPLETED before the read).

## Notes

- **Coupling:** #2 (filter mapper) is upstream of #3 (engine application) — same legacy row
  (article 5001). Likely one fix clears both; #1 (Location fan-out render) is independent.
  Order the work mapper-first → re-run the J-9 block → then the independent Location render.
- **No new screen** — assert on the existing rendered screens (skill: stated explicitly).
- **Done-bar:** the fanout parity step is NON-required (it did not block J-10's merge), so
  J-27's evidence is that step going green on its branch + the per-club migrated data
  rendering in the gallery — NOT a required-gate flip. [[project_false_green_derive_fallback]]
  [[project_synth_bundle_doesnt_validate_producer_select]] (only the real export validates
  the producer SELECT — these reds only surface there).
- **Seams** (non-binding, for /do-ship; one mapper/engine each):
  - AccountingRuleFilter producer SELECT `JSON_VALUE(ArticleTarget)` + `buildFilterConfig`
    `DeliveryLineText` fold → `AccountingRuleFilterMapper.java` + `FilterConfig.java`.
  - Migrated-filter engine application over the migrated glider flight → `EngineTimeStage.java`
    / the engine orchestrator (predicate scope vs the migrated flight; not timing).
  - Location club-B fan-out render → Location producer SELECT + `ForeignKeyResolver` /
    `legacy_guid` keying (the J-0b fan-out subsystem).
- **Riders on this journey's surface** (`/do-ship` folds per the ≤70% burndown budget — this
  journey IS the fanout/CI surface, so the burndown spike is a natural co-resident):
  - **[FANOUT-SPEC-WIRING]** wire `reporting-migration-parity` into the fanout spec list + fix
    the stale step name (it predates J-5/J-6/J-7) — `_BOYSCOUT.md`.
  - **[GALLERY-SIMPLIFY]** + **[WORKFLOW-SLIM]** — both heavily edit `alpenflight-proof-fanout.yml`
    (J-27's home file); WORKFLOW-SLIM absorbs **[PROOF-HARNESS TRANSIENTS]** (the 60s gh-pages
    link-check timeout + the KC-26 step-timeout). The burndown spike clears here.
  - **[COMMENT-STRIP]** per-touch on the mapper files this journey edits (`MapperLegacyBindings.java`
    is the still-pending one) + **[HISTORY→GIT]** on any journey file touched.
- The J-0c rename/distinct-rows test (`fan-out-migration-parity.spec.ts:197`) stays
  operator-disabled on the load-starved fanout runner; J-27 does NOT re-enable it (a
  fanout-perf/sharding rider owns that). [[feedback_proof_gallery_per_journey_one_bookmark]]

## Tasks

The 3 parity reds are independent fixes except T-02 (downstream of T-01 — same legacy
row). Each fidelity task ships a real-producer round-trip IT so it reds in `check`
(minutes), not the ~20-min fanout. Per-touch comment-strip/history→git rides each task's
files. GALLERY-SIMPLIFY + WORKFLOW-SLIM deliberately deferred to J-11 (the next feature
journey) — J-27 is the pure-debt exception, not a burndown host.

- [x] **T-01** — AccountingRuleFilter migration fidelity. Producer SELECT `JSON_VALUE(ArticleTarget)`
  scalar extraction + `buildFilterConfig` `DeliveryLineText` fold, so the migrated `FlightTime: Glider
  per minute` filter carries `articleTarget='5001'` + `filterConfig.deliveryLineText='Glider flight
  minutes'` + derived target `'5001 (Glider flight minutes)'`. Real-producer round-trip IT. Clears
  `accounting-rules-parity.spec.ts:524`. *(AccountingRuleFilterMapper + FilterConfig)*
- [x] ~~**T-02** — premise "T-01 fully clears it, no engine fix" was WRONG at the gate.~~ The regression IT
  (`AccountingDeliveryEngineIT`) uses a SYNTH-shaped filter + passes, but the gate (`:577`) stays red: the
  ACTUALLY-migrated FlightTime filter's predicate/scope doesn't match the migrated glider flight (synth-vs-real
  again). The engine is correct; the migrated filter's SCOPE columns are not. The IT stays as engine coverage.
- [x] **T-02b** *(gate-revealed)* — the migrated FlightTime filter's **predicate/scope** must round-trip so it
  matches the migrated §5 glider flight → engine emits the article-5001 line (`Minuten`/`47`). Fixed the mapper's
  bigint→Integer read of the legacy `Min/Max(Flight|Engine)TimeInSecondsMatchingValue` columns (read as `Long`
  + clamp); `AccountingRuleFilterProducerDedupeIT` extended with a glider-scoped FlightTime row over BIGINT time
  columns asserting glider scope + min=0 + max-clamp round-trip through the real producer SELECT → mapper (CI PG17;
  skips on LAN PG15 — JSON_VALUE). *(AccountingRuleFilter producer SELECT/mapper — scope columns)*
  - **ESCALATION:** the stated suspects are ruled out empirically — in FLSTest the time columns are `int` (DBUpdate
    v1.9.17 re-ALTERs from v1.9.14's `bigint`), scope flags are identity-projected, type 30 / unit 10 resolve
    (`AccountingRuleFilterMigrationRoundTripIT`), and `accounting-rules-parity.spec.ts:524` proves the filter migrates
    with article 5001. The bigint fix is a real latent bug (pgjdbc-fatal; >Int.MAX values) but may NOT flip `:577` in
    FLSTest. If the CI PG17 run shows the new IT green-without-the-fix, the residual `:577` cause is flight-side (the
    §5 glider's crew / aircraft-type bit gating `crewMatches`/`flightAircraftTypeMatches`) — a follow-up outside the
    filter scope.
- [ ] **T-05** *(gate-revealed, J-6 hollow done-bar)* — migrated planning-day notification emits the WRONG
  template: mailpit got "Flugbetriebstag abgesagt" (cancelled) where only "findet statt" (takes place) is
  expected (`planning-migration-parity.spec.ts:901`). App-side template selection in `PlanningDayNotificationJob`
  for the migrated/seeded day's status. *(PlanningDayNotificationJob template-selection branch)*
- [x] ~~**T-03** — Location club-B fan-out render via the producer SELECT / ForeignKeyResolver seam.~~
  **Re-scoped:** the named seam is proven CLEAN — `LocationFanOutProducerSelectIT` (committed, green) shows
  the producer SELECT fans the shared Location to a distinct per-club row; the real-bundle API reads
  `owners.length===2`. The IT stays as the now-closed producer-SELECT fan-out coverage guard.
- [x] **T-03b** — club-B `:167` render. Root cause was the fixture, not app code: the J-10 409-reuse
  path (`fan-out-parity-fixture.ts:585 reusedDeploymentFixture`) picked clubA/clubB in arbitrary KC order
  with no Location-ownership filter (the fresh-ingest path filtered; on the real 4-club test only 2 own
  the fanned-out Location → club-B slot could be a non-owner). Fix: extracted `partitionLocationOwners`,
  used in BOTH paths. App provisioning + tenant resolution verified symmetric. Clears
  `fan-out-migration-parity.spec.ts:167` (proof = the fanout gate). *(real-idp fan-out fixture)*
- [x] ~~**T-04** — FANOUT-SPEC-WIRING (wire an existing reporting migrated spec).~~ **Closed — invalid as
  scoped:** no reporting migrated-data spec exists (`flight-reports-parity.spec.ts` is clean-seed only;
  no `reporting-migration-parity.spec.ts`). The rider is actually *author new reporting migrated coverage*
  — new fidelity work that risks its own red, outside J-27's contract (the 3 known reds in the existing
  7-spec fanout). Re-filed to `_BOYSCOUT.md` (corrected) for a future migration journey; not dropped.
- [ ] **Gate (§4)** — fanout `Run AlpenFlight parity specs` green on the FINAL sha (ALL 7 specs),
  gap-hunter ×2-3, gallery deployed + verified, PR ready. Run 27845619061 (sha 7fdebb5c): 39 pass / 4 fail
  / 1 skip — T-01(:524)+T-03b(:167) GREEN; remaining reds → T-02b, T-05, + 2 secondary `beforeAll` timeouts
  (`planning :1040`, `reservations :813` — migrated-admin login instability; reassess after T-02b/T-05, harden
  `resolveMigratedTestClubAdmin` only if they persist standalone).

## Assumptions made

1. `depends_on: [J-10]` only — J-10 is the journey that made the fanout run end-to-end and
   exposed these reds; the entities themselves (Location/filter/flight) are already merged.
2. The 3 reds are migration-FIDELITY gaps in the producer SELECT / mapper / engine-predicate,
   not infra flakes (the boyscout + T-07 poll-to-COMPLETED already excluded read-race timing).
