---
id: J-30
title: Nightly gate — green both e2e suites + gate `main` on the stable subset
epic: E-10
status: in_progress
journey0: false
carved: true
started_at: 2026-07-21
depends_on: [J-13, J-29]
rolls_up: []
acceptance:
  - "[happy] Audit target-entity-type filter genuinely EXCLUDES: with a Location AND an Aircraft mutation-audit row present in the same tenant, filtering /system/logs by targetEntityType=Location returns the Location row and the Aircraft row is ABSENT — adversarial-seeded (the excluded row is created + asserted absent, not merely 'the visible rows are Location'). Backed by an AuditAdminControllerIT multi-entity-type filter case."
  - "[happy] real-idp nightly (alpenflight-e2e-real-idp.yml) is job-level GREEN read from the real-idp-merge test tally: all 4 shards 0-fail + the all-shards-ran assert passes — NOT merely 'the stack comes up'."
  - "[happy] legacy nightly (nightly.yml `e2e (Playwright)`) is job-level GREEN read from the Playwright test tally: registration/email/reporting specs no longer race a not-ready backend — mailpit is brought up and a readiness gate (seeded-data counts + mailpit /api/v1/info health + backend-warm probe) passes before the suite runs."
  - "[key-error] gate-main (informational-only — operator 2026-07-21): both nightly suites report a clear job-level PASS/FAIL conclusion, surfaced LOUDLY via a run summary (pass/fail tally + which specs failed) so a red is never silently swallowed; residual flakes carry fix-owner riders. NO merge-blocking gate; branch protection stays OFF (the operator chose the weakest, louder-than-today enforcement)."
  - "[edge] Residual flakes are tracked, never silently dropped: the stable subset the gate reads EXCLUDES the 3 @quarantine-kc26 real-idp specs (login ?ui_locales=fr / register verify-mail / token-lifecycle silent-refresh), each carrying a named fix-owner rider in _BOYSCOUT.md; the exclusion is explicit + documented, not a bare grep-invert."
screen: /system/logs (existing — built in J-13; J-30 fixes filter correctness only) + CI/nightly infra (no screen)
headless_pulled_in: none — CI/test infra + a filter-correctness bugfix; no new headless capability
migration: N/A — test/CI/audit-test fixes; no mapper/entity/schema touched
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts (hardened with an adversarial excluded-row seed)
adr_refs: [0007, 0008, 0022]
---

## Context

J-29 declared both scheduled proof workflows "fully-green", but that was a HOLLOW done-bar: fixing the
nightly's dead-stack bring-up (a missing docker network) made the legacy suite RUN for the first time in
~6 weeks — un-hiding ~12 real legacy reds (registration/email/reporting racing a not-ready backend) plus
J-13's vacuously-passing audit filter. Nightly reds gate no PR, so they rot silently. Operator-flagged
2026-07-21: make BOTH nightly suites genuinely green (read the test tally, not "the stack comes up") and
make the stabilized subset GATE `main` — a red nightly blocks the next merge; a residual flake gets a
named fix-owner rider, never a silent drop.

Operator-flagged stabilization journey (precedent J-27/J-29): it deviates from 60/40 by operator decision
and LEADS with a real user-facing correctness fix — the /system/logs audit filter genuinely excluding
non-matching entity types (real who-changed-what forensics correctness) — proven by one green Playwright
run. Its body is the two-suite green + gate-main infra. **Revert to 60/40 after J-30.**

## Spec must assert

**Pillar 1 — audit filter genuinely excludes (the Playwright feature proof).** The J-13 target-entity-type
filter is backed by a correct backend predicate (`JpaMutationAuditEventRepository:86-87` —
`cb.equal(root.get("targetEntityType"), targetEntityType)`) and a correct frontend param
(`audit-logs.store.ts:64`), but its e2e assertion is VACUOUS: `audit-log-two-club.spec.ts:324-333` seeds
only Location rows, so "the visible rows are all Location" passes even if the predicate were dropped. Harden
it adversarially — create an Aircraft (or FlightType) mutation-audit row in the same tenant, filter by
targetEntityType=Location, assert the Aircraft row is ABSENT — and add an `AuditAdminControllerIT`
multi-entity-type filter case. Treat as test-first: the hardened spec is *expected* to reveal the truth; if
it surfaces a real backend/frontend bug (not just a vacuous test), fix that too — do not assume test-only.

**Pillar 2 — real-idp nightly all-shards 0-fail.** `alpenflight-e2e-real-idp.yml`'s `real-idp-merge` job is
authoritative (needs all 4 shards, `if: always()`, asserts every shard ran, merge exit code = pass/fail).
Green is read from that tally over the non-quarantined set.

