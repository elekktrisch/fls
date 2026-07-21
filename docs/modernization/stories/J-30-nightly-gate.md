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
  - "[key-error] gate-main: while either nightly's authoritative job was RED on its latest scheduled run, a new required ci.yml job (`nightly-gate`) FAILS on every PR → the next merge is blocked; when both are green it passes. Demonstrated red→green: a deliberately-red nightly conclusion blocks, green unblocks."
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

**Gate mechanism (load-bearing — recommended; /do-ship + operator finalize at ship time).** Add a per-PR
required ci.yml job `nightly-gate` that queries the latest SCHEDULED run conclusion of both nightly workflows
(`real-idp-merge` and legacy `e2e`) via `gh api` and FAILS if either was red; add it to the `required`
aggregator's `needs`. This makes "a red nightly blocks the next merge" literal AND required-able (Option C —
adding a scheduled check to branch protection — is non-viable: the scheduled check never exists on a PR SHA).
Rejected as primary: a fixed stable-subset real-idp job re-run per-push (Option A) — heavier CI, re-runs
specs, and the operator's model is "a red NIGHTLY blocks the next merge", not "a per-push subset". **Enabling
branch protection requiring `required` (+ `nightly-gate`) is an operator/admin action** (outward repo-config,
needs admin) — J-30 wires the job + recommends; it does not flip protection itself.

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
- [ ] T-04 — Legacy nightly readiness gate. `nightly.yml`: `up -d mssql mailpit` + a bounded, loud readiness step; new `e2e/global-setup.ts` (poll /countries data-count + mailpit `/api/v1/info` + seeded-club count) wired via `playwright.config.ts` `globalSetup` — stops registration/email/reporting racing a not-ready backend.
- [ ] T-05 — Real-idp quarantine formalization. `alpenflight-e2e-real-idp.yml`: make the `@quarantine-kc26` exclusion explicit + documented (not a bare inline grep); confirm each quarantined spec (login ?ui_locales=fr / register verify-mail / token-lifecycle silent-refresh) carries a named fix-owner rider in `_BOYSCOUT.md`.
- [ ] T-06 — Gate-main `nightly-gate` required job. New `ci.yml` job querying the latest SCHEDULED conclusion of both nightly workflows (`real-idp-merge` / legacy `e2e`) via gh api, FAIL if either red; add to the `required` aggregator `needs`. Demonstrate red→green.
- [ ] T-07 — [LOCAL-PG-GUARD] structural test-preflight. Hard-fail when a local Postgres-container launch is attempted (`ALPENFLIGHT_TEST_FORCE_DOCKER=1` / any local PG spin) with `CI` unset; fail-loud message pointing at the LAN-PG rule. Seam: `PostgresTestContainerLifecycle` external-mode preflight.

## Assumptions made

1. **Gate mechanism = per-PR `nightly-gate` job reading the latest scheduled conclusion** (recommended above),
   not a per-push stable-subset re-run. Recorded, not asked — /do-ship confirms with the operator at ship
   time (do-ship has its own operator checkpoint).
2. **The audit bug is most likely test-only** (backend + frontend verified correct); the fix leads with the
   adversarial spec + IT, but J-30 budgets for a real backend/frontend fix if the hardened spec surfaces one.
3. **The 3 @quarantine-kc26 specs stay quarantined-with-rider** — J-30 formalizes the exclusion (Pillar 5)
   rather than fixing the deep KC-26 OIDC behavior, which needs live-KC iteration beyond one journey.
4. **Branch protection stays an operator action** — J-30 wires `nightly-gate` into `required`; the operator
   enables protection (admin) to make `required` binding. Until then the gate is advisory-but-present.
5. **Epic E-10** per the roadmap assignment (scheduled-jobs + CI area); J-30 is stabilization, not new E-10
   feature scope.
