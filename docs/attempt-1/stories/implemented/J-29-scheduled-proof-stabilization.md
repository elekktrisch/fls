---
id: J-29
title: Scheduled-proof stabilization — flights widen refetch + real-idp/nightly fully-green
epic: E-02
status: done
started_at: 2026-07-19
done_at: 2026-07-19
journey0: false
carved: true
depends_on: []
rolls_up: []
acceptance:
  - "[happy] The proof-fanout `Run AlpenFlight parity specs` step runs `flight-migration-parity.spec.ts` GREEN — the migrated glider flight (today-5) renders in the owning club's /flights list after the date-range widen (was a 20s `page.waitForResponse` timeout in `widenFlightListRangeToRecent`, red 6+ days)."
  - "[key] Widening the /flights date range fires exactly one `GET /api/v1/flights?from=…&to=…` (from≠to, 200) whose window covers the migrated flight's date; the row is identified by immatriculation and carries a `flights-row-<uuid>` testid."
  - "[edge] The `alpenflight e2e real-idp` cross-journey shard no longer reds on `flight-migration-parity`; the change is test-side only — app refetch is unchanged (proven by the mock `flights-list.spec.ts` round-trip + `flight.store.spec.ts` unit)."
  - "[fully-green-1] `token-lifecycle.spec.ts` (disabled-user redirect) passes deterministically in the `alpenflight e2e real-idp` run — the Keycloak disable is polled-until-propagated, the redirect driven via warm nav (not a fixed-wallclock refresh-grant race), and the expected OIDC token-rejection console log (`token(s) validation failed, resetting`) allowlisted per-test (folded in on operator ask 2026-07-19 to make the scheduled proofs FULLY green)."
  - "[fully-green-2] The `nightly` legacy `e2e (Playwright)` job's docker-stack bring-up is fixed so it runs instead of hanging: the missing `external` network `alpenflight_shared` is created + `up -d mssql` + the healthy threshold corrected to `>=1` + bring-up failures surfaced loudly. Empirical root cause (NOT the rider's 'duration budget'): the job was silently dead ~6 weeks (0/40 runs since 2026-05-28), hanging the full 30 min on a failed `docker compose up` (network never created), never running a test. Validated post-merge on the scheduled warm-cache nightly (on-branch dispatch is blocked by the cold-NuGet legacy build the e2e job `needs`)."
screen: none — the fix targets the existing /flights list; no new screen/route.
headless_pulled_in: none
migration: N/A — test/CI fixes; no mapper/entity/schema touched.
parity_test: alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts (widenFlightListRangeToRecent)
adr_refs: []
---

## Context

Main's two scheduled proof workflows (`alpenflight proof fan-out`, `alpenflight e2e real-idp`) read as
perpetually broken. Operator granted a fix-only branch (0% new feature, deviates from 60/40; precedent J-27),
then expanded scope (2026-07-19) to drive the scheduled proofs FULLY green in one PR — not just the dominant
`flight-migration-parity` red. Revert to 60/40 after.

## Tasks

- [x] T-01 — Widen helper `widenFlightListRangeToRecent`: `super-prev-btn` year jump → single `prev-btn` month
  page-back + a guard that the committed refetch window spans the oldest seeded date. Test-side only.
- [x] T-02 — Re-tag the primary flight-migration-parity proof video `journey: 'J-2'` → `'J-29'` (the generator's
  `proof-journey` annotation) so the clean-seed proof's deployed-bookmark gallery guard sees ≥1 J-29 video.
- [x] T-03 — token-lifecycle disabled-user redirect: `pollUserDisabled` (poll KC until `enabled===false`) +
  deterministic warm-nav drive replacing the fixed 40s wait + allowlist the expected OIDC token-rejection log.
- [x] T-04 — Nightly e2e dead-stack bring-up (dead ~6 weeks): create the `external` network `alpenflight_shared`
  + `up -d mssql` + healthy threshold `>=1` + loud fail on timeout. Ship + validate post-merge.

## Outcome

**T-01 (widen)** — root cause was a test-helper page-back: it paged a full YEAR back before picking cells,
committing a prior-year range that never covered the current-month migrated flight (today-5/today-10) → 20s
timeout. Fixed to a one-month page-back matching the proven `flights-list.spec.ts` pattern.

**Fully-green expansion** — T-03 fixed `token-lifecycle:87`: the disable is now polled-until-propagated and the
redirect driven deterministically via warm nav (was a fixed 40s refresh-grant race); the expected OIDC
`token(s) validation failed, resetting` log is allowlisted per-test. T-04 fixed the nightly `e2e (Playwright)`
job that had been silently dead ~6 weeks — hanging the full 30 min on a failed `docker compose up` (the
`external` network was never created), never running a test — by creating the network + `up -d mssql` +
healthy `>=1` + a bounded, loud-failing wait.

**Proof (final code sha `97139337`):** `alpenflight e2e real-idp` GREEN at run level — token-lifecycle
deterministic (first-attempt) + flight-migration-parity green, 0 reds across 4 shards + merge. ci.yml heavy
lane GREEN job-level — `alpenflight proof (real-idp, clean-seed)` (flight-migration-parity + the deployed J-29
gallery bookmark, page + `.webm` 200) + mock-e2e (4 shards + merge) + dashboard/profile proofs + builds. T-04
(`nightly.yml`, inert to the heavy lane) is validated post-merge on the scheduled warm-cache nightly; on-branch
verify is blocked by the cold-NuGet legacy build the e2e job `needs`. On-branch fanout is a known cold-NuGet
no-op (migration: N/A ⇒ not a merge blocker); [happy] fanout-green lands post-merge on the warm-cache run.

Both folded `_BOYSCOUT.md` riders are retired ([TOKEN-LIFECYCLE-87-RESIDUAL] shipped — its "realm-mutating
race" theory was refuted; the real cause was a fixed-wait + per-test console-guard gap). The 3
`@quarantine-kc26` nightly reds remain a separate, unfolded `_BOYSCOUT.md` item.