**Pillar 3 — legacy nightly green (readiness, not "stack up").** `nightly.yml`'s `e2e (Playwright)` job runs
the 12-category legacy suite. Root causes of the reds: (a) the job only does `docker compose up -d mssql`, so
mailpit (declared in `docker-compose.yml`, infra profile, host :1025 SMTP / :8025 HTTP) never starts → every
email spec times out on `expectEmail()`; (b) `/api/v1/countries` returns 200 as soon as the EF pool is ready,
before the seed/subsystems are hydrated → registration/reporting specs race. Fix: bring up mailpit + a
readiness gate before the suite (see seam 2). Done-bar reads the test tally, NOT bring-up
([[project_nightly_e2e_dead_stack_silent_hang]]).

**Pillar 4 — gate main on the stable subset.** Branch protection on `main` is currently OFF
(`gh api …/branches/main/protection` → 404 "not protected"), so ci.yml's `required` aggregator is advisory.
A scheduled workflow cannot report a status on a PR SHA, so the feasible + operator-faithful mechanism is a
per-PR required job that reads the latest scheduled nightly conclusions (see seam 4). A red nightly then
fails that job on every PR → the next merge is blocked until the nightly is re-greened (on-demand
`workflow_dispatch` re-runs it). The subset the gate reads EXCLUDES the @quarantine-kc26 specs (Pillar-5
residual-flake path).

## Notes

**Design reference:** none — `/system/logs` was built in J-13; J-30 changes no screen structure, only filter
correctness. No `screens-*.jsx` oracle applies.

**Gate mechanism (RESOLVED — operator 2026-07-21: informational-only, no merge-blocking gate).** The operator
chose the weakest, louder-than-today enforcement: both nightly suites emit a clear job-level PASS/FAIL and a
loud run summary (pass/fail tally + which specs failed) so a red is never silently swallowed; residual flakes
carry fix-owner riders (KC-26). NO per-PR blocking `nightly-gate` job is added, and **branch protection stays
OFF** (`gh api …/branches/main/protection` → 404). Options weighed + declined: a per-PR job reading the latest
scheduled conclusion (would block the next merge — rejected as too strong for now), and a per-push stable-subset
re-run (heavier CI). "Never a silent drop" is met by the loud surfacing + the riders, not a gate. If the
operator later wants blocking, the per-PR-reads-nightly job is the recommended add + they enable protection.

**Legacy stays nightly (reference-only).** Per CLAUDE.md "legacy is reference-only" + the cold-NuGet legacy
build ([[project_fanout_legacy_build_cold_nuget]]), the legacy suite is NOT promoted per-push — the gate reads
its latest scheduled conclusion; it never runs legacy on a PR.

**Proof-gallery:** re-tag ≥1 audit proofVideo `journey: 'J-30'` so the clean-seed gallery bookmark guard sees
a current-journey video, else that ci.yml step reds green-on-main / red-on-branch
([[project_clean_seed_proof_gallery_journey_tag]]).

**Riders touching this surface** (/do-ship sizes against the budget; land the four pillars first):
- **[KC-26 UPGRADE DRIFT]** — this IS the Pillar-5 residual-flake case: formalize as a documented exclusion +
  retained fix-owner rider. Do NOT attempt the deep KC-26 OIDC fixes (login-locale / verify-mail SMTP /
  silent-refresh) inside J-30 unless cheap; each needs live-KC iteration.
