---
id: J-9b
title: Flight-time-credit / discount sub-engine
epic: E-09
status: done
started_at: 2026-06-21
done_at: 2026-06-22
journey0: false
carved: true
depends_on: [J-9, J-4]
rolls_up: []   # the credit split of S-074 (rolled_up_into J-9); operator-deferred from J-9 (2026-06-14)
acceptance:
  - "[happy] A pilot with a PersonFlightTimeCredit balance whose aircraft immatriculation MATCHES the credit → dry-running a flight FULLY covered by the balance emits ONE FlightTime DeliveryItem carrying the credit's DiscountInPercent."
  - "[happy] Over-credit 2-line split: when the balance covers only PART of the billable flight-time → TWO DeliveryItems — a credited line (qty = credited seconds, DiscountInPercent = the credit's discount) + a billed-remainder line (qty = over-credit seconds, DiscountInPercent = 0), same article/itemText."
  - "[edge] Zero-balance + NoFlightTimeLimit=false → credit branch SKIPPED (pure decrement, no discount); NoFlightTimeLimit=true → unlimited credit (whole line credited)."
  - "[happy] Dry-run does NOT mutate balances — running 'Create test delivery' twice yields identical output and persists NO PersonFlightTimeCreditTransaction (AsNoTracking parity)."
  - "[migration/parity] migrated PersonFlightTimeCredit balances (current balance from the IsCurrent transaction) round-trip so the engine applies REAL migrated credits over migrated flights — green fan-out parity."
