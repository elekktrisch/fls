---
id: J-33
title: Audit attribution and the migrate dead-end — drain every S1 and S2 rider (hardening)
epic: E-13
status: in_progress
started_at: 2026-08-23
journey0: false
hardening: true
carved: true
depends_on: []
rolls_up: []
acceptance:
  - "[happy] A system administrator opens /system/logs and reads a user name in every actor cell, not a raw Keycloak subject."
  - "[key-error] The abuse guard rejects an anonymous public write. The row shows ANONYMOUS_PUBLIC and the client IP, not SYSTEM."
  - "[happy] A migrated audit row shows a resolved AlpenFlight user, not a raw legacy identifier."
  - "[edge] An audit row for an Article, a PlanningDay or an EmailTemplate shows its decided snapshot fields, not an empty cell."
  - "[happy] A new registrant selects the migrate call-to-action, signs up, and reaches /migrate/start with a live handshake."
  - "[key-error] The ingest rejects a cross-tenant bundle with a 4xx and a named reason, not a 500."
  - "[happy] A registrant who signs up through bare /signup lands on /join."
  - "[happy] The nightly legacy suite reads 0 failed on main."
  - "[edge] Each guard this journey wires or widens scores a planted violation for every input class it claims to cover."
  - "[happy] The push to integration/J-33 arms the fan-out. The gate waits for that run and reads its verdict."
screen: /system/logs and /migrate/start — two built screens, reused for the proof
headless_pulled_in: audit actor attribution + audit snapshot fields + the pre-tenant user lookup → /system/logs and /migrate/start
migration: mapper-touching — AuditLogMapper gains foreignKeyColumns(), so `fan-out parity` is a hard merge gate. Not N/A.
parity_test: alpenflight/web/e2e/tests/real-idp/audit-log-two-club.spec.ts + alpenflight/web/e2e/tests/real-idp/register.spec.ts
adr_refs: [0008, 0022, 0026, 0027, 0030]
---

## Context

J-32 shipped its S1 work and re-filed the S2 tail. The operator decided on 2026-08-22 that J-33
drains the remainder: 3 S1 and 30 S2 riders. The riders are not a flat list. Five of them land on one
screen, `/system/logs`, and three land on the migrate funnel. Both screens are built, so this journey
reuses them for its proof, as a hardening journey may.

The value is honest attribution. Today an administrator opens the audit trail and cannot tell a
rejected anonymous write from a cron row, cannot read a user name when a system administrator is
signed in, and reads raw legacy identifiers on every migrated row. In parallel, the migrate
call-to-action that J-16 shipped ends in an error state, so the funnel the landing page advertises
does not complete.

## Spec must assert

### `/system/logs` — every row is attributable and legible

1. A system administrator signs in and opens `/system/logs`. Each actor cell renders a user name.
   Today `audit-logs.store.ts:93` calls `listUsers`, which admits `CLUB_ADMINISTRATOR` only, while
   `AuditAdminController.java:28` admits `SYSTEM_ADMINISTRATOR` too. The store swallows the 403 and
   falls back to the raw subject.
2. The abuse guard rejects an anonymous public write. The resulting row carries `ANONYMOUS_PUBLIC`
   and the client IP. Today `AuditTrailService.attributionOfTheCurrentPrincipal()` answers
   `SYSTEM`, `systemActor=true`, `clientIp=null` for that case.
3. A migrated audit row renders a resolved AlpenFlight user. `AuditLogMapper` declares no
   `foreignKeyColumns()`, so `ForeignKeyResolver` seeks `user_id` while the wire field is
   `actor_user_id`. Assert this over the real bundle, not a synthetic one.
4. An audit row whose snapshot class the recorded `entityType` does not describe renders its decided
   fields. The fifteen sites are pinned in
   `alpenflight/server/config/audit/undecided-audit-snapshot-fields.txt`.

### `/migrate/start` — the funnel completes