- **[LOCAL-PG-GUARD]** — structural test-preflight guard (already on this branch's `_BOYSCOUT.md`); fits an
  infra/stabilization journey's surface.
- **[CI fail-aggregate]** — surface all reds in one run; relevant to the gate surface J-30 edits.
- **[WORKFLOW-SLIM]** — composite-action extraction; J-30 edits the workflow YAML so it's a natural fold IF
  budget allows — but J-30 is already infra-heavy, likely defer.
- **[MAINTAINABILITY-TOOLING — Qodana baseline backfill]** — rides the next CI-touching journey; J-30 qualifies.

**Seam hints (non-binding, seam-granularity, for /do-ship):**
1. Audit filter — `e2e/tests/real-idp/audit-log-two-club.spec.ts` adversarial seed + `AuditAdminControllerIT`
   multi-entity-type case (verify the mock `audit-logs/audit-logs-list.spec.ts`, which already seeds 3 types,
   actually asserts exclusion; harden if not).
2. Legacy readiness — `.github/workflows/nightly.yml` (`up -d mssql mailpit` + a bounded, loud readiness step)
   + new `e2e/global-setup.ts` (poll /countries data-count + mailpit `/api/v1/info` + seeded-club count)
   wired via `e2e/playwright.config.ts` `globalSetup`.
3. Real-idp green — `.github/workflows/alpenflight-e2e-real-idp.yml` (confirm `real-idp-merge` tally 0-fail)
   + formalize the `@quarantine-kc26` exclusion (documented list + riders retained).
4. Gate-main — `.github/workflows/ci.yml` new required `nightly-gate` job (gh-api reads both nightly
   conclusions) added to the `required` aggregator `needs`.

## Tasks

- [x] T-01 — Audit adversarial-seed spec + J-30 gallery scaffold. Harden `e2e/tests/real-idp/audit-log-two-club.spec.ts`: create an Aircraft (non-Location) mutation-audit row in the same tenant, filter targetEntityType=Location, assert the Aircraft row is ABSENT (not merely "visible rows are Location"). Scaffold the J-30 proof-gallery page + re-tag ≥1 audit proofVideo `journey: 'J-30'`.
- [x] T-02 — Scope the per-push gate to J-30 (verify-only). Confirm ci.yml `alpenflight-proof` derives J-30's `parity_test` (audit-log-two-club.spec.ts) and prior journeys run mock-IdP. Standing slot. Verified no-op: the `proof_spec` derive (`ci.yml:170-249`) resolves `integration/J-30` → `e2e/tests/real-idp/audit-log-two-club.spec.ts` (journey=J-30, is_baseline=false, 5 active `test(`), and the run step (`ci.yml:854-859`) passes that single spec as the positional path filter — only J-30's spec runs per-push; prior journeys' real-idp specs run mock-IdP + nightly. Scoping already correct.
- [x] T-03 — Audit backend IT multi-entity-type filter case. `AuditAdminControllerIT`: seed ≥2 entity types, assert the `targetEntityType` filter returns only matching rows (proves the `JpaMutationAuditEventRepository:86-87` predicate genuinely excludes).
- [x] T-04 — Legacy nightly readiness gate. `nightly.yml`: `up -d mssql mailpit` + a bounded, loud readiness step; new `e2e/global-setup.ts` (poll /countries data-count + mailpit `/api/v1/info` + seeded-club count) wired via `playwright.config.ts` `globalSetup` — stops registration/email/reporting racing a not-ready backend.
- [x] T-05 — Real-idp quarantine formalization. `alpenflight-e2e-real-idp.yml`: make the `@quarantine-kc26` exclusion explicit + documented (not a bare inline grep); confirm each quarantined spec (login ?ui_locales=fr / register verify-mail / token-lifecycle silent-refresh) carries a named fix-owner rider in `_BOYSCOUT.md`.
- [x] T-06 — Gate-main (informational-only, operator 2026-07-21). NO blocking `nightly-gate` job. Surface both nightly results LOUDLY: add a `GITHUB_STEP_SUMMARY` pass/fail tally (+ failing spec names) to each nightly's authoritative step (`alpenflight-e2e-real-idp.yml` real-idp-merge; `nightly.yml` e2e) so a red is glanceable, never silently swallowed; confirm each suite's job conclusion is genuinely red on failure. Branch protection stays OFF.
- [x] T-07 — [LOCAL-PG-GUARD] structural test-preflight. Hard-fail when a local Postgres-container launch is attempted (`ALPENFLIGHT_TEST_FORCE_DOCKER=1` / any local PG spin) with `CI` unset; fail-loud message pointing at the LAN-PG rule. Seam: `PostgresTestContainerLifecycle` external-mode preflight.
- [x] T-08 — Fix loud-summary honesty (gap-hunter blockers). (a) Both nightly tally verdicts print "✅ PASS" on a ZERO-test run (globalSetup throws / no-run) — treat `total==0`/`expected==0` as NO-RUN→FAIL (`alpenflight-e2e-real-idp.yml` real-idp tally + `nightly.yml` legacy tally). (b) real-idp: the "Download shard blobs" + JSON re-merge steps default to `if: success()`, so on the ordinary test-failure path "Assert all shards ran" exit-1s first → they skip → the tally shows "crashed pre-test" + never lists failing specs → make them `if: always()`. Keep RED-on-failure intact; verify against synthetic no-run / all-pass / test-failure / missing-report reports.

- [x] T-09 — Fix the audit spec real-idp red (Pillar 1). Two independent frontend/spec causes, both empirically found (real-idp on the LAN PG); backend predicate confirmed correct (the `?targetEntityType=Aircraft` API response returned exactly the 2 Aircraft rows). (a) `withViewTransitions()` stalls every zoneless SPA nav ~10s until it aborts with a "Transition was aborted because of timeout in DOM update" console.error, blowing the per-test budget + leaving the destination list unrendered — removed from both app configs (ADR 0024 restrained-motion; no design loss). (b) The `aircraftTargetRows()` locator used an anchored `hasText: /^Aircraft$/`, which Playwright tests against the UN-normalized `audit-row-target` textContent ("\n…Aircraft\n…" from template indentation) → 0 matches despite the row being present; switched to `filter({ has: getByText('Aircraft', { exact: true }) })` (whitespace-normalized AND exact, so it still excludes "AircraftReservation"). All 5 real-idp cases green locally (1.1m); adversarial present/absent assertions intact.
- [x] T-10 — Legacy nightly flight-seed readiness (Pillar 3). Gate triage: the mailpit/readiness fix took the legacy suite 12→2 fails; the 2 remaining hard fails (`flights/flights-parity-J2.spec.ts:98` + `reporting/flight-reports.spec.ts:54`, both "Received: 0 seeded flights") are a seed-timing race — the flights ARE seeded for TODAY and the default filters match, but `e2e/global-setup.ts` waits only for `/countries` + mailpit, not flights. Add a `waitForSeededFlights()` probe (poll `/api/v1/flights?from=TODAY&to=TODAY` until ≥1) to the readiness gate. Re-dispatch the legacy nightly; if the 7 warm-up flakes (auth/login/J-5, recovered on retry) persist, file a fix-owner rider (don't deep-de-flake in J-30). RESULT: mailpit/probe took the suite to 153 pass / 3 fail / 1 flaky (flaky 7→1), but the probe gated the wrong flight read path (`[HttpGet] gliderflights/today`) while the UI list renders via `[HttpPost] gliderflights/page` — see T-12.
- [x] T-11 — Real-idp graduation reliability (operator 2026-07-21: FIX, don't quarantine — J-30's charter is a RELIABLY-green nightly). `join-request.spec.ts:360` (409 already-member) flaked under shard-load: after approve, the JIT `t_user` isn't visible in time (`currentClubId` stays null → the SSE `filter(clubId!==null)` never emits → `navigateByUrl('/start')` never fires → `waitForURL` 30s timeout). Green on main + zero `features/join/` diff on J-30 = pre-existing, scoped into J-30. FIX (`join-pending.page.ts`): graduation was single-point-brittle on TWO seams — the one SSE event AND a single `/me` read. Replaced with a bounded `forceRefreshSession`+token-payload poll (`timer(0,1.5s)`, ≤40 attempts, `takeUntil(denied)`) that gates navigation on the ACCESS TOKEN carrying `clubId` (the interceptor's source of truth) — not `/me`'s DB-sourced `currentClubId`, which can resolve before the refreshed token propagates and then 403s `/start`'s dashboard reads. Also fires on-load as a background net so a missed SSE frame still graduates. Verified locally against the real stack: 7/7 join-request real-idp green, previously-flaky 409 green ×4 consecutively (403 race eliminated). Backend `JoinRequestDecisionsService.approve` already commits the t_user + KC clubId attribute before returning — no backend change needed. NOTE: local LAN-PG seed drift (seed club join_code was `L8PDJDXF`, not `SEEDCLUB`) blocked the default code path; verified via a throwaway env override, reverted. The nightly re-dispatch under real shard-load is the final proof.
- [x] T-12 — Legacy readiness: proper test concept (operator 2026-07-21: 0-fail via a proper concept, not per-path probes). The 3 fails are warm-up races on DISTINCT read paths (paged flights / reservations / report aggregation) — the today-GET probe warms the wrong path. Analyze WHY a read is empty right after seed when a sibling endpoint has the data, then apply ONE systemic concept (warm the actual read surface the suite uses up front, or a shared populated-list retry) — not a probe per path. ROOT CAUSE: cold EF6 query-shape compilation — each paged read is a distinct LINQ tree compiled lazily on first execution. IMPLEMENTED: `warmPagedReadSurface()` fires one `POST …/page/0/100` per read path (glider/motor flights, reservations, flight-reports, planning-days) to warm each shape up front.
- [x] T-13 — Warm-up shape fix (T-12 follow). The first re-dispatch NO-RAN: `warmPagedReadSurface` assumed every path returns a top-level `PagedList{Items}`, but `flightreports/page` returns `FlightReportResult` nesting its PagedList under `.Flights` → `body.Items` undefined → 180s poll → globalSetup threw → 0 tests. Made the accessor per-path shape-aware (`nestedUnder: "Flights"` for flight-reports). The loud-fail + NO-RUN→FAIL surfacing (T-08) correctly flagged it.
- [x] T-14 — Legacy spec robustness (close the residual reds). With warm-up in place the suite ran 152 pass / 2 fail / 3 flaky (`reservations-parity-J5` now green). The empty-list races are per-request `ng-table getData` timing, not a persistent compiled plan — so apply the robust concept at the assertion layer: `expect.poll` list/aggregation assertions until populated. Fix (a) `flights-parity-J2:130` empty-list race (poll rows) + `:240` tow-form `scrollIntoViewIfNeeded` UI-timing; (b) `flight-reports:120` `totalFlightsSum>=1`=0 — determine timing (poll the aggregation) vs data (self-seeded flight outside the this-year window → seed/window fix); (c) opportunistically harden the 3 flaky (`locking-workflow:75` API timeout, `reservations-parity-J5:153` list race, `rules-engine-per-type:243` data timing) with the same poll pattern for a stable green.

- [x] T-15 — Legacy: 2 genuine hard fails + systemic flake knob (final legacy push). After T-14: 153 pass / 2 fail / 2 flaky. (a) `flights-parity-J2:253` — the row-poll now passes but a tow-form assertion (`fls-flight-edit-tow-form div[ng-if="needsTowplane(...)"]`) is not visible in 15s: investigate timing (ng-if evaluates after data → robust wait) vs parity (the seeded flight doesn't need a towplane → fix expectation/seed) and fix the right one (don't weaken). (b) `flight-reports:54/:144` — the `expect.poll` exhausts at 10s with 0 flights: the self-seeded today/glider/LSZK flight should be in the this-year+homebase window (T-14 verified), so determine slow-aggregation (bump the poll budget + re-navigate/re-query, not just re-read stale DOM) vs a genuine count miss, and fix it. (c) The 2 flaky are a MOVING set (planning-J6, aircrafts-J1 — different from the prior flaky, all now green) = systemic Mono/Angular-under-load list-render flakiness → bump the legacy Playwright `retries` (1→2) to absorb it; confirm `nightly.yml` "Mark job as failed" reds only on hard fails (`pw.outcome`), so flaky-recovered stays green.

- [x] T-16 — Legacy: 2 concrete bugs (NOT a cap, NOT flakiness — triage: 154 pass / 2 fail / 1 flaky, 8.0m). (a) `flights-parity-J2.spec.ts:166` — T-15's explicit-row selector had invalid CSS (`:has(... >> text=...)` — the `>>` chained-selector engine syntax isn't legal inside `:has()`, "Unexpected token"): replaced with `has:`/`hasText:` locator options, still targets the HB-3407 aerotow row. (b) `flight-reports.spec.ts:162` — REAL data gap, EMPIRICALLY traced via the deployed nightly Playwright report's page snapshot: the `location-flights-this-year` filter counts only flights whose Start/LdgLocationId == the club's HomebaseId, and the snapshot showed the running club's homebase was `JQXF (J0C-QXFMY7)`, NOT LSZK (accumulating-state drift); `ensureGliderFlight` hardcoded LSZK → excluded → Total 0. Fix (category: SEED): `ensureGliderFlight` now resolves the club's actual homebase from `GET /api/v1/clubs/my` and anchors start/ldg there. Right-sized: test timeout 240s→90s, poll 90s→20s. No assertion weakened.

## Assumptions made

1. **Gate mechanism RESOLVED — informational-only** (operator 2026-07-21, asked at ship time): no blocking
   gate, branch protection stays OFF; reds are surfaced loudly + tracked by fix-owner riders. See Notes.
2. **The audit bug is most likely test-only** (backend + frontend verified correct); the fix leads with the
   adversarial spec + IT, but J-30 budgets for a real backend/frontend fix if the hardened spec surfaces one.
3. **The 3 @quarantine-kc26 specs stay quarantined-with-rider** — J-30 formalizes the exclusion (Pillar 5)
   rather than fixing the deep KC-26 OIDC behavior, which needs live-KC iteration beyond one journey.
4. **Branch protection stays OFF** — per the informational-only decision J-30 wires no blocking gate; enabling
   protection (+ a per-PR nightly-reader) remains a future operator/admin action if stronger enforcement is wanted.
5. **Epic E-10** per the roadmap assignment (scheduled-jobs + CI area); J-30 is stabilization, not new E-10
   feature scope.
