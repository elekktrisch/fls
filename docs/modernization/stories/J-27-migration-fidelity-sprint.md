---
id: J-27
title: Migration-fidelity sprint — drive the fanout fully green
epic: E-02
status: done
started_at: 2026-06-19
done_at: 2026-06-20
journey0: false
carved: true
depends_on: [J-10]
rolls_up: []
acceptance:
  - "[happy] The proof-fanout `Run AlpenFlight parity specs` step runs ALL 7 migrated-data specs GREEN over the single real exported bundle (was 3 red: J-0c/J-8/J-9)."
  - "[migration/parity] club-B admin sees its OWN fanned-out copy of the migrated Location (fan-out-migration-parity.spec.ts:167)."
  - "[migration/parity] the migrated `FlightTime: Glider per minute` AccountingRuleFilter renders with predicate intact: articleTarget='5001', deliveryLineText='Glider flight minutes', derived target '5001 (Glider flight minutes)' (accounting-rules-parity.spec.ts:524)."
  - "[migration/parity] the engine over a migrated glider flight emits the genuine migrated delivery — 5001 'HB-3256 Glider flight minutes' qty 22 Minuten + 6001 'Landegebuehr LSZK' qty 2 Landung (delivery-creation-test-parity.spec.ts:577, redesigned to genuine seed per operator 2026-06-19)."
  - "[edge] the per-club fanned-out copies stay edit-isolated; the gallery renders the current journey live."
screen: none — migrated data renders on existing /locations, /accountingrules, /deliverycreationtests screens.
headless_pulled_in: none
migration: N/A — repairs migrated parity of already-built mappers (Location, AccountingRuleFilter, FlightTime engine path); no new mapper.
parity_test: alpenflight/web/e2e/tests/real-idp/{fan-out-migration-parity,accounting-rules-parity,delivery-creation-test-parity}.spec.ts, gated by the proof-fanout `Run AlpenFlight parity specs` step.
adr_refs: [0008, 0003, 0022]
---

## Context

J-10's gate ran the real-bundle fanout end-to-end for the first time, revealing the merged
migration journeys' migrated done-bars were hollow: parity specs red over real migrated data.
This journey drives the fanout fully green so the migration promise — *a migrated club sees its
own real legacy data, correctly* — holds and is glanceable per-club in the gallery. Operator-
sanctioned pure-debt journey (J-10 retro). [[feedback_verify_infra_is_run_not_just_authored]]
[[project_synth_bundle_doesnt_validate_producer_select]]

## Contract

The fanout `Run AlpenFlight parity specs` step (`alpenflight-proof-fanout.yml`, `J0C_BUNDLE_SOURCE=real`)
green over the SINGLE real export — all 7 migrated-data specs, on the FINAL sha. Done-bar is that
step green + a live current-journey gallery row (the fanout parity step is non-required, so this
is its own gate, not a required-check flip). [[project_false_green_derive_fallback]]

**Operator principle (2026-06-19):** a migration test relies only on legacy seed data and asserts
what it GENUINELY produces — never re-point/gerrymander the seed to force a pass.

## Tasks

- [x] **T-01** — AccountingRuleFilter producer SELECT: bare `JSON_VALUE` aborted the whole export cursor on
  58 dirty `N''`/non-JSON target values → `CASE WHEN LEFT(LTRIM(col),1) IN ('{','[')` guard. Real-producer IT. Clears `:524`.
- [x] **T-02 / T-02b** — engine matcher needs no fix (regression IT `AccountingDeliveryEngineIT`); fixed a latent
  bigint→`Integer` read of the legacy time columns (read as `Long` + clamp, pgjdbc-fatal on >Int.MAX).
- [x] **T-03 / T-03b** — Location fan-out producer SELECT proven clean (`LocationFanOutProducerSelectIT`); the `:167`
  red was a fixture bug — the J-10 409-reuse path picked clubA/clubB by arbitrary KC order. Fix: `partitionLocationOwners` in both paths.
- [x] **T-05** — J-6 planning email: a Friday-dated bare weekend dev-seed day landed on notification-day+1 → stray
  CANCEL mail. V45 pins it ≥2d out; `PlanningDevSeedIT` extended.
- [x] **T-07** — engine 500 on account-recipients: `RuleFilterLoader.resolveRecipient` threw when no Person matched;
  legacy builds a self-contained recipient from the blob. Fixed to mirror legacy. Regression IT.
- [x] **T-08** — flaky 45s `beforeAll`: `resolveMigratedTestClubAdmin` re-enumerated ~6 clubs' SPA logins per spec →
  worker-scoped memo (keyed by ownership remark, JWT-`exp` refresh).
- [x] **T-09** — `:577` redesign (operator decision): removed the malformed §4 recipient fixture `F1500004` (legacy-oracle:
  `RecipientType=FlightCrew` has no legacy basis; legacy routes pilot-pays via `FlightCostBalanceType` fallback). With it
  gone the engine produces real deliveries AND the §4 5001 filter was never shadowed (the 500 had suppressed all items;
  the earlier "shadowing" theory was a red herring). `:577` now asserts the GENUINE static-seed glider delivery (HB-3256:
  5001 qty 22 Minuten + 6001 qty 2 Landung), identified by `itemText`. `:524` keeps the 5001 filter as a round-trip subject.