screen: /deliverycreationtests (reuses J-9's dry-run + diff harness; adds credit-path assertions) — no new screen
headless_pulled_in: the rules-engine credit branch (PersonFlightTimeCredit application + DiscountInPercent + over-credit 2-line split) — homed by J-9's deliveryCreationTests harness
migration: PersonFlightTimeCredit + the IsCurrent PersonFlightTimeCreditTransaction (current balance) + PERSON_CLUB (the indirect-tenancy pivot, a J-4-gap dependency) — new mappers; FK PersonId→Person (J-4), nullable BalancedDeliveryId→Delivery (null when unmigrated)
parity_test: alpenflight/web/e2e/tests/real-idp/delivery-creation-test-parity.spec.ts (credit cases + migrated block)
adr_refs: [0005, 0008, 0022, 0027]
---

## Context

J-9 ported the rules engine's FlightTime decrement loop as the pure decrement path; the operator deferred the
credit sub-engine (2026-06-14). A pilot can hold a pre-paid `PersonFlightTimeCredit` balance + a `DiscountInPercent`;
the engine applies that balance to the flight-time line — splitting into a credited line + a billed remainder when
the balance only partly covers the flight. This is real money (a mis-migrated/mis-applied credit double-charges a
member), so J-9b is the credit branch of the engine + the `PersonFlightTimeCredit` migration, proven through J-9's
`/deliverycreationtests` dry-run harness (no new screen).

## Spec must assert

Grounded in `AircraftFlightTimeRule.cs:49-214` (credit branch) + `DeliveryService.cs` persist vs
`CreateDeliveryDetailsForTest` dry-run.

1. **Activation = aircraft-immatriculation match** — the credit applies when the flight immat matches
   `MatchedAircraftImmatriculations` under `UseRuleForAllAircraftsExceptListed`. Credit = the `IsCurrent`
   transaction's balance; `NoFlightTimeLimit=true` ⇒ unlimited; `=false && balance=0` ⇒ skip.
2. **Over-credit 2-line split** — `L > C` → credited line (qty=C secs, discount) + remainder line (qty=L−C,
   discount 0), same article/itemText. Fully-covered → single discounted line.
3. **DiscountInPercent** — passthrough int stamped on the credited line; NOT applied to qty/unit-price here.
4. **Dry-run must NOT mutate** — loads credits read-only, writes no transaction; the re-run is idempotent.
5. **Migration fidelity** — migrated credit + its IsCurrent balance carry over so the engine credits real
   migrated members; green fan-out parity (migration done-bar).

## Decisions (ship-time)

- **Migrate (Option B, operator 2026-06-21).** Reversed the `UnmappedTables` registration — a prepaid credit's
  grant/discount/immat-list aren't derivable from flight history, so the "recompute" rationale didn't hold.
  Authored the entity + V3 schema + mapper + the hard fanout fidelity gate.
- **Current balance = the single `IsCurrent` transaction** (not latest-by-date). The mapper guarantees
  exactly-one `IsCurrent` per credit (keep-first dedupe by `BalanceDateTime`); V3 carries an identity-bearing
  partial UNIQUE `(credit_id) WHERE is_current`, proven by a real-producer collision IT that reds in `check`.
- **Tenancy is INDIRECT** — no club_id column; the read pivots `FROM PersonClub` so Hibernate's `@TenantId` on
  `PersonClub.clubId` scopes it (a credit surfaces only when its owner is a member of the caller's tenant). This
  made J-9b the FIRST entity to require **PERSON_CLUB migration** (a J-4 gap) — bound here (id-map-exempt leaf
  junction; member_state orphan-nulled).
- **No cross-line balance carryover** (legacy trace) — the `IsCurrent` balance is read fresh per delivery, never
  mutated during the line loop; the over-credit split is provoked within a single flight-time line (`L > C`).
- **Dry-run mutates NOTHING** — out of J-9b scope is the real-run persist (transaction insert + `IsCurrent` flip).

## Parity exclusions

- Immat activation reproduces legacy substring `.Contains` (parity, not corrected to exact-element); the legacy
  null-list NPE is null-guarded (skip), not reproduced.
- `DiscountInPercent` is a passthrough int (price/rounding downstream).
- Clean-seed credits are seeded via a `@Profile(dev/test) @Hidden` CLUB_ADMIN-gated internal affordance (no
  production credit-CRUD exists); the migrated block relies ONLY on the genuine legacy export (data isolation).

## Tasks

- [x] T-01 — Credit-case spec stub + J-9b gallery scaffold (red-first).
- [x] T-02 — Per-push heavy lane → J-9b's real-idp spec.
- [x] T-03 — `PersonFlightTimeCredit`(+current-balance) aggregate + V3 schema (partial UNIQUE) + tenant-scoped repo.
- [x] T-04 — Credit branch in `FlightTimeStage` (activation/substring-match/over-credit split/discount); domain tests.
- [x] T-05 — Wire the read-only credit load into the dry-run path (no mutation, idempotent).
- [x] T-06 — `PersonFlightTimeCredit`(+IsCurrent) migration mapper + remove from `UnmappedTables` + collision/orphan IT.
- [x] T-07 — Full credit-proof corpus + the test-only seed affordance + the migrated-block structure.
- [x] T-08 — Gate fixes: arch-guard allow-list + seed DTO `PersonId` typing + warm-nav bearer capture.
- [x] T-09 — Credit-uncredited fix: set `PILOT_PAYS_ALL` so the recipient resolves; pin the recipient→credit linkage IT.
- [x] T-10 — Seed a representative credit in the genuine legacy fixture so the migrated-fidelity AC is non-vacuous.
- [x] T-11 — Fix the `fcb-` prefix on the cost-balance literal + audit all typed-IDs.
- [x] T-12 — Per-case FlightTime-filter isolation by exact immat (engine split was already correct).
- [x] T-13 — Widen the migrated-admin resolver hook budget to clear the 45s ingest-409 cascade (harness-only).
- [x] T-14 — Bind `PERSON_CLUB` migration so the credit's indirect-tenancy pivot resolves on migrated data.
- [x] T-15 — `@Transactional(readOnly)` on the credit read so the idempotent re-read doesn't lazy-init 500.
- [x] T-16 — Clear the PERSON_CLUB bind's two defects: arch-guard allow-list + id-map carve-out (composite PK, no guid).
- [x] T-17 — Inherited-console-guard hygiene: 412 opt-out + deterministic AEROTOW person-picker search terms.

## Outcome

Shipped green on `b3f00ac6`: the per-push `ci` clean-seed credit corpus (full-cover, over-credit 2-line split,
zero-balance skip, unlimited, dry-run idempotent/no-mutation — all asserting the real discount/quantities) AND the
`fan-out parity` migration done-bar (the migrated HB-3256 credit round-trips legacy seed → producer → mapper →
migrated PG → engine, applying the 10min@25% + 12min@0% split on the migrated flight, rendered in the gallery
video). The engine is correct Java domain logic (ADR 0022 §2); V3 schema is structural-only. The dominant
gate-revealed work was the migration FK-closure: the credit's indirect tenancy surfaced that **PERSON_CLUB was
never migrated** (a J-4 gap) — bound here. Two `gap-hunter`s independently confirmed the green is genuinely
vertical (real round-trip, no tenancy leak, test-only seed affordance, narrow opt-outs).

## Assumptions made

1. **Migrate (Option B)** — operator-confirmed 2026-06-21, reversing the `UnmappedTables` "recompute" decision.
2. One screen — reuses J-9's `/deliverycreationtests` harness; no new route.
3. Person migrated (J-4); the historical transaction→Delivery linkage isn't needed (only the current balance), so
   `BalancedDeliveryId` nulls when unmigrated rather than forcing a J-10b dependency.
