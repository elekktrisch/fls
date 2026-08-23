---
id: J-33
title: Audit attribution and the migrate dead-end — drain every S1 and S2 rider (hardening)
epic: E-13
status: done
started_at: 2026-08-23
done_at: 2026-08-23
journey0: false
hardening: true
carved: true
depends_on: []
rolls_up: []
acceptance:
  - "[happy] A club administrator opens /system/logs. Every actor cell reads a user name or a named notice, never a raw Keycloak subject."
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

1. A club administrator signs in and opens `/system/logs`. Each actor cell reads a user name or a
   named notice. **AC-1 was re-worded on 2026-08-23 (operator).** The original said "a system
   administrator". That scenario has never been reachable: `check-realm-shape.sh:75` enforces that
   `sysadmin` carries no `clubId`, and `club-admin.guard.ts:11` redirects a principal without one.
   No `systemAdminGuard` exists. All nine specs on this screen sign in as a club administrator.
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

8. The nightly legacy suite reads 0 failed. The carve fixed a `#username` strict-mode violation in
   `lostpassword-parity-J19.spec.ts` and proved it on
   [run 32554637416](https://github.com/elekktrisch/fls/actions/runs/32554637416): 155 passed,
   **0 failed**. `main` un-reds when this journey merges.

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
- [x] T-06 — S2 `[AUDIT-LOGS-STORE-403-FALLS-BACK-SILENTLY]`. Cause CONFIRMED and measured: a real SYSTEM_ADMINISTRATOR JWT reads `403 FORBIDDEN` from `GET /api/v1/users`. `listUsers` now admits both admin roles, and the widening leaks nothing — `UsersService.listInCurrentTenant` scopes the answer to the caller's own club, which is the same tenant the audit page already reads. The two consumers are `audit-logs.store.ts` and `users.store.ts`; no other surface calls it. The silent fallback is gone: the store records the failure and `/system/logs` renders a named notice above the table. A PILOT negative test proves the widening admits no lower role. Third gap found and filed as `[SYSTEM-ADMINISTRATOR-CANNOT-REACH-THE-AUDIT-SCREEN]` — the SPA route guard and the nav still admit a club administrator only, so AC-1 needs an operator decision before a SYSTEM_ADMINISTRATOR-only principal can drive it.
- [ ] T-07 — S2 `[UNDECIDED-AUDIT-SNAPSHOT-FIELDS]`. Decide the snapshot fields for the 15 pinned sites.
- [ ] T-08 — S2 `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]`. Delete `RequestTenantHint` (operator, 2026-08-23). No ADR amendment is owed — see §Decisions.
- [ ] T-09 — S3 `[AUDIT-ACTOR-KIND]`. The rider is STALE: `SYSTEM` has a live writer and `AuditEventRow` carries `actorKind` (T-04 measured this). Re-confirm, then delete the rider. Expect no code change.

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

**Gate-surfaced — these block an AC, so they ship in-journey**
- [x] T-37 — S1. `AUDIT_LOG` has no `MapperLegacyBindings` entry, and `EntityStreamIngestor.destinationTableFor` computes `t_audit_log`, not `t_mutation_audit_event`. Both halves CONFIRMED and closed. `AUDIT_LOG` left `KNOWN_UNBOUND`, so the real fan-out now exports audit rows. A third gap remains and it belongs to the operator: a migrated row carries no tenant, so `/system/logs` renders none of them — filed as `[MIGRATED-AUDIT-ROW-CARRIES-NO-TENANT-SO-NO-SCREEN-RENDERS-IT]`.
- [ ] T-38 — S1. T-05's new guard found the same missing `foreignKeyColumns()` in three producer-bound mappers: `DeliveryMapper` (`club_id`, `person_id`), `DeliveryItemMapper` (`club_id`), `PersonFlightTimeCreditTransactionMapper` (`person_flight_time_credit_id`). The real fan-out exports all three. Fix them and unpin the guard.
- [ ] T-39 — AC-2's client IP. Resolve the club from the URL slug BEFORE the abuse guard, and cache the slug lookup (operator, 2026-08-23). `AuditTrailService.java:22` also hard-codes a null client IP — a club alone does not make the IP land.
- [x] T-40 — AC-3 on the screen. Backfill each migrated audit row's `tenant_club_id` from the entity the row describes, per S-189 (operator, 2026-08-23). Cause CONFIRMED. The ingest now runs `MigratedAuditRowTenantBackfill` after every entity stream drains, so a migrated row takes the club of the entity it describes and its club administrator reads it on `/system/logs`. Three classes stay NULL and the IT asserts each: a cross-tenant target (Person, Aircraft — the destination table carries no club column), a fanned-out target (Location, InOutboundPoint — one replica per club names no single club), and a target the bundle never migrated. V61 grants the app role `UPDATE (tenant_club_id)` on that table alone, so the V54 append-only carve-out still refuses every other UPDATE and every DELETE. Caption the NULL limit with the gallery video in T-35.

- [x] T-41 — Fan-out red on sha `406f52b53`. Cause CONFIRMED against a real SQL Server at level 100: `TRY_CONVERT` is the only construct the level refuses. A LIKE-guarded `CONVERT` replaces it and returns the same value for every RecordId class. `HASHBYTES('SHA2_256')`, `ROW_NUMBER() OVER`, `FIRST_VALUE() OVER` and `JSON_VALUE` all run at level 100 — measured, not assumed. The extract fixture now applies the compatibility level the legacy scripts declare and refuses to seed if the level does not take, and `LegacyProducerSelectCompatibilityLevelTest` compiles every registered producer SELECT against that fixture.

- [x] T-42 — S2. Both halves CONFIRMED and measured. `./gradlew build --dry-run` in server, migration-tool and database/extract each lists `:migration-bundle:{compileJava,processResources,classes,jar}` and no `:migration-bundle:test`, so 492 unit tests — including the T-05 and T-37 guards — scored nothing. The new `migration-bundle-tests` job sits at the graph root of `ci.yml` with no `paths:`, no `if:` and no `needs:`, and `required` reds when it fails, skips or cancels. `FlsTestSchemaApplier.java:19` carried the same compatibility-level hole and now applies the declared level and refuses a fixture that does not take it. Wiring the lane surfaced that `parityTest` has NEVER passed: T-42 closed three of its four causes and filed the fourth as `[PARITY-ORACLE-HARNESS-HAS-NEVER-PASSED]` S1, because it needs an operator decision on a club whose `ClubStateId` names no `ClubStates` row.

- [x] T-43 — Parity harness, test-only. The T-42 S1 framing is REFUTED and the refutation is measured: `ClubStates` seeds 0, 1, 2 and 3, and `ClubStateId = 0` is the System sentinel the legacy `ClubService.cs:78` hides from users. `LegacyIdMapPopulator.java:52-59` keyed its map by CODE while production keys by GUID, so the 4-to-3 club-state map dropped guid `0` and the run died at `ForeignKeyRewriter.java:29` on `00000000-0000-0000-0000-000000000000`. The populator now iterates bundle rows and resolves each natural key to its seed primary key, as `BundleWriter.java:201` does. Two further causes fell in `ParityDiffEngine`: it counted the legacy side from the raw table, which scored the one `Users` row the producer SELECT excludes, and it counted the new side including the Flyway dev seed. `parityTest` PASSES for the first time (2 tests, 0 skipped) and `ci.yml` now EXECUTES it with `PARITY_REQUIRES_DOCKER=1`, so an unreachable Docker daemon reds the lane instead of skipping it.

**Gate**
- [ ] T-35 — Thicken both proof specs to the full oracle assertions. Delete `register.spec.ts`'s known-defect declaration.
- [ ] T-36 — S2 `[FANOUT-PUSH-ARM-IS-AUTHORED-BUT-NEVER-FIRED]`. The arm FIRED on the T-05 push ([run 32616326231](https://github.com/elekktrisch/fls/actions/runs/32616326231), event `push`). Confirm the gate WAITS for it and READS its verdict (AC-10).

## Outcome

**The operator closed this journey early on 2026-08-23, with clusters B to F deferred.** The session
reached its subagent limit, and the operator chose a smaller honest PR over a degraded full run.
Eleven tasks shipped. Twenty-eight are re-filed.

**Shipped.** Cluster A's audit attribution (T-04, T-05, T-06), the whole migration chain
(T-37, T-40, T-41, T-43), the gate work (T-01, T-02, T-42) and the red mapper-versus-schema test
(T-03). All three carved S1 riders are fixed, plus two S1 defects that this journey's own new guard
found.

**Deferred to `/do-plan`.** Cluster B (the migrate funnel, T-10 to T-12), cluster C's remaining gate
riders (T-13 to T-21), cluster D (T-22, T-23), clusters E and F (T-24 to T-34), plus T-38, T-39,
T-35 and T-36. The rider bullets stay in `_BOYSCOUT.md`, so nothing is lost.

**Acceptance criteria not met.** AC-4, AC-5, AC-6 and AC-7 are NOT met — their tasks did not run.
AC-2 is HALF met: the actor kind reads `ANONYMOUS_PUBLIC`, but the client IP does not land, because
T-39 did not run. AC-3 is proved at the data layer, not on the screen, because T-35 did not run. The
PR checklist states each one on its line.

**Five rider causes were wrong as stated.** The mapper-versus-schema test was not rotting in CI, it
is order-dependent. `[AUDIT-ACTOR-KIND]` is stale. The first AC-2 chain named the wrong link — the
client IP is dropped at `AuditTrailService.java:22`. The parity S1 escalation was test-harness-only,
and its claim about the seeded `ClubStates` set was false. AC-1's own persona was unreachable. Every
catch came from a worker or an investigation that MEASURED
([[feedback_rider_symptom_is_evidence_cause_is_a_guess]]).

**The largest find.** No CI lane ran `migration-bundle`'s tests, so 492 unit tests scored nothing —
including the guards T-05 and T-37 shipped in this journey. T-42 wired a root-level lane. T-43 then
made the parity oracle pass for the first time and wired it to execute.

## Decisions

**2026-08-23 — AC-2's client IP (operator).** Resolve the club from the URL slug before the abuse
guard, and add a slug cache. S-025 already requires tenant context before the controller body runs,
and an audit entry that names the club and the IP, so today's code violates a shipped contract. The
cache answers the one measured risk: an uncached database read in front of the rate limiter.
Rejected: a club-less row that carries an IP (it needs a `privacy-notice.md` §1 amendment) and
dropping the IP from AC-2 (it leaves the S-025 violation open).

**2026-08-23 — AC-3 on the screen (operator).** Backfill the migrated audit row's club from the
entity the row describes, per S-189. ADR 0008 makes tenancy structural, so a NULL-tenant row is an
anomaly that would also break the retention sweep and the erasure endpoint later. Rejected: an
unscoped system-administrator read (it adds a tenancy bypass to an audit surface, and a club
administrator would still see no migrated row) and proving AC-3 at the data layer only (the operator
prefers proof through the UI).

**2026-08-23 — `RequestTenantHint` (operator).** Delete it. ADR 0008 §Amendment S-159 names
`RequestAuditFilter`, never `RequestTenantHint`, and it permits rather than requires. Every writer is
package-private, the one reader always gets null, and an ArchUnit rule already forbids a new
producer. No ADR amendment is owed.

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