5. A registrant opens the landing page, selects the migrate call-to-action, completes signup, and
   reaches `/migrate/start` with a live handshake. Today the chain reaches
   `MigrationHandshakeService.issue` → `PreTenantUserLookup.resolveUserId` → no `t_user` row →
   `UnknownPrincipalException` → 403. `JitUserMaterializerImpl.materialize` returns empty when the
   JWT carries no `clubId` claim, and a new registrant has no club.
   `register.spec.ts` declares that 403 as a known defect today. Delete the declaration when the
   assertion turns green.
6. The ingest rejects a cross-tenant bundle with a 4xx and a named reason.
7. A registrant who signs up through bare `/signup` lands on `/join`. `resolveSignupIntent(null)`
   makes `join` the default, and no spec drives it.

### The nightly is green on main

8. The nightly legacy suite reads 0 failed. See §"Main-branch red" below.

## Main-branch red — fold in, do not re-carve

The `nightly` workflow is red on `main` on 2026-08-20, 2026-08-21 and 2026-08-22. It is one
deterministic failure, not a flake: 1 failed, 1 flaky, 155 passed, and the failure exhausted all four
attempts.

- **Symptom (evidence).** `e2e/tests/auth/lostpassword-parity-J19.spec.ts:42` fails with
  `strict mode violation: locator('#username') resolved to 2 elements`.
- **Cause (measured, not inferred).** `lostpassword.html:20` carries `id="username"` inside the
  `lostpassword-form`. `login-form-directive.html:11` carries a second `id="username"`, and
  `navigation-bar-directive.html:171` renders that directive on every page. Line 40 uses a
  page-global locator while line 38 already holds the scoped form. Line 80 of the same spec already
  scopes its button to its form, so the fix follows the spec's own pattern.
- **Why no gate caught it.** `nightly.yml` triggers on `schedule` and `workflow_dispatch` only. A
  spec added under `e2e/` never runs before merge. J-19 authored this spec and its first real run was
  the 2026-08-20 nightly. This is the same class as the riders below
  ([[feedback_verify_infra_is_run_not_just_authored]]).
- **Blast radius (checked).** `#newPassword` and `#newPasswordConfirm` exist only in
  `confirm-email.html`. Every other page-global id locator in `e2e/tests/` sits on an authenticated
  screen, where the nav login form is absent. Only `#username` collides.

The one-line fix ships with the carve. The gate hole it exposes is a new rider,
`[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]`, recorded in `_BOYSCOUT.md`.

