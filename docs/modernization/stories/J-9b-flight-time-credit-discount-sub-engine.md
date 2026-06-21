---
id: J-9b
title: Flight-time-credit / discount sub-engine
epic: E-09
status: in_progress
started_at: 2026-06-21
journey0: false
carved: true
depends_on: [J-9, J-4]
rolls_up: []   # the credit split of S-074 (already rolled_up_into J-9); operator-deferred from J-9 (2026-06-14), no new horizontal story
acceptance:
  - "[happy] A pilot with a PersonFlightTimeCredit balance whose aircraft immatriculation MATCHES the credit (UseRuleForAllAircraftsExceptListed × MatchedAircraftImmatriculations CSV) → dry-running a flight FULLY covered by the balance emits ONE FlightTime DeliveryItem carrying the credit's DiscountInPercent; the engine output reflects the decremented balance."
  - "[happy] Over-credit 2-line split: when the balance covers only PART of the billable flight-time → TWO DeliveryItems — a credited line (qty = credited seconds, DiscountInPercent = the credit's discount) + a billed-remainder line (qty = over-credit seconds, DiscountInPercent = 0), same article/itemText."
  - "[edge] Zero-balance + NoFlightTimeLimit=false → credit branch SKIPPED (pure decrement, no discount); NoFlightTimeLimit=true → unlimited credit (whole line credited)."
  - "[happy] Dry-run does NOT mutate balances — running 'Create test delivery' twice yields identical output and persists NO PersonFlightTimeCreditTransaction (AsNoTracking parity); only a real/persisted run would write the transaction + flip IsCurrent."
  - "[migration/parity] migrated PersonFlightTimeCredit balances (current balance from the IsCurrent transaction) round-trip so the engine applies REAL migrated credits over migrated flights — green fan-out parity."
