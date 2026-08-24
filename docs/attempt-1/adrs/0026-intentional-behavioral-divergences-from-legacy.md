# 0026 — Intentional behavioral divergences from legacy

- **Status:** Proposed (drafted by /do-retro, J-2 window — operator approves before Accepted)
- **Date:** 2026-06-04
- **Scope:** Behavioral rules where AlpenFlight **deliberately** does something
  different from `flsserver`/`flsweb`, by an operator decision — *not* bugs, *not*
  yet-unported behavior. The durable registry a parity reviewer consults to tell
  "intended divergence" apart from "parity miss."

## Context

The rewrite is parity-driven: the `legacy-oracle` extracts exact legacy behavior, the
`parity-reviewer` checks the new stack matches it, and the `flsweb` video + paired
screenshots in the proof gallery let the operator eyeball the match. The default is
**match legacy**.

Occasionally the operator deliberately chooses to diverge — legacy has a quirk, a bug,
or a modelling choice the rewrite should *not* carry forward. J-2 produced the first
such call (the flight time-gate, below). These decisions were recorded only in the
per-journey story file — but journey bodies get pruned at finalize ([do-ship](../../../.claude/skills/do-ship/SKILL.md)
§5) and are per-journey, so a parity reviewer touching the same surface in a **later**
journey has no durable, greppable place to learn "this difference is intended." Without
that, an intentional divergence reads as a regression and gets "fixed" back toward
legacy.

[ADR 0022](0022-modernization-primary-directives.md) Directive 1 prefers working
software over documentation — so this registry stays terse (one entry per divergence,
citing the legacy file:line it departs from and the new-stack code that owns it), not a
narrative. A standalone ADR keeps it greppable as one file.

## Decision

A deliberate behavioral divergence from legacy is recorded as an **entry in this ADR**
at the time it's decided (the operator's call; `/do-retro` drafts the entry from the
journey's parity-decisions section). Each entry states: the legacy behavior (with
`file:line`), the AlpenFlight behavior (with the owning aggregate/policy), and the
rationale. The `parity-reviewer` and `legacy-oracle` treat a documented entry here as an
**expected** difference, not a finding.

### Divergence registry

**D-1 — Flight lock/bill time-gate keys on flying date, not record-entry date.** (J-2, S-061)
- **Legacy:** both gates key on `CreatedOn` (record-entry timestamp, `TruncateTime`'d,
  `DateTime.Today` local). Lock allowed when `CreatedOn ≤ Today-2d` gated on
  `ProcessStateId == VALID(30)` (`flsserver/.../FlightService.cs:1157,1164`); bill
  (delivery) allowed when `CreatedOn ≤ Today-3d` gated on `LOCKED(40)`
  (`flsserver/.../DeliveryService.cs:65,97`). Legacy has **no `locked_at` column**.
- **AlpenFlight:** lock allowed when `flight.flight_date ≤ today-2d`; bill allowed when
  `flight.locked_at ≤ today-3d`. Owned by `FlightGatePolicy` (+ injected `java.time.Clock`)
  on the Flight aggregate; `locked_at` is a net-new column set on the Valid→Locked
  transition (`V27__flight_locked_at.sql`). Migrated flights at `ProcessStateId ≥ LOCKED(40)`
  backfill `locked_at` from `ModifiedOn`, else null.
- **Why:** S-061's wording was chosen over legacy's. The flying date (`flight_date`) and
  the lock instant (`locked_at`) are the operationally meaningful clocks for "may this
  flight still be edited / is it billable yet" — the record-entry date (`CreatedOn`)
  conflates them and is an accident of when a row happened to be typed in. The operator
  accepted that this shifts *when* flights become lockable/billable vs legacy.

**D-2 — Flight-report location decoration is tenant-scoped; the legacy cross-club
ride-through is dropped.** (J-7 read-model conversion, ADR 0027 / PR #217)
- **Legacy:** report decoration joins carry no tenant predicate — a flight referencing
  another club's Location still shows that location's name
  (`FlightReportService.cs` join shape; carried into the retired
  `flight-report-read-model` native-SQL register entry as a documented ride-through).
- **AlpenFlight:** the `FlightReportProjector` decorates under `@TenantId` — a
  cross-club location would project `start/ldg_location_name = null` (id retained on
  `t_flight_report_row`). Owned by `flights.application.FlightReportProjector` +
  `JpaFlightReportDecorations`.
- **Why:** the case is structurally unreachable in AlpenFlight, so the ride-through
  buys nothing: (1) migration FANS OUT each legacy shared location into one replica
  per referencing club ("union of club homebases + flights' start/landing locations",
  `MapperLegacyBindings` LOCATION binding) and flight FKs remap to the own-club
  replica — migrated flights always decorate; (2) tenant-filtered pickers prevent
  creating a cross-club reference in the app. Keeping a tenant-bypassing decoration
  seam (a register entry) for an unreachable case would contradict ADR 0027's
  shrinking-register direction. If a reachable case ever appears, the row still
  carries the location id — a register-listed projection-time lookup (the
  `persons-cross-tenant-membership-check` shape) is the prepared remedy.

**D-3 — FlightTime credit over-credit on non-zero-`min` tiers is clamped to the billed
slice.** (J-9b)
- **Legacy:** the over-credit split compares the `PersonFlightTimeCredit` balance against
  the FULL active flight-time (before the tier `min` subtraction), `lineFlightTimeInSec`
  (`flsserver/.../AircraftFlightTimeRule.cs:36,145`), but bills `active − min`
  (`:45`) and sets the credited line to the full balance (`:148,158`). On a non-zero-`min`
  tier (e.g. the 600s-`min` "Schulung ab 11.min" filters, `AccountingRuleFilterFactory.cs:796,866`),
  when the balance falls between `(active − min)` and `active` the rule credits MORE than
  the billed slice and emits a NEGATIVE remainder line. The combination is reachable —
  credits match by immatriculation + `PersonId` only, tier-agnostic (`DeliveryService.cs:307-312`).
- **AlpenFlight:** the split decision AND the credited quantity use the billed slice
  `lineSeconds` (= `active − min`): the split fires only when `lineSeconds > balance`;
  `creditedSeconds = min(balance, lineSeconds)`; `remainder = lineSeconds − creditedSeconds`
  (≥ 0). Owned by `accounting.domain.FlightTimeStage`. `min = 0` tiers are unaffected
  (`lineSeconds == active`), so the clean-seed + migrated proof corpus is behaviour-identical.
- **Why:** over-crediting a member and emitting a negative invoice line is a money defect,
  not a modelling choice — the billed slice is the only quantity that can legitimately be
  credited. Dry-run only: the legacy balance decrement (by full active) is not modeled in
  J-9b, so no decrement-side divergence exists yet.

## Consequences

- **Positive:** a parity reviewer / `legacy-oracle` has one greppable place to confirm a
  difference is intended; intentional divergences stop reading as regressions. Survives
  journey-body pruning.
- **Negative:** a second place (besides code + the journey file) that must be kept
  honest — an entry that drifts from the code is worse than none. Mitigate by citing the
  owning class so the entry is checkable; remove an entry if the divergence is ever
  reverted to match legacy.
- **Follow-ups:** none for D-1 (the time-gate shipped in J-2: `FlightGatePolicy`,
  `V27__flight_locked_at.sql`, `FlightStateTransitionService` wiring, boundary ITs).
  Future divergences append a new `D-n` entry here as they're decided.