**Proven, not claimed.** The carve dispatched the nightly on `integration/J-33`
([run 32554637416](https://github.com/elekktrisch/fls/actions/runs/32554637416)). Read at step level:
the spec **ran and passed** — `✓ 17 [auth] › lostpassword-parity-J19.spec.ts:21:5` in 12.5s — and the
tally reads 155 passed, **0 failed**, 2 flaky. The prior main run read 155 passed, 1 failed, 1 flaky.
So AC-8 is already met on this branch; `main` un-reds when J-33 merges.

Two DIFFERENT specs flaked on this run — `locking-workflow.spec.ts:43` and
`reservations-parity-J5.spec.ts:55` — where the prior run flaked `planning-parity-J6`. Both recovered
on retry. The flaky set moves run to run, which is the legacy Mono and AngularJS stack under CI load
that `[LEGACY-J2-READINESS]` already names. Do not read a moving flaky set as a new defect.

## The rider inventory — 3 S1, 30 S2

Full text and seams stay in `_BOYSCOUT.md`. `/do-ship` deletes each bullet as it ships. Per
[[feedback_rider_symptom_is_evidence_cause_is_a_guess]], each task opens by confirming or refuting
the stated cause: J-32 burned about sixteen causes and eight were wrong.

**Cluster A — audit attribution and legibility (proves on `/system/logs`)**
`[ANON-FAILED-WRITE-READS-AS-SYSTEM]` S1 · `[AUDITLOGMAPPER-DECLARES-NO-FOREIGN-KEY-COLUMNS]` S1 ·
`[AUDIT-LOGS-STORE-403-FALLS-BACK-SILENTLY]` S2 · `[UNDECIDED-AUDIT-SNAPSHOT-FIELDS]` S2 (15 sites) ·
`[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]` S2 (needs the operator — ADR 0008 §Amendment S-159 names
`RequestAuditFilter` as a `runAs` seam). The S3 rider `[AUDIT-ACTOR-KIND]` decides with this cluster.

**Cluster B — the migrate and signup funnel (proves on `/migrate/start`)**
`[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` S1 · `[INGEST-CROSS-TENANT-REJECTION-READS-AS-500]`
S2 · `[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]` S2.

**Cluster C — gates that never run, or do not read their own inputs**
`[FANOUT-PUSH-ARM-IS-AUTHORED-BUT-NEVER-FIRED]` S2 · `[ARCHUNIT-AND-NULLAWAY-DEMO-GATES-NEVER-RUN]`
S2 · `[CHECK-THEME-LOAD-IS-ROTTEN-AND-UNWIRED]` S2 · `[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]` S2 ·
`[NG-LINT-COVERS-TWO-E2E-DIRECTORIES-ONLY]` S2 · `[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]` S2 ·
`[GATING-LANE-SKIP-HAS-NO-GUARD]` S2 · `[THEME-GUARD-MISSES-PROTOCOL-RELATIVE-URLS]` S2 ·
`[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]` S2 · `[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]` S2 ·
plus the new `[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]` S2.

**Cluster D — silent failures**
`[OGN-SYNC-SWALLOWS-ITS-OWN-FAILURE]` S2 · `[REQUEST-ID-NEVER-LOGGED]` S2.

**Cluster E — tests that assert less than their name**
`[VACUOUS-NARROWING-ASSERTIONS]` S2 · `[TENANT-ISOLATION-IT-PREFIX-COLLISION]` S2 ·
`[PERSONS-DETAIL-ROUTE-MAY-BE-SHADOWED]` S2 · `[MOCK-CLUB-ID-SHAPE]` S2 · `[SUITE-ISOLATION]` S2 ·
`[NAV-OVERLAY-EATS-CLICKS]` S2 · `[LEGACY-J2-READINESS]` S2 · op-field-mutate coverage S2 ·
fanout reporting spec over migrated data S2.

**Cluster F — structural remainder**
`[GH-PAGES-HISTORY-IS-UNBOUNDED]` S2 · `[J-32-GATE-NITS]` S2 (four nits) · orval positional `getN` S2 ·
JIT-username robustness S2.

## Notes

**The fan-out is a hard merge gate, and this journey is its first real test.** `AuditLogMapper.java`
sits under `alpenflight/migration-bundle/**`, which `alpenflight-proof-fanout.yml` lists in its
`on.push` paths. So Cluster A's S1 mapper fix arms the push trigger on `integration/J-33`. That makes
J-33 the journey `[FANOUT-PUSH-ARM-IS-AUTHORED-BUT-NEVER-FIRED]` names as its real test. Confirm the
trigger fires and the gate waits. Do not assume it did. J-32's carve wrote `migration: N/A` and was
wrong; this carve states it up front.

**The FK target is available.** `AUDIT_LOG.actor_user_id` resolves to `t_user`, and `UserMapper`
already migrates that entity. No dependency journey is owed.

**Cluster B crosses a roadmap boundary.** `_ORDER.md:23` gives the migrate surface to J-21. J-33 does
not build J-21's upload wizard. It fixes the funnel dead-end that J-16 shipped, so the advertised
path completes. Keep the scope at the handshake.

**Order.** Run Cluster A and Cluster B first — they carry every S1 and both screen proofs. Run
Cluster C next, because a wired gate protects the rest. Clusters D to F fill the remainder.

**Named deferrable tail.** Clusters E and F are the tail. If the gate surfaces heavy unforeseen work,
ship Clusters A to D complete and re-file E and F, rather than half-shipping all six. Do not narrow
Cluster A or B — they hold the S1 riders and the screen proof.

**Guard bar.** Every guard this journey wires or widens plants a violation per input class and scores
the old code ([[feedback_gate_must_prove_a_red_per_input_class]]). J-19 shipped four guards that each
missed a class inside their own stated scope.

**Verify one level out.** Check the guard is wired, the lane runs, and the test still asserts
([[feedback_verify_one_level_out]]).

**No design reference exists for these screens.** `docs/modernization/design-reference/` holds
entry, home, logbook, misc, public and reservations. Neither `/system/logs` nor `/migrate/start` has
a reference screen. Both are built, so structure comes from the shipped screen.

## Tasks

Each task opens by confirming or refuting the rider's stated cause against the tree, and says which
([[feedback_rider_symptom_is_evidence_cause_is_a_guess]]). A green is a hypothesis too.

**Scaffold**
- [x] T-01 — Tag this journey's proof videos `journey: 'J-33'`. `audit-log-two-club.spec.ts` emits J-32/J-30/J-13 today; `register.spec.ts` calls `proofVideo` zero times. Zero J-33 videos reds the bookmark guard.
- [x] T-02 — `Derive journey proof spec` (`ci.yml:256`) reads only the FIRST path token of `parity_test:`, so `register.spec.ts` is dropped and Cluster B gets no lane. Carry every token. Plant a red per input class (one spec, two specs, each separator).
- [x] T-03 — `[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]`. Add the missing Flyway placeholder. This test is red today, so it ships first.

**Cluster A — audit attribution and legibility (proves on `/system/logs`)**
- [x] T-04 — S1 `[ANON-FAILED-WRITE-READS-AS-SYSTEM]`. Give `AuditTrailService.recordFailed` the `ANONYMOUS_PUBLIC` kind and the client IP. Kind shipped. The client IP needs the club, which `[FAILED-ANONYMOUS-ROW-NAMES-NO-CLUB]` and T-08 own.
- [x] T-05 — S1 `[AUDITLOGMAPPER-DECLARES-NO-FOREIGN-KEY-COLUMNS]`. Declare `actor_user_id` in `AuditLogMapper.foreignKeyColumns()`. Arms the fan-out push trigger. Cause confirmed. A new class guard scores every mapper and found three more mappers with the same defect, filed in `_BOYSCOUT.md`. `AUDIT_LOG` still has no producer binding and no ingest table, so AC-3 needs that rider too.
- [ ] T-06 — S2 `[AUDIT-LOGS-STORE-403-FALLS-BACK-SILENTLY]`. Admit `SYSTEM_ADMINISTRATOR` to the user lookup. Delete the silent fallback. Shared surface — grep the cross-journey consumers first.
- [ ] T-07 — S2 `[UNDECIDED-AUDIT-SNAPSHOT-FIELDS]`. Decide the snapshot fields for the 15 pinned sites.
- [ ] T-08 — S2 `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]`. Measure the seam. Raise the ADR 0008 decision to the operator. Do not choose.
- [ ] T-09 — S3 `[AUDIT-ACTOR-KIND]`. Decide the dead `SYSTEM` constant with cluster A.

**Cluster B — the migrate and signup funnel (proves on `/migrate/start`)**
- [ ] T-10 — S1 `[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]`. Materialize the club-less registrant so the handshake issues. Keep the scope at the handshake — do not build J-21's wizard.
- [ ] T-11 — S2 `[INGEST-CROSS-TENANT-REJECTION-READS-AS-500]`. Map the tenancy rejection to a 4xx with a named reason.
- [ ] T-12 — S2 `[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]`. Drive bare `/signup` to `/join` in a real-idp spec.

**Cluster C — gates that never run, or do not read their own inputs**
Every guard here plants a violation per input class and scores the old code ([[feedback_gate_must_prove_a_red_per_input_class]]).
- [ ] T-13 — S2 `[ARCHUNIT-AND-NULLAWAY-DEMO-GATES-NEVER-RUN]`. Wire both demo gates into a lane CI runs.
- [ ] T-14 — S2 `[CHECK-THEME-LOAD-IS-ROTTEN-AND-UNWIRED]`. Wire the theme-load script to a CI job.
- [ ] T-15 — S2 `[THEME-GUARD-MISSES-PROTOCOL-RELATIVE-URLS]`. Widen the pattern to protocol-relative URLs. Plant the script class too.
- [ ] T-16 — S2 `[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]`. Typecheck `alpenflight/web/scripts/**`.
- [ ] T-17 — S2 `[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]`. Repair `e2e/tsconfig.json` and add the `e2e` typecheck lane.
- [ ] T-18 — S2 `[NG-LINT-COVERS-TWO-E2E-DIRECTORIES-ONLY]`. Lint every `e2e/` directory. Fix the seven live errors.
- [ ] T-19 — S2 `[GATING-LANE-SKIP-HAS-NO-GUARD]`. Ban a non-negated real-bundle `test.skip`.
- [ ] T-20 — S2 `[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]`. Widen `GUARDED_DATE_FIELDS`. Repair the specs it reds.
- [ ] T-21 — S2 `[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]`. Run the `e2e/` suite on a pull request that touches `e2e/`.

**Cluster D — silent failures**
- [ ] T-22 — S2 `[OGN-SYNC-SWALLOWS-ITS-OWN-FAILURE]`. Report the failure instead of a false success.
- [ ] T-23 — S2 `[REQUEST-ID-NEVER-LOGGED]`. Align the MDC key with the logback pattern. Assert a real request id in a log line.

**Cluster E — tests that assert less than their name** (named deferrable tail)
- [ ] T-24 — S2 `[VACUOUS-NARROWING-ASSERTIONS]`. Seed the excluded row. Send a mixed-case payload.
- [ ] T-25 — S2 `[TENANT-ISOLATION-IT-PREFIX-COLLISION]`. Give each isolation IT a distinct prefix.
- [ ] T-26 — S2 `[PERSONS-DETAIL-ROUTE-MAY-BE-SHADOWED]`. Prove which fixture the detail GET reads.
- [ ] T-27 — S2 `[MOCK-CLUB-ID-SHAPE]`. Give the mock the real `clb-<uuid>` shape.
- [ ] T-28 — S2 `[NAV-OVERLAY-EATS-CLICKS]`. Stop the overlay from taking the click.
- [ ] T-29 — S2 op-field-mutate coverage. Assert a changed `nrOfLdgs` on an unchanged-identity crew row.
- [ ] T-30 — S2 fanout reporting spec over migrated data. Assert `/flightreports` over the migrated dataset.

**Cluster F — structural remainder** (named deferrable tail)
- [ ] T-31 — S2 `[GH-PAGES-HISTORY-IS-UNBOUNDED]`. Bound the history, not only the published tree.
- [ ] T-32 — S2 `[J-32-GATE-NITS]`. Fix the four nits.
- [ ] T-33 — S2 orval positional `getN`. Give the endpoints explicit `operationId`s.
- [ ] T-34 — S2 JIT-username robustness. Reject a distinct sub that reuses a live username.

**Gate**
- [ ] T-35 — Thicken both proof specs to the full oracle assertions. Delete `register.spec.ts`'s known-defect declaration.
- [ ] T-36 — S2 `[FANOUT-PUSH-ARM-IS-AUTHORED-BUT-NEVER-FIRED]`. Confirm the push arm fired and the gate read its verdict (AC-10).

Deferred, and re-filed rather than half-shipped: `[SUITE-ISOLATION]` (a test-architecture restructure) and
`[LEGACY-J2-READINESS]` (a quarantine whose flaky set moves run to run — the carve says do not read it as a
new defect).

## Assumptions made

1. The operator asked to drain S1 and S2. Thirty-three riders exceed one journey's normal reach, so
   the carve names a deferrable tail rather than promising all six clusters.
2. `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]` proposes deleting a seam that ADR 0008 names. The
   task raises the decision to the operator rather than choosing.
3. The nightly fix ships with the carve because the operator asked for it directly. It is one line
   and it restores a red main gate.