screen: /deliverycreationtests (reuses J-9's dry-run + diff harness; adds credit-path assertions) — no new screen
headless_pulled_in: the rules-engine credit branch (PersonFlightTimeCredit application + DiscountInPercent + over-credit 2-line split + transaction side-effects) — homed by J-9's deliveryCreationTests harness
migration: PersonFlightTimeCredit + the IsCurrent PersonFlightTimeCreditTransaction (current balance) — new mapper; FK PersonId→Person (J-4), nullable BalancedDeliveryId→Delivery (null when the delivery isn't migrated)
parity_test: alpenflight/web/e2e/tests/real-idp/delivery-creation-test-parity.spec.ts (extend with credit cases) + alpenflight/web/e2e/tests/accounting/ (mock inner-loop)
adr_refs: [0005, 0008, 0022, 0027]
---

## Context

J-9 ported the rules engine's FlightTime decrement loop as the **pure decrement path** and the operator
deliberately **deferred the credit sub-engine** (2026-06-14): a pilot can hold a pre-paid
`PersonFlightTimeCredit` balance + a `DiscountInPercent`, and the engine applies that balance to the
flight-time line — splitting into a credited line + a billed remainder when the balance only partly covers
the flight. This is real money (a mis-migrated or mis-applied credit double-charges a member), so it gets its
own journey: the credit branch of the engine + the `PersonFlightTimeCredit` migration, proven through J-9's
existing `/deliverycreationtests` dry-run + diff harness (no new screen).

## Spec must assert

Grounded in `flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/AircraftFlightTimeRule.cs:49-214`
(the credit branch) + `DeliveryService.cs:201-218` (real persist) vs `:373-408` (`CreateDeliveryDetailsForTest`
dry-run, AsNoTracking). Exact line-by-line stays for the ship-time `legacy-oracle`; the contract:

1. **Activation = aircraft-immatriculation match, not a flag** (`AircraftFlightTimeRule.cs:191-214`): the credit
   applies when the flight's immat matches `MatchedAircraftImmatriculations` (CSV) under
   `UseRuleForAllAircraftsExceptListed`. Credit = `CurrentFlightTimeBalanceInSeconds` from the `IsCurrent`
   transaction; `NoFlightTimeLimit=true` ⇒ unlimited; `NoFlightTimeLimit=false && balance=0` ⇒ skip.
2. **Over-credit 2-line split** (`:145-171`): `lineFlightTimeInSec > credit` → line A (credited, qty=credit secs,
   discount=DiscountInPercent) + line B (remainder, qty=line−credit, discount=0). Fully-covered (`:172-183`) →
   single line with the discount; balance decremented by the line.
3. **DiscountInPercent** is a passthrough int stamped on the credited line's `DeliveryItemDetails`
   (`PersonFlightTimeCredit.DiscountInPercent`, `:46`); NOT applied to quantity/unit-price here (price/rounding
   downstream). Remainder line gets 0.
4. **Dry-run must NOT mutate** — `CreateDeliveryDetailsForTest` loads credits `AsNoTracking` (`:406-408`) and
   never `SaveChanges`; the harness re-run is idempotent and writes no transaction. Persist-on-real-run only.
5. **Migration fidelity** — migrated PersonFlightTimeCredit + its current (IsCurrent) transaction balance carry
   over so the engine credits real migrated members; green fan-out parity (migration journey done-bar).

## Notes

- **Migration shape** (oracle): two tables, tenant-scoping INDIRECT via `Person`→`PersonClubs.ClubId` (no
  ClubId column). `PersonFlightTimeCredit`: PK, FK `PersonId`→Person, `NoFlightTimeLimit`, `ValidUntil`,
  `UseRuleForAllAircraftsExceptListed`, `MatchedAircraftImmatriculations` (denormalized CSV — no FK rewrite),
  `DiscountInPercent`, audit/soft-delete. `PersonFlightTimeCreditTransaction`: PK, FK `PersonFlightTimeCreditId`,
  nullable FK `BalancedDeliveryId`→Delivery, the balance longs, `IsCurrent`. The engine only needs the
  **current** balance, so migrate the `IsCurrent` transaction; null `BalancedDeliveryId` when its Delivery isn't
  migrated (keeps J-10b off the hard-dependency path — nullable FK, so not a depends_on per the FK rule).
- **depends_on:** J-9 (the engine + the `/deliverycreationtests` harness this extends) + J-4 (migrates Person,
  the NOT-NULL `PersonId` FK target). Both done. Person is already in the migrated set (J-2 FlightCrew needed it).
- **Ship-time flag:** confirm the next schema does NOT add a UNIQUE on `(PersonId, immat)` the legacy lacks —
  legacy permits multiple overlapping credits and `break`s on the first immat match (`:92`). If next adds such a
  UNIQUE, ship a real-producer collision IT (so it reds in `check`, not the ~20-min fanout).
- **Seam hints (non-binding, one seam each):** the credit branch on the AlpenFlight FlightTime rule (J-9's
  `AircraftFlightTimeRule`-equiv — the credit application + 2-line split + discount stamping); the
  `PersonFlightTimeCredit`(+Transaction) aggregate + repo (current-balance read; `@TenantId` via Person, JPA-first
  per ADR 0027); the `PersonFlightTimeCredit`(+IsCurrent-transaction) migration mapper + the collision/orphan IT +
  fanout assertion; the harness spec extension (credit cases through the dry-run + diff); the credit-case corpus
  (fully-covered, over-credit split, discount, zero-balance skip, unlimited).
- **Parity exclusions to carry:** the dry-run AsNoTracking no-mutation contract; DiscountInPercent as a
  passthrough int; legacy's multiple-overlapping-credits-first-match-wins behavior (reproduce, don't "fix").
- **Burndown riders:** J-9b LEADS with the high-risk credit engine (feature-heavy by nature, like J-9). `/do-ship`
  folds only the light per-touch riders that touch its surface (COMMENT-STRIP, HISTORY→GIT) + its gallery slice;
  GALLERY-SIMPLIFY / WORKFLOW-SLIM heavy infra stays a lighter journey's load unless the gate has room.

## Assumptions made

1. **Migration ≠ N/A** (the 2026-05-31 roadmap marked J-9b "N/A (engine)"): the credit branch only fires on a
   PersonFlightTimeCredit balance, and a pilot's pre-paid balance is real money — so J-9b migrates the credit
   tables + proves fidelity over migrated data (fanout done-bar), not clean-seed only.
   **RESOLVED (ship-time, operator 2026-06-21 — Option B):** migrate. The codebase had registered both tables
   `UnmappedTables` ("recomputed from t_flight + t_flight_crew per ADR 0022 directive 2") — but a balance-bearing
   prepaid credit's grant / discount / immat-list / valid-until are NOT derivable from flight history, so that
   rationale doesn't hold. J-9b **reverses** the `UnmappedTables` entry and authors the entity + schema + mapper
   + the hard fanout fidelity gate.
2. One screen — reuses J-9's `/deliverycreationtests` dry-run + diff harness; the credit path is proven by new
   assertions on the existing screen, no new route.
3. Person is migrated (J-4 + already required by J-2 FlightCrew), so the `PersonId` FK resolves; the historical
   transaction→Delivery linkage is not needed for the engine (only the current balance), so `BalancedDeliveryId`
   nulls out when unmigrated rather than forcing a J-10b dependency.

## Decisions (ship-time, 2026-06-21)

- **Migrate (Option B).** Reverse the `UnmappedTables` registration for `PersonFlightTimeCredits` +
  `PersonFlightTimeCreditTransactions`; author the destination entity + schema + mapper + fanout fidelity gate.
- **Current balance = the single `IsCurrent==true` transaction** (`AircraftFlightTimeRule.cs:61`), NOT
  latest-by-date. Migrate only that transaction's balance. The mapper guarantees **exactly-one `IsCurrent`** per
  credit (keep-first dedupe by `BalanceDateTime`); the V3 table carries an identity-bearing **partial UNIQUE**
  `(credit_id) WHERE is_current` (structural invariant), and a **real-producer collision IT** seeds a
  multi-`IsCurrent` row to prove the dedupe reds in `check`, not the ~20-min fanout.
- **Immat activation = reproduce legacy substring `.Contains`** (`AircraftFlightTimeRule.cs:197/206`) — a flight
  immat substring-matches the stored CSV string; **parity exclusion** ("reproduce, don't fix"), not silently
  corrected to exact-element. `UseRuleForAllAircraftsExceptListed` is the inversion flag (true ⇒ applies when the
  immat is NOT in the list; false ⇒ applies when it IS).
- **Null-list under the non-inversion branch ⇒ null-guard → skip the credit** (legacy NPEs at `:206`; the NPE is
  not reproduced).
- **Tenancy is INDIRECT** — no ClubId column on either table; `@TenantId` derives from the owning
  `Person → PersonClubs.club_id` (ADR 0027 JPA-first), like the Person aggregate's `PersonClub` child.
- **Dry-run mutates NOTHING** — the `/deliverycreationtests` path loads credits read-only and writes no
  transaction; only a real persisted run inserts the transaction + flips `IsCurrent` (out of J-9b scope — J-9b
  proves the dry-run engine output + the no-mutation invariant).

## Tasks

- [x] **T-01** — Spec stub: extend `delivery-creation-test-parity.spec.ts` with the credit-case structure +
  selectors (thin asserts) + scaffold the J-9b per-journey proof-gallery page; red-first repro of credit application.
- [x] **T-02** — Scope the per-push heavy lane to J-9b's spec only (prior journeys → mock-IdP; full real-idp
  regression → nightly + the §4 gate).
