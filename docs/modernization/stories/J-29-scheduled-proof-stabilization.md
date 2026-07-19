---
id: J-29
title: Scheduled-proof stabilization — flights date-range widen refetch (fidelity fix)
epic: E-02
status: in_progress
started_at: 2026-07-19
journey0: false
carved: true
depends_on: []
rolls_up: []
acceptance:
  - "[happy] The proof-fanout `Run AlpenFlight parity specs` step runs `flight-migration-parity.spec.ts` GREEN — the migrated glider flight (today-5) renders in the owning club's /flights list after the date-range widen (was a 20s `page.waitForResponse` timeout in `widenFlightListRangeToRecent`, red 6+ days)."
  - "[key] Widening the /flights date range fires exactly one `GET /api/v1/flights?from=…&to=…` (from≠to, 200) whose window covers the migrated flight's date; the row is identified by immatriculation and carries a `flights-row-<uuid>` testid."
  - "[edge] The `alpenflight e2e real-idp` cross-journey shard no longer reds on `flight-migration-parity`; the change is test-side only — app refetch is unchanged (proven by the mock `flights-list.spec.ts` round-trip + `flight.store.spec.ts` unit)."
screen: none — the fix targets the existing /flights list; no new screen/route.
headless_pulled_in: none
migration: N/A — test-helper fix; no mapper/entity/schema touched.
parity_test: alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts (widenFlightListRangeToRecent, ~line 506)
adr_refs: []
---

## Context

Main's two **scheduled** proof workflows — `alpenflight proof fan-out` and `alpenflight e2e real-idp` —
have been red 6+ days running (2026-07-14 → 07-19) on a single **untracked, deterministic** failure:
`flight-migration-parity.spec.ts`'s `widenFlightListRangeToRecent` helper times out after 20s waiting for the
`/flights` date-range refetch, so the migrated glider flight never renders and the spec fails. The **required
`ci` gate is green throughout** (12/12) — this is scheduled-only and non-gating, but it makes main's daily
proofs read as perpetually broken. Operator chose a **fix-only branch** (this deviates from the 60/40 journey
rule — it delivers 0% new feature — done at explicit operator request because the reds are untracked and
misleading; precedent: J-27 "drive the fanout fully green", a fidelity sprint with `migration: N/A` and
`screen: none`).

## Tasks

- [x] T-01 — Fix `widenFlightListRangeToRecent` (`flight-migration-parity.spec.ts:506-536`): replace the
  `.ant-picker-header-super-prev-btn` year jump (`:527`, + now-unused `leftPanel`) with a single
  `.ant-picker-header-prev-btn` month page-back, then pick first + last in-view non-disabled cell so the
  committed `from≠to` window spans last-month→this-month (covers today-5 **and** today-10 across month
  boundaries). Keep the existing cell locator + `from≠to` `waitForResponse` predicate. Assert the committed
  range spans the seeded flight's date before awaiting the refetch. Test-side only (`flights-row-<id>` exists).