- [x] **T-10** — gallery row: tag the migrated-fidelity capture `journey:'J-27'` so the current-journey page renders live.
- [x] **T-11** — previews-index scanner probed `proof-preview/<branch>/<jid>/` (subPath null) but the fanout deploys under
  `legacy-parity/` → every unmerged journey rendered PENDING. Added `subPath:'legacy-parity'` + a unit spec for the branch-only case.
- [x] ~~**T-04**~~ closed — no reporting migrated spec exists to wire; re-filed to `_BOYSCOUT.md` (author new coverage, not a one-liner).
- [x] **Gate (fanout)** — fanout `Run AlpenFlight parity specs` GREEN on code sha `1ab394b8` (42 pass / 1 skip — the operator-disabled
  rename test; run 27864311564). gap-hunter ×3: code honest (2 real:true incl. 1 high; the dissent was the pre-existing mock-suite timeout, not J-27).
- [x] **T-12** *(operator decision 2026-06-20 — pull WORKFLOW-SLIM sharding into J-27)* — the required `mock-auth e2e`
  flaky-timed-out (164 tests / 4 cores > 5-min cap). SHARDED `alpenflight-mock-e2e` into a `--shard=i/4` matrix
  (`reporter: blob`, `--workers=4`) + a new authoritative `alpenflight-mock-e2e-merge` job that downloads the shard
  blobs, `merge-reports`-stitches them, guards "all shards ran" (so a crashed shard can't false-green), and is what
  `required` now `needs`. `workers` was ALREADY top-level (J-9 stopgap #222); confirmed real-idp invocations all pass
  `--workers=1`. n=4 chosen (~41 tests/shard, locally ~2 min on 2 workers → comfortable <5 min headroom on a 4-vCPU
  4-worker runner). Verified locally: shard 3/4 + 4/4 green (39+41), blobs merge to one report exit-0. BRANCH-PROTECTION
  ADMIN ACTION REQUIRED (operator): the required check name changed `alpenflight mock-auth e2e (Run Playwright)` →
  `alpenflight mock-auth e2e (merge shards)`. Scope = sharding ONLY; rest of WORKFLOW-SLIM stays for J-11.
  *(ci.yml mock-auth e2e + playwright.config.ts workers)*
- [ ] **T-13** *(operator-flagged — hollow proof video)* — the J-27 gallery video filmed the EMPTY `/deliverycreationtests`
  stored-runs list: the migrated block proved the delivery via the API then navigated to a screen the migrated club has no
  rows on. The demonstrable proof (the migration promise made true) was never rendered. Fix: drive the DCT dry-run UI for
  the migrated HB-3256 flight (`dct-new-button` → `dct-flight-picker` → `dct-create-test-delivery`) so the 5001 + 6001 lines
  RENDER on screen, and record the video there (mirrors the clean-seed block); `dct-expected-item-*` asserted visible so the
  video can't be hollow. API assertions kept. *(delivery-creation-test-parity.spec.ts migrated block)*
  - **DONE (9549eff7):** fanout GREEN on `6775b253` re-capturing the video; the J-27 gallery video now films the
    rendered dry-run delivery (5001 + 6001 lines), gated by `dct-expected-item-*` visible asserts. Verified structurally
    (asserts gate the capture, gate green) + the deployed video is real content; pixel-frame extraction not possible on
    this box (no ffmpeg) — glanceable in the gallery.

## Outcome

The fanout went red→green by fixing **7 genuine bugs** that were hollow done-bars across merged journeys (dirty-JSON
cursor abort, bigint column read, fan-out fixture owner-pick, Friday dev-seed, account-recipient 500, flaky admin-login,
previews-index scanner) — plus the operator-directed `:577` redesign onto genuine seed. No mocked seams. The migration-
fidelity gate is now reliable for every future migration journey (J-11/J-10b/J-1/J-21).

**Open / filed (non-blocking):**
- **Flight-migration fidelity (→ J-2 / retro):** the hand-crafted §5 glider (HB-3407/47min) is ABSENT from the migrated
  TestClub glider flights (static-seed HB-3256/22 + HB-3407/212 present; an unexplained HB-3407/30 appears). `:577` was
  redesigned off §5, so non-blocking — but gap-hunter flagged that `:577`'s green assumes the base-seed `HB-3256 Schulung`
  filter (1059, min=0, lower sort) does NOT shadow the 5001 line; that's only true if the migrated HB-3256 flight's
  crew/flight-type did NOT round-trip. Verify the flight's crew + flight-type migration to confirm `:577` is green for the
  right reason (not a flight-fidelity miss baked in).
- **Required `ci` mock-auth e2e:** FIXED in T-12 — sharded 4× + a merge-reports gate (the WORKFLOW-SLIM sharding,
  pulled into J-27 by operator decision 2026-06-20). Needs a one-time branch-protection required-check rename (see T-12).
- **[FANOUT-SPEC-WIRING] / [GALLERY-SIMPLIFY] / rest of [WORKFLOW-SLIM]** (composite-action extraction, YAML cut,
  real-idp shard, KC-26 quarantine) ride J-11 (a feature host); J-27 was the pure-debt exception.

## Assumptions made

1. `depends_on: [J-10]` only — J-10 made the fanout run; the entities are already merged.
2. Fanout green on the final CODE sha `1ab394b8`; the trailing journey-doc/status commit is inert (no behavior change).
