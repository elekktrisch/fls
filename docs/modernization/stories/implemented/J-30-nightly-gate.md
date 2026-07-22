---
id: J-30
title: Nightly gate — green both e2e suites + loud informational surfacing
epic: E-10
status: done
journey0: false
carved: true
started_at: 2026-07-21
done_at: 2026-07-22
depends_on: [J-13, J-29]
rolls_up: []
acceptance:
  - "[happy] Audit target-entity-type filter genuinely EXCLUDES: with a Location AND an Aircraft mutation-audit row in the same tenant, filtering /system/logs by targetEntityType=Location returns the Location row and the Aircraft row is ABSENT — adversarial-seeded (the excluded row is created via a real 201 CREATE + asserted absent, not merely 'the visible rows are Location'). Backed by an AuditAdminControllerIT multi-entity-type filter case."
  - "[happy] real-idp nightly (alpenflight-e2e-real-idp.yml) is job-level GREEN read from the real-idp-merge test tally: all 4 shards 0-fail + the all-shards-ran assert passes — NOT merely 'the stack comes up'."
  - "[happy] legacy nightly (nightly.yml `e2e (Playwright)`) is job-level GREEN read from the Playwright test tally (0 hard fails; flaky-recovered stays green): mailpit is brought up + a readiness gate (seeded-data + mailpit health + a warm-the-paged-read-surface step) passes before the suite runs, so registration/email/reporting no longer race a not-ready or cold-EF6 backend."
  - "[key-error] gate-main (informational-only — operator 2026-07-21): both nightly suites emit a clear job-level PASS/FAIL + a loud run-summary tally (treats a no-run as FAIL, lists failing specs) so a red is never silently swallowed. NO merge-blocking gate; branch protection stays OFF."
  - "[edge] Residual flakes are tracked, never silently dropped: the tally EXCLUDES the 3 @quarantine-kc26 real-idp specs AND the @quarantine-legacy flights-parity-J2 spec — each an explicit, why-commented exclusion carrying a named fix-owner rider in _BOYSCOUT.md, not a bare grep-invert."
screen: /system/logs (existing — built in J-13; J-30 fixes filter correctness only) + CI/nightly infra (no screen)
headless_pulled_in: none — CI/test infra + a filter-correctness bugfix
migration: N/A — test/CI/audit-test fixes; no mapper/entity/schema touched
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts (adversarial excluded-row seed)
adr_refs: [0007, 0008, 0022]
---

## Context