- [x] T-02 — Attribute the flight-migration-parity proof video to J-29 for the gallery. ci.yml's `alpenflight
  proof (real-idp, clean-seed)` derives this journey's `parity_test` and its step-28 deployed-bookmark guard
  requires the current journey's gallery page to carry ≥1 proof video; but every video annotation in
  `flight-migration-parity.spec.ts` is tagged `journey: 'J-2'`, so `generate-gallery.mjs` attributes 0 to J-29 →
  thin page → guard reds. Re-tag the primary proof video (migrated glider renders in /flights after the widen)
  to `journey: 'J-29'` per the generator's annotation contract (`generate-gallery.mjs:149-151`) + refresh its
  caption to the J-29 proof. The video is real — only its journey attribution changes; no assertion touched.

§4 gate (driven by `e2e-driver`): the box cannot host the real-idp stack (OOM), so CI is the proof. The
`alpenflight e2e real-idp` shard confirmed `flight-migration-parity.spec.ts` GREEN on-branch (migrated context);
the PR's `alpenflight proof (real-idp, clean-seed)` job re-runs the same spec + gallery guard on the final sha.
An on-branch fanout is a known cold-NuGet no-op (migration: N/A ⇒ not a merge blocker); [happy] fanout-green
lands post-merge on the scheduled warm-cache run.

## Spec must assert

The fixed helper must commit a `from ≠ to` range that **covers the migrated flight's date** and fires the
`GET /api/v1/flights?from=…&to=…` (200) the assertion waits on, so the row renders and the tow-link/crew
sub-assertions (already correct) run against a populated list.

Ground the fix in the **proven** round-trip, not a re-derivation:
- `alpenflight/web/e2e/tests/flights/flights-list.spec.ts:350-395` — the mock spec commits a range via
  `.ant-picker-cell-in-view:not(.ant-picker-cell-disabled)` cell-clicks (**no `super-prev-btn`**) and asserts
  `from`/`to` round-trip to the server. Green in required `ci`.
- `alpenflight/web/src/app/features/flights/flight.store.spec.ts:277,288` — `setDateRange` forwards
  `from`/`to` to the endpoint. Unit-proven.

The bug is the helper's page-back: `flight-migration-parity.spec.ts:527` clicks `.ant-picker-header-super-prev-btn`
(a full **year** back) before selecting cells. The seeded migrated flight is dated **today-5 / today-10**
(`FlightParityBundleSeeder`) — i.e. the **current month** (today 2026-07-19 → July 14 / July 9) — so paging back
a year is both unnecessary and commits a range that never fires / never matches the `from≠to` predicate → the
20s timeout.

## Notes

- **Seam (single):** `widenFlightListRangeToRecent` at `flight-migration-parity.spec.ts:506-536` — a **test
  helper**. The app is untouched (the date-range refetch is proven working). Fix = align the helper with the
  `flights-list.spec.ts` proven cell-click pattern: drop the `super-prev-btn` year jump (today-5/today-10 sit in
  the default current-month panel), and if the seeder is ever changed to date rows older than the visible panel,
  page back with `prev-btn` (month) the exact number of months needed — never a blind year jump. Assert the
  committed range actually spans the flight's date before awaiting the refetch.
- **NOT a flake — do not "re-run to clear."** 100% deterministic, identical failure 6+ consecutive days. Treat
  any green-on-retry as suspicious and confirm the helper actually fires the `from≠to` GET.
- **Root-cause evidence** (so `/do-ship` doesn't re-litigate app vs test): required `ci` green (mock
  `flights-list` round-trip + `flight.store` unit both pass) ⇒ app refetch works; only this helper pages back a
  year ⇒ test-side bug.
- **Ride-along candidates (OPTIONAL — not required by this carve's contract).** Fold in only if the operator/
  `/do-ship` wants the scheduled proofs *fully* green rather than just clearing the dominant red:
  - **[TOKEN-LIFECYCLE-87-RESIDUAL]** (`_BOYSCOUT.md`) — `token-lifecycle.spec.ts:87` (disabled-user redirect)
    still flakes the non-gating real-idp shard after J-10b T-10. Root cause is the realm-mutating race (the
    disable hasn't propagated when the assertion runs), not the console error. Real fix = a per-run throwaway
    realm user + poll-until-disabled, not a wider console allow-list. Seam: `token-lifecycle.spec.ts:87` + real-idp
    user provisioning.
  - **`nightly` 30-min cap** — the legacy `e2e (Playwright)` job exceeds its 30-minute max-execution time and is
    cancelled (a duration budget, **not a red test**). Shard the legacy Playwright run or raise the cap. Separate
    stack from AlpenFlight; lowest priority.
- **J-10b (merged 2026-07-19) already cleared** the `hardening-J26` (409) and `fan-out-migration` reds — verified
  empirically on a fresh post-merge dispatch. J-29 owns only what J-10b did **not** touch.
- **Deviation acknowledged:** pure test-fix, no new vertical AlpenFlight scope. Standalone-journey status granted
  by operator because the failure is untracked and reds two main workflows daily. After ship, revert to 60/40.