- [x] **T-03** — `PersonFlightTimeCredit`(+current-balance) aggregate: new JPA entity + V3 schema (partial UNIQUE
  `(credit_id) WHERE is_current`) + repo (current-balance `IsCurrent` read; tenancy indirect via Person→PersonClubs).
- [x] **T-04** — Credit branch in the engine (`FlightTimeStage`): activation (substring match, reproduce +
  null-guard), balance source, over-credit 2-line split, `DiscountInPercent` passthrough, fully-covered single
  line, in-memory decrement; domain unit tests.
- [ ] **T-05** — Wire the credit read into the dry-run path (`DeliveryCreationTestsService`/`exampleDelivery`):
  read-only load, NO transaction write, idempotent (re-run yields identical output + no mutation).
- [ ] **T-06** — Migration mapper (`PersonFlightTimeCreditMapper`: producer SELECT + V3 insert) + remove both
  tables from `UnmappedTables` + the real-producer collision/orphan round-trip IT (exactly-one-`IsCurrent` dedupe;
  `PersonId` resolve; nullable `BalancedDeliveryId`).
- [ ] **T-07** — Thicken the spec to full real assertions (clean-seed corpus: full-cover, over-credit split,
  discount, zero-balance skip, unlimited, dry-run idempotent/no-mutation + migrated credit round-trip — mine the
  ACTUAL migrated values); drive real-idp green locally; populate the gallery.

**Riders watched at the gate (fold only if still red on the FINAL-sha fanout):** the J-9-filed **article-5001**
migrated-FlightTime gap lives in THIS spec's migrated block — keep it green as the credit cases extend it; J-27
("drive the fanout fully green") + the green J-2b fanout (2026-06-20) likely closed it + the J-8 AccountingRuleFilter
/ J-0c Location riders, but the §4 job-level verify confirms on the to-merge sha.