J-29 declared both scheduled proof workflows "fully-green", but that was a HOLLOW done-bar: fixing the
nightly's dead-stack bring-up made the legacy suite RUN for the first time in ~6 weeks — un-hiding ~12 real
legacy reds plus J-13's vacuously-passing audit filter. Nightly reds gate no PR, so they rot silently.
Operator-flagged 2026-07-21: make BOTH nightly suites genuinely green (read the test tally, not "the stack
comes up") and surface reds loudly so none is silently dropped. Operator-flagged stabilization journey
(precedent J-27/J-29); deviates from 60/40 by operator decision — **revert to 60/40 after J-30**.

## Contract (what the pillars prove)

- **Pillar 1 — audit filter genuinely excludes** (the Playwright feature proof, on the existing /system/logs).
  The J-13 target-entity-type filter's e2e assertion was VACUOUS (seeded only Location rows). Adversarial fix:
  a real 201 Aircraft CREATE writes an Aircraft audit row; filtering by Aircraft asserts it PRESENT (non-vacuous),
  then by Location asserts it ABSENT. + an `AuditAdminControllerIT` multi-type case. Test-first found a REAL
  frontend bug (see Decisions).
- **Pillar 2 — real-idp nightly all-shards 0-fail**, read from the authoritative `real-idp-merge` tally.
- **Pillar 3 — legacy nightly green (readiness, not "stack up")**, read from the tally (0 hard fails).
- **Pillar 4 — loud informational surfacing** (RESOLVED informational-only, see Decisions): both suites emit a
  job-level PASS/FAIL + a run-summary tally (no-run→FAIL, lists failing specs). No merge-blocking gate.

## Decisions / parity exclusions

- **Gate mechanism = informational-only** (operator 2026-07-21, asked at ship time). No per-PR blocking
  `nightly-gate` job; **branch protection on `main` stays OFF** (`gh api …/branches/main/protection` → 404).
  "Never a silent drop" is met by the loud tally + fix-owner riders, not a merge gate. If stronger enforcement
  is wanted later, the recommended add is a per-PR job reading the latest scheduled nightly conclusion + enabling
  branch protection (an operator/admin action). A scheduled workflow can't report a status on a PR SHA, so a
  branch-protection required-check on the nightly itself is non-viable.
- **The audit bug was NOT test-only.** The adversarial spec surfaced a real frontend bug: `withViewTransitions()`
  stalls every zoneless SPA nav ~10s until it aborts (console TimeoutError), leaving the destination list
  unrendered — removed from both app configs (ADR 0024 restrained-motion; no design loss; the dead
  `::view-transition` CSS was also removed). The backend predicate + frontend param were confirmed correct.
- **Legacy stays nightly (reference-only)** per CLAUDE.md — NOT promoted per-push (cold-NuGet + reference-only);
  the loud surfacing is the value.
- **Quarantines (the residual-flake path — explicit, why-commented, fix-owner-ridered, never silent):**
  the 3 `@quarantine-kc26` real-idp specs (deep KC-26 OIDC drift), and `@quarantine-legacy` **flights-parity-J2**
  — the heaviest legacy spec, irreducibly flaky on the Mono/AngularJS reference stack (its list render +
  `flightDetails.StartType` bind never arrive reliably under CI load, exhausting retries:3 even after three
  rounds of step-wait hardening). All 12 GENUINE legacy reds were fixed; J2 is the residual, ridered as
  `_BOYSCOUT [LEGACY-J2-READINESS]`.
- **Legacy flakiness knob:** Playwright `retries: 3` on the reference-only pool absorbs the roaming
  Mono/AngularJS list-render readiness races; the job reds only on `pw.outcome` (hard fails), so flaky-recovered
  stays green while the flaky count is still surfaced in the tally.

## Tasks

- [x] T-01 — Audit adversarial-seed spec (real Aircraft CREATE → present-under-Aircraft, absent-under-Location) + gallery re-tag `journey:'J-30'`.
- [x] T-02 — Verify per-push gate scopes to J-30's parity_test (no-op; already correct).
- [x] T-03 — `AuditAdminControllerIT` multi-entity-type filter-exclusion IT (proves the predicate excludes).
- [x] T-04 — Legacy nightly: bring up mailpit (`--profile infra`) + a readiness global-setup (countries + mailpit health).
- [x] T-05 — Formalize the `@quarantine-kc26` real-idp exclusion (why-comment + retained fix-owner rider).
- [x] T-06 — Loud nightly PASS/FAIL `$GITHUB_STEP_SUMMARY` tallies on both suites (informational-only).
- [x] T-07 — [LOCAL-PG-GUARD] structural preflight in `PostgresTestContainerLifecycle` (blocks a dev-box local-PG spin).
- [x] T-08 — Tally honesty (gap-hunter): no-run→FAIL; real-idp `if: always()` download+merge so failing specs list.
- [x] T-09 — Fix the audit real-idp red: removed zoneless-broken `withViewTransitions()` + whitespace-exact target locator.
- [x] T-10 — Legacy flight-seed readiness probe (wrong read path — superseded by T-12).
- [x] T-11 — Real-idp join graduation reliability: token-payload poll gates nav on the access token's `clubId`, not `/me`.
- [x] T-12 — Legacy readiness: warm the paged read surface up front (cold-EF6-query-shape compilation root cause).
- [x] T-13 — Warm-up shape fix: `flightreports/page` nests its PagedList under `.Flights` (was a no-run).
- [x] T-14 — Legacy spec robustness: `expect.poll` list/aggregation assertions until populated.
- [x] T-15 — Legacy: fix 2 hard fails (J2 tow-form; flight-reports re-navigate poll) + retries 1→2.
- [x] T-16 — Legacy: J2 `:has()` CSS fix + flight-reports homebase-drift seed (resolve homebase from `/clubs/my`).
- [x] T-17 — Legacy retries 2→3 (operator: accept flaky-recovered).
- [x] T-18 — Harden J2: deterministic `flightDetails.StartType===1` scope gate + list/motor row polls.
- [x] T-19 — Quarantine `flights-parity-J2` (`@quarantine-legacy`) + `[LEGACY-J2-READINESS]` rider (irreducibly flaky).

## Outcome

Both nightly suites are job-level GREEN, read from the test tally. **real-idp** (run on sha `6a074d32`): all
4 shards 0-fail — the audit adversarial spec passes on the real chain (the vacuous J-13 filter test is closed)
and the join-request graduation flake is fixed. **legacy** (run on sha `2083754f`): 151 pass / 0 hard fail,
flaky-recovered (auth/login) green under retries:3, J2 quarantined-with-rider. The legacy suite went from ~12
genuine reds → 0 genuine bugs via a chain of fixes: mailpit bring-up, a warm-the-paged-read-surface readiness
step (cold-EF6 root cause), per-request `expect.poll` list/aggregation robustness, a homebase-drift seed fix,
and retries:3; the single irreducibly-flaky heavy spec (flights-parity-J2) is quarantined with a fix-owner
rider. Both tallies now surface reds loudly (no-run→FAIL + failing-spec listing), gap-hunter-verified. Gate
mechanism is informational-only (operator decision); branch protection stays OFF. PR #243.

## Assumptions made

1. **Gate mechanism RESOLVED — informational-only** (operator 2026-07-21): no blocking gate; branch protection OFF.
2. **Epic E-10** per the roadmap assignment; J-30 is stabilization, not new E-10 feature scope.
