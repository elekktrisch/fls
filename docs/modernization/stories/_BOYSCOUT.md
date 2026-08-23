# Boyscout riders

Fix-forward backlog. Per the operator's standing rule (**no tiny stories** — see
[[feedback_no_tiny_stories_fix_forward]]), mechanical/bounded work (bug fixes,
one-liners, doc reconciliations, guard tests, file deletions — however many) does NOT
get its own story/journey. It's recorded here and **folded into the next journey** that
runs the gate, so the fix flows through the do-* workflow and produces gate + gallery
proof the operator can see.

`/do-plan` (Mode B) scans this file for riders touching the journey's surface and notes
them in the journey file; `/do-ship` folds them into the task list (sized per its gate)
and **clears the bullet here as it ships**. A standalone journey is filed only for
genuinely new vertical feature scope.

**Standing rule: when a rider ships, DELETE its bullet here — never mark it
✅/struck-through and leave it.** This file holds only pending work; shipped riders live
in git + the PR. `/do-ship` deletes a rider as it ships; `/do-retro` sweeps any
stragglers each ceremony so the file shrinks.

**Burndown moved to a dedicated journey (operator, 2026-08-19).** Neither oldest-first (J-17 retro) nor
severity-first (J-31 retro) drained this file: it went ~17 → 45 riders, and J-19 alone burned 2 while filing 9.
A per-journey slot cannot match the discovery rate, and throttling the filing would be worse — J-19's riders
came from real `gap-hunter` and worker findings. J-32 (hardening) drained the S1 riders and re-filed the
S2 tail. **J-33 (hardening) now owns draining every S1 and S2** (operator, 2026-08-22).
`/do-ship` still folds on-surface riders opportunistically. Filing stays unrestricted.

**Severity markers** — `/do-retro` tags every rider, `/do-ship` burns them down highest-first:
**[S1]** security / tenancy / correctness / money · **[S2]** coverage gap / silent-failure risk ·
**[S3]** cosmetic / dead code / doc.

## Pending (filed by /do-ship J-33 T-05, 2026-08-23)

- **[THREE-MAPPERS-SEEK-A-FOREIGN-KEY-COLUMN-THEY-NEVER-EMIT]** [S1] **Symptom (measured, not inferred).**
  T-05 added `MapperForeignKeyColumnDeclarationTest`, which scores every `KnownMappers` entry against the
  column `ForeignKeyResolver` seeks. It reds on four mappers. T-05 fixed `AuditLogMapper`. Three remain, and
  the test pins them in `KNOWN_UNDECLARED_AWAITING_ITS_OWN_MIGRATION_PROOF`: `DeliveryMapper` seeks `club_id`
  and `person_id` while it emits `operating_club_id` and `recipient_person_id`; `DeliveryItemMapper` seeks
  `club_id` while it emits `operating_club_id`; `PersonFlightTimeCreditTransactionMapper` seeks
  `person_flight_time_credit_id` while it emits `credit_id`. **Cause (hypothesis).** Each declares
  `foreignKeyTargets()` but no `foreignKeyColumns()`, so the resolver falls back to the `<target>_id`
  convention and rewrites nothing. `ArticleMapper:61`, `AccountingRuleFilterMapper:100` and
  `PlanningDayMapper:58` declare the same column correctly, so the omission looks like drift, not a decision.
  Unlike `AUDIT_LOG`, all three entities carry a `MapperLegacyBindings` producer entry, so the real fan-out
  exports them. Measure each against the real ingest before you fix it; `recipient_person_id` is a reviewed
  cross-tenant column, so its repair needs the tenancy review too.
  *(seam: `DeliveryMapper`, `DeliveryItemMapper`, `PersonFlightTimeCreditTransactionMapper`)*

## Pending (filed by /do-ship J-33 T-42, 2026-08-23)

- **[PARITY-DIFF-ENGINE-TRUSTS-THE-PRODUCER-SELECT]** [S2] **Symptom (measured).** T-43 made
  `ParityDiffEngine` count the legacy side through `MapperLegacyBindings.selectForProducer`, because the
  raw-table count scored the one `Users` row the USER producer SELECT excludes by id as a false red. The
  oracle now proves "every row the producer emitted landed in Postgres". It no longer proves "the producer
  emitted every row the legacy table holds": a producer SELECT that silently drops legacy rows shrinks both
  sides of the comparison and stays green. **Cause (hypothesis, not measured).** The engine has no
  declaration of which legacy rows a mapper deliberately excludes, so it cannot separate a deliberate
  exclusion from a lost row. A per-binding exclusion count that the engine adds back would score both
  classes. [[project_synth_bundle_doesnt_validate_producer_select]]
  *(seam: `ParityDiffEngine.java:23`, `MapperLegacyBindings`)*

- **[PARITY-REJECT-AND-META-TASKS-HOLD-ZERO-CASES]** [S2] **Symptom (measured).** `parityRejectTest` and
  `parityMetaTest` both complete `BUILD SUCCESSFUL` in seconds and write an empty result directory. No test
  in `src/parity/java` carries `@Tag("parity-reject")` or `@Tag("parity-meta")` — `ParityOracleHarnessTest`
  carries `@Tag("parity")` only. S-187a shipped the two Gradle tasks and S-187d still owes their cases, so
  each task is a gate that cannot fail. T-42 measured that Gradle 9.4.1 `failOnNoDiscoveredTests` does NOT
  score this: a tag filter that selects nothing still passes, and an empty `testClassesDirs` makes Gradle
  skip the task as NO-SOURCE. So the guard must be the missing cases, not a build flag. T-43 re-measured
  it: still zero tagged cases, and T-43 did not invent any.
  *(seam: `alpenflight/migration-bundle/build.gradle.kts:89`, S-187d)*

- **[DOCKER-SKIP-TURNS-AN-MSSQL-GUARD-GREEN]** [S2] **Symptom (measured).**
  `LegacyProducerSelectCompatibilityLevelTest:21` is `@EnabledIf("dockerAvailable")` and swallows the
  container start failure. A Docker blip on the runner turns the guard into a silent skip, and the lane
  reads green. T-42 confirmed it executes today (3 tests, 0 skipped, on this box). **Cause (hypothesis).**
  The condition exists for a developer box with no Docker. T-43 closed the same hole on the parity side
  with a `PARITY_REQUIRES_DOCKER` env var that rethrows, and measured both arms: unreachable Docker with
  the variable reads `initializationError FAILED`, without it reads `BUILD SUCCESSFUL`. The extract guard
  owes the same treatment.
  *(seam: `alpenflight/database/extract/src/test`)*

- **[EXTRACT-LANE-REDS-NOTHING-A-MERGE-DEPENDS-ON]** [S2] **Symptom (measured).** `extract.yml` runs
  `LegacyProducerSelectCompatibilityLevelTest` and `MetadataExtractorIntegrationTest`, but the job is in no
  `required.needs` list, because `required` lives in `ci.yml` and a job cannot depend on another workflow.
  A red extract lane shows as a red check and blocks no merge. The same holds for `alpenflight-e2e.yml` and
  `nightly.yml`. Either move the lane into `ci.yml` or add it to the branch-protection contexts.
  *(seam: `.github/workflows/extract.yml`, branch protection)*

## Pending (filed by /do-ship J-33 gate, 2026-08-23)

- **[FIXTURE-TABLE-NAMING-GUARD-SCANS-PROSE]** S3 — `FixtureTableNamingConventionTest` applies its
  `FROM <token>` bare-table-name regex to English prose inside assertion description strings, not only
  to SQL. **Symptom (evidence):** it failed the CI server build on
  `ForeignKeyResolverColumnDeclarationTest.java:158` for the phrase "not from this test", reporting the
  table name `this`. **Cause (hypothesis, unmeasured):** the scan does not distinguish a SQL string
  from an `.as(...)` description. **Seam:** `FixtureTableNamingConventionTest.java:83`. J-33 reworded
  the sentence rather than allow-list `this`, which would have blunted the guard for a real token. The
  guard is otherwise live — proven red on the original string and green on the reworded one.

## Pending (filed by /do-plan J-33 carve, 2026-08-22 — main-branch red)

The red itself is fixed in the carve commit and is **folded into J-33** (see
`J-33-audit-attribution-and-migrate-dead-end.md` §"Main-branch red"). The gate hole it exposed
outlives that fix and stays here.

- **[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]** [S2] `nightly.yml` triggers on `schedule` and
  `workflow_dispatch` only, so a spec added under `e2e/` **never runs before merge**. J-19 authored
  `lostpassword-parity-J19.spec.ts`, CI stayed green, and the spec's FIRST real run was the
  2026-08-20 nightly — which reds deterministically on a `#username` strict-mode violation and stayed
  red for three nights. The legacy stack is too heavy for every pull request, so the answer is not
  "run the nightly on push". Options: run only the specs a pull request TOUCHES under `e2e/`, or gate
  the merge on a dispatched nightly when the diff reaches `e2e/`. A suite that cannot red before
  merge does not gate the change that breaks it.
  *(seam: `nightly.yml` triggers + an `e2e/`-touching pull-request lane)*
  [[feedback_verify_infra_is_run_not_just_authored]] [[project_gate_must_cover_its_own_inputs]]

## Pending (filed by /do-ship J-32 close-out, 2026-08-21)

The operator decided on 2026-08-21 to ship J-32 on its S1 work and to re-file the S2 tail. Each bullet
below names a defect a J-32 task found and did not fix. J-32 also leaves the carve-time S2 riders in
this file untouched.

- **[FANOUT-PUSH-ARM-IS-AUTHORED-BUT-NEVER-FIRED]** [S2] J-32 T-67 made a `git push` arm the fan-out and
  made the gate wait for it, so no human triggers a run any more. The selftest scores 38 input classes,
  but the **`push`-arm, wait and self-dispatch paths have never executed**: every fan-out in J-32 was a
  `workflow_dispatch`, and no producer-tree change remained to arm the new trigger. The operator decided
  on 2026-08-21 that the next journey touching a producer mapper is the real test. That journey must
  **confirm the trigger fires** and the gate waits, and must not assume it did — authored infra can be
  wired wrong in a way only an end-to-end run reveals
  ([[feedback_verify_infra_is_run_not_just_authored]]). *(seam:
  `.github/workflows/alpenflight-proof-fanout.yml` `on.push` + `fanout-parity-verdict.py` `resolve()`)*
- **[NG-LINT-COVERS-TWO-E2E-DIRECTORIES-ONLY]** [S2] `ng lint` reads `src/**` plus the two real-idp lane
  directories. The other approximately twenty `e2e/` directories are gated by nothing, and they carry
  **seven live errors** today — `e2e/tests/landing/landing.spec.ts:122` and
  `e2e/tests/public/signup.spec.ts:77,87,96,105,125,144`, all `sessionStorage` inside `page.evaluate`,
  which looks like a false positive of an app-side rule. T-63 refused to silence the rule to widen the
  gate, which was correct. Decide the rule first, then widen the lane. *(seam: `angular.json`
  `lintFilePatterns` + the app-side rule)*
- **[GATING-LANE-SKIP-HAS-NO-GUARD]** [S2] T-65 deleted a `test.skip` that hid the migrated-copy rename
  from the gating fan-out lane for months. Nothing stops the next author adding one. A
  `no-restricted-syntax` rule banning a non-negated real-bundle `test.skip` under `e2e/tests/real-idp/`
  would hold it. T-65 did not build it, because the rule needs a proven red per input class and that is
  its own task. *(seam: `eslint.config.mjs` + the real-idp lane)*
- **[FAILED-ANONYMOUS-ROW-NAMES-NO-CLUB]** [S2] J-33 T-04 gave the rejected anonymous write the
  `ANONYMOUS_PUBLIC` kind. The row still names no club, and it keeps no client IP. Measured on
  `integration/J-33`: the 429 row reads `tenant_club_id = null` and `client_ip = null`. Two causes sit
  outside T-04's seam. First, `PublicRegistrationIntake.java:56` runs the abuse guard before
  `PublicClubResolver.resolve`, so the rejection holds the slug and never the club id. Second,
  `Tenants.runAs` restores the request hint in its `finally` block, so `RequestAuditFilter.java:80` always
  reads null — `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]` owns that decision. A row that names no club
  keeps no client IP by design: `MutationAuditEvent.java:291` refuses the value,
  `MutationAuditEventListener.java:136` drops it, and `docs/modernization/privacy-notice.md` §1 states the
  rule. The audit projection also filters by tenant, so no administrator reads this row on `/system/logs`.
  Decide this rider together with `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]`: give the failed row its
  club first, then record the client IP through the path the successful submission uses. Do not relax the
  club-scoped rule on its own — the erasure endpoint reaches a row through its club.
  *(seam: `RequestAuditFilter` + `RequestTenantHint` + `PublicRegistrationIntake`)*
- **[UNDECIDED-AUDIT-SNAPSHOT-FIELDS]** [S2] The T-45 guard found **fifteen** more audit call sites that
  pass a snapshot whose class the recorded `entityType` does not describe — Article, PlanningDay,
  EmailTemplate, UserRole, PersonLookup, User and Delivery among them. Each row renders almost empty,
  because an unmatched type gets an empty allow-set. The sites are pinned in
  `alpenflight/server/config/audit/undecided-audit-snapshot-fields.txt`. Decide each one, as T-45 did for
  its five. *(seam: the audit call sites + `application.yml` redaction config)*
- **[OGN-SYNC-SWALLOWS-ITS-OWN-FAILURE]** [S2] `HttpOgnDeviceDatabase.java:53` catches `RuntimeException`
  and reports the aircraft device sync as a success. The job writes nothing and says it worked. T-07d
  found this while it enumerated the `ObjectProvider` injection shape. *(seam: `HttpOgnDeviceDatabase`)*
- **[SYSTEM-ADMINISTRATOR-CANNOT-REACH-THE-AUDIT-SCREEN]** [S2] T-06 opened the backend for a system
  administrator: `UsersController.listUsers` and `AuditAdminController` now admit the same two roles. The
  SPA still refuses the screen. `audit-logs.routes.ts:7` applies `clubAdminGuard`, which demands
  `currentClubId() !== null` AND `isClubAdmin()`, and `nav-sections.ts:26` lists `/system/logs` under
  `MASTERDATA_CLUB_ADMIN_ITEMS`. The seeded `sysadmin` realm user carries no `clubId` attribute
  (`realm-export.json`), so it fails both halves of the guard and reads no tenant. Two decisions belong to
  the operator: does a system administrator get `/system/logs`, and which tenant do they read? Until then
  no real-idp spec can drive AC-1 with a SYSTEM_ADMINISTRATOR-only principal.
  *(seam: `clubAdminGuard` + `nav-sections` + the sysadmin tenant)*
- **[INGEST-CROSS-TENANT-REJECTION-READS-AS-500]** [S2] The bundle ingest maps a cross-tenant foreign-key
  rejection to `500 INGEST_INTERNAL_ERROR`, not a `4xx`. A tenancy defence reads as a server fault, so an
  operator cannot tell a rejected bundle from a broken server. T-51 found it. *(seam: the ingest error map)*
- **[NAV-OVERLAY-EATS-CLICKS]** [S2] `nav.ts:21` — an overlay takes a click the test aimed at the element
  behind it. T-10 found it while it drove the proof spec. *(seam: the nav overlay)*
- **[J-32-GATE-NITS]** [S2] Four nits the mid-journey `gap-hunter` round raised and J-32 did not fix: the
  impersonation guard exempts all of `ClubsController` when only `deleteClub` needs it, so `updateClub`
  could lose `@tenant.isOwnClub` and pass; `ClientIpRetentionIT` asserts 90 days plus a margin, so only the
  unit test hits the true boundary; and `ClientIpRedaction.java:38` re-checks a window the query already
  filtered, which no input can reach.

## Pending (filed by /do-ship J-32 T-03, 2026-08-20)

- **[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]** [S2] T-03 deleted `AuditTargetTenantInterceptor`. That
  interceptor was the only writer of a `RequestTenantHint` attribute that outlives the handler, because it
  never restored the attribute. `Tenants.runAs` writes the same attribute, but it restores the prior value in
  its `finally` block. `RequestAuditFilter` reads the attribute in the outermost `finally`, after every
  `runAs` unwound, so `RequestTenantHint.currentForRequest` now always answers null. The
  `targetTenantHint != null` branch at `alpenflight/server/src/main/java/ch/alpenflight/audit/web/RequestAuditFilter.java:80-91`
  is unreachable. Decide: delete `RequestTenantHint` and that branch, then drop `RequestAuditFilter` from the
  `TenantsRunAsAllowlistTest` allow-list — or keep the hint and give it a producer. [ADR 0008](../adrs/0008-multi-tenancy-mechanism.md)
  §Amendment S-159 names `RequestAuditFilter` as an in-process `runAs` seam, so the deletion needs the
  operator. *(seam: `RequestTenantHint` + `RequestAuditFilter` + `TenantsRunAsAllowlistTest`)*
- **[ARCHUNIT-AND-NULLAWAY-DEMO-GATES-NEVER-RUN]** [S2] `verifyArchUnitFailsOnViolation`
  (`alpenflight/server/build.gradle.kts:339`) and `verifyNullAwayFailsOnViolation` (`:146`) both prove that a
  guard reds on a planted violation. Neither task depends on `check`, and `ci.yml:583` runs only
  `./gradlew build`, so neither has ever run in CI. The `src/archDemo/java` and `src/nullawayDemo/java`
  violations are therefore unscored. Wire both into `check`, or delete the two source sets and their tasks.
  Same class as the `extract.yml` hole: the repository authors a gate and never runs it.
  *(seam: `alpenflight/server/build.gradle.kts` check wiring)* [[feedback_verify_infra_is_run_not_just_authored]]

## Pending (filed by /do-retro J-19 window, 2026-08-19)

Found by the confirming `gap-hunter` round AFTER #251 merged.

- **[COMMENT-GATE-DOES-NOT-COVER-GITHUB-DIR]** [S3] The J-31 sweep deleted every comment in
  `alpenflight/` and `e2e/`, and the gate at `ci.yml:105` reads those two roots only. `.github/`
  was never swept, so it still holds 1850 comment lines in 5895 (31%), and `ci.yml` alone holds 883
  in 2501 (35%). The operator reads that narration as a policy breach, because the rule is one rule
  for the repository. The stripper maps `.yml`, `.yaml` and `.sh`, so it can sweep the workflows
  today; it has no `.py` mapping, so `.github/scripts` needs one first. Sweep with `/comment-strip`
  BEFORE adding `.github` to the gate's roots, because the gate reds on its first run otherwise.
  Same class as the extract.yml hole: a gate that does not read its own inputs.
  *(seam: `.claude/skills/comment-strip/scripts/strip.mjs` extension map + the gate's roots at
  `ci.yml:105`)* [[project_gate_must_cover_its_own_inputs]]
- **[GH-PAGES-HISTORY-IS-UNBOUNDED]** [S2] Two guards bound the gh-pages TREE: the retention cron
  deletes what no published page reaches, and `check-gh-pages-payload-size.py` reds over 400 MB.
  Nothing bounds the gh-pages HISTORY. Every deleted proof video, trace and screenshot stays in the
  branch's objects, and the cron's own delete commit adds one more. Measured: at least 0.87 GiB in
  1518 blobs that no other branch reaches, on a SHALLOW clone whose `gh-pages` is grafted, so the
  real branch is larger. This cost GitHub storage, and it broke the `changes` job's clone once (run
  32286548088) until `filter: blob:none` removed the historical content from that fetch. Root fix:
  re-write `gh-pages` as one orphan commit that holds the current published tree, on a schedule.
  That force-pushes a published branch, so it must take the existing `gh-pages-deploy` concurrency
  group and must run after the retention sweep, never beside it.
  *(seam: `gh-pages-retention.yml`, after the "Publish the pruned site" step)*
- **[THEME-GUARD-MISSES-PROTOCOL-RELATIVE-URLS]** [S2] `check-theme-resources-are-all-self-hosted.sh:10`
  catches an external host only inside `url(` or `@import`. Reproduced: an `.ftl` carrying
  `<link href="//fonts.googleapis.com/css2?family=Roboto">` and `<script src="//cdn…">` passes with
  rc=0. The exact regression J-19 T-23 fixed returns through that form; the selftest plants only the
  CSS `@import` case. *(seam: that script's pattern + its selftest fixtures)*

## Pending (filed by /do-plan J-19 carve, 2026-08-16 — main-branch reds)

**MAIN-1 to MAIN-4 are folded into J-19** (see `J-19-password-recovery-email-confirmation.md`
§"Main-branch reds"). The entries below are the parts that outlive that journey.

- **[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]** [S1] A club-less registrant cannot complete the
  migrate handshake, so the funnel J-16 shipped ends in an error state. The chain, proven by the
  real-idp run on the J-19 branch: the landing migrate CTA opens `/signup?intent=migrate`
  (`alpenflight/web/src/app/features/landing/landing.component.ts:76`). `postSignupLandingPath` sends
  the registrant to `/migrate/start`
  (`alpenflight/web/src/app/features/signup/signup-intent.ts:14`). The page mounts and the store calls
  `GET /api/v1/migrations/handshake/current`
  (`alpenflight/web/src/app/features/migrate-handshake/migrate-handshake.store.ts:55`). That 404 is by
  design for a first-time registrant (`MigrationHandshakeController.java:52`). The store then sends
  `POST /api/v1/migrations/handshake` (`migrate-handshake.store.ts:59`).
  `MigrationHandshakeService.issue` calls `userLookup.resolveUserId(jwt)`
  (`MigrationHandshakeService.java:61`). That field binds to `PreTenantUserLookup.resolveUserId`
  (`PreTenantUserLookup.java:27`), which reads `t_user` by `keycloak_sub` and finds no row. The service
  throws `UnknownPrincipalException`, and the handler answers 403
  (`MigrationHandshakeExceptionHandler.java:27`).

  The service states the expectation itself: **"No t_user row for principal — verified-email signup
  expected"** (`MigrationHandshakeService.java:63`). The row never exists.
  `JitUserMaterializerImpl.materialize` returns empty when the JWT carries no `clubId` claim
  (`JitUserMaterializerImpl.java:60-63`), and a new registrant has no club. The page still renders and
  shows its error state (`migrate-handshake.page.ts:54`). J-19 narrowed AC-6 to the render, and
  declared the 403 in `register.spec.ts` as a known product defect. The operator decided on 2026-08-16
  that J-19 files the defect and does not fix the backend. **J-21 owns this surface**
  (migrate-from-legacy upload wizard, `_ORDER.md:23`), so the fix rides J-21.
  *(seam: `PreTenantUserLookup.resolveUserId` + `JitUserMaterializerImpl` + `MigrationHandshakeService`
  + the verified-email signup path)*
- **[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]** [S2] J-19 T-21 widened the quote styles, the verbs
  and the call span of `absolute-flight-date-in-api-seed-guard.mjs`, but the guarded field list is still
  the three flight fields T-03 chose. A T-21 scan over every file that holds a seeding call site found
  absolute dates on **other** date fields that the same 90-day-window class of hazard can reach:
  `reservations-migration-parity.spec.ts:305-381` seeds `start` / `end` at `2026-09-02`,
  `deliveries-write-parity.spec.ts:519` seeds `deliveryDateTime` at `2026-06-01`,
  `aircraft-migration-parity.spec.ts:312` seeds `atDateTime` at `2026-01-01`, and
  `e2e/tests/email/notifications.spec.ts:48` seeds `SelectedDay` at `2026-06-15`. Each needs the same
  question T-01 answered for `flightDate`: does a server-side default window reach this date on a future
  run date? Answer it per field, then either derive the date or add the field to `GUARDED_DATE_FIELDS`.
  *(seam: `GUARDED_DATE_FIELDS` + those four specs)*
- **[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]** [S2] `alpenflight/web/tsconfig.json` sets `files: []`, and
  neither `tsconfig.app.json` nor `tsconfig.spec.json` includes `scripts/**`. So every file under
  `alpenflight/web/scripts/` — including the two CI guards J-19 added and their own specs — is
  typechecked by no CI job. J-19 T-17 typechecked the guard spec by hand to ship. This is the same
  "a gate must cover its own inputs" class as the guard gap it was found next to, one level up: the
  guards that protect the suite are themselves unguarded. *(seam: a tsconfig that includes
  `alpenflight/web/scripts/**` + a CI step that runs it)*
  [[project_gate_must_cover_its_own_inputs]]
- **[CHECK-THEME-LOAD-IS-ROTTEN-AND-UNWIRED]** [S2] `alpenflight/auth/scripts/check-theme-load.sh` cannot
  pass: it built a raw authorize URL with no PKCE parameters (the same fault J-19 T-12 found in
  `login.spec.ts`, so Keycloak 302s away before any theme renders) and it matches a root-element
  pattern that the `keycloak.v2` track never emits. T-12 fixed both faults. The remaining defect is
  that **the script is wired to no CI job at all**, so nothing would have caught either. A theme
  verification that runs nowhere is not a gate. Wire it, or delete it and say the theme is unguarded.
  *(seam: `check-theme-load.sh` + a CI job that runs it)*
  [[feedback_safety_claim_needs_negative_test]]
- **[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]** [S2] No spec registers through bare `/signup` and lands on
  `/join`, although `resolveSignupIntent(null)` makes `join` the DEFAULT intent
  (`signup-intent.ts:3,6`). Existing coverage misses it: the unit spec covers the resolver
  (`signup-intent.spec.ts:19,28`), the mock e2e reaches the `/join` stamp through `intent=demo`
  (`e2e/tests/public/signup.spec.ts:82`), the landing spec asserts only the CTA href
  (`landing.spec.ts:136`), and the real-idp join lifecycle never enters `/signup` — it creates the
  user through the Keycloak admin API (`join-request.spec.ts:57-62`). J-19 found this while fixing a
  stale assertion that had named the pre-J-12a landing path. Not J-19's surface (password recovery),
  so it rides the next journey over the signup funnel. *(seam: a real-idp spec for bare `/signup`)*

## Pending (filed by /do-ship J-31 T-14, 2026-08-15)

- **[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]** [S2] `e2e/tsconfig.json:5` sets `moduleResolution: node10`, which
  TypeScript 6 rejects as deprecated (TS5107), so `npx tsc -p e2e/tsconfig.json` cannot run at all on the
  top-level suite. Pre-existing and unrelated to the sweep, but it means that suite has **no typecheck gate**
  — alongside the finding that `angular.json`'s `lintFilePatterns` is `src/**` only, so `e2e/` has no lint gate
  either. J-31's `--check` is currently the only automated guard covering that directory.
  *(seam: `e2e/tsconfig.json` + an `e2e` lint/typecheck lane)*
## Pending (filed by /do-ship J-31 T-12, 2026-08-15)

- **[SCHEMA-DECISIONS-NOTE]** [S3] T-12 stripped 1,946 comments from the 58 applied Flyway migrations. Unlike every
  other batch this one had **no rescue move**: an applied index/constraint cannot be renamed to carry its reason
  (that needs `ALTER … RENAME TO` in a NEW migration — a schema change), and an applied migration's DDL must not
  be edited. So the rationale below now lives only in git. **111 `COMMENT ON` DDL statements survive** (string
  literals, untouchable by the stripper), so the column-level contracts they carry — e.g. the aircraft
  ownership-exclusivity XOR on `t_aircraft.aircraft_owner_person_id` — are still in the DB catalog; everything
  below is what was in `--` prose only. Proposal: one `docs/modernization/schema-decisions.md`, sourced from the
  106 above-threshold entries reviewed at T-12, covering:
  - **Partial-UNIQUE predicates and what each one's identity claim is.** `ux_location_legacy_guid_club` (V23:32) /
    `ux_iop_legacy_guid_location` (V24:34) — one fan-out replica per (shared legacy row, club / parent replica);
    re-ingest UPSERTs onto this index instead of colliding on the `id` PK (the J-0 23505); predicate excludes both
    soft-deleted AND non-migrated (`legacy_guid IS NULL`) rows. `ux_deployment_owner_active` (V14:34) — sandbox +
    deleting deliberately excluded (shared-fixed singleton; mid-cascade users may legitimately re-ingest).
    `ux_migration_run_upload_active` (V20:31) and `ux_join_request_alive` (V49:31) — the uppercase state literal in
    the predicate is load-bearing (`@Enumerated(STRING)` writes `name()`). `ux_pftc_transaction_current` (V46:58) —
    structural backstop for the mapper's keep-first dedupe. V11 flight-type name + V7 location ICAO — active-only so
    soft-delete-then-recreate-same-name works; V7 also retired the S-049 "null out icao_code on soft-delete"
    workaround that had traded audit fidelity for it.
  - **Tenant-FK constraint NAMES are a guard contract.** `LeakageSweepIT` reconstructs `fk_<t_-stripped-table>_<tenant-col>`
    and pins its fail-closed write to that exact string. V32/V33/V41/V43/V44 exist solely to realign ad-hoc V4 names.
    Renaming a tenant FK off that shape silently disarms the leakage guard for that aggregate — nothing states this now.
  - **Deliberately absent CHECK constraints, with the Java owner that replaced each.** V3: 14 flight CHECKs →
    Flight aggregate / TimeWindow / FlightDate / RunwayCode / CouponNumber VOs; aircraft + location + counter CHECKs →
    their VOs. V4:239 `ck_arv_end_after_start` → `AircraftReservation.validateDuration()` **because the degenerate
    lower==upper case yields an empty `tstzrange` that GiST silently misses** — the DB cannot catch it. V4: `EXCLUDE
    USING gist` deliberately NOT applied (maintenance-vs-flight / multi-pilot / charter overlaps are legitimate).
    V4:493 `delivery_item.total_amount` GENERATED removed, and the named future answer is a denormalised
    `delivery.total_amount_chf` written by `Delivery.book()`, never a re-introduced GENERATED column. V13: air state
    is computed, never stored (sacred cow). V38: read-model rows written by `FlightReportProjector` via `@DomainEvents`,
    never triggers.
  - **Columns nullable on purpose, and what NOT NULL would break.** V23/V24 `legacy_guid` (clean-seed + API rows have
    no legacy origin). V25 `counter_unit_type.legacy_int_id` — LANDINGS/STARTS are AlpenFlight-canonical with no legacy
    equivalent and fabricating a key would be a **false entry in the reversible id-map**; the UNIQUE still works because
    Postgres treats NULLs as distinct. V15 `idempotency_key` UNIQUE-but-nullable (multiple NULLs allowed → no synthetic
    backfill needed). V50 `club.logo_url` and V48 `join_code`'s DEFAULT — both shaped so the cutover CLUB reconcile
    (`INSERT … ON CONFLICT (id) DO UPDATE`, `EntityStreamIngestor#buildInsertStatement`) leaves the provisioning-owned
    column intact because the candidate tuple omits it. V53 `delivery_item.article_id` (a free-typed legacy
    ArticleNumber must be KEPT with NULL, not 23503 the whole bundle). V21, V57, V9 `tenant_club_id`.
  - **Backfill ordering + guards.** V7 add-nullable → backfill → SET NOT NULL → FK, with the backfill *defensive only*
    (V3 seeds no locations, so a fresh DB hits zero rows). V10's owner→managing fallback to seed-club-1. V14's
    `deployment_id` DEFAULT catches direct-JDBC test fixtures predating the column. **V54:29** — the whole app-role
    split is guarded on the migrator holding CREATEROLE and NO-OPs with a NOTICE on this LAN dev cluster; roles are
    cluster-global while Flyway history is per-DB, so every step must stay create-if-not-exists.
  - **Index-shape debt — ALREADY COVERED, do not re-document.** V4:254 `ix_arv_location` carried a literal
    `covers tombstones: deferred-perf-tuning S-108` marker. `alpenflight/server/CONVENTIONS.md:266-279` already
    names all three tombstone-coverage reasons, points at each index by search string, and states outright that
    renaming an applied index needs a new `ALTER INDEX … RENAME TO`. The three applied indexes still carry their
    pre-convention names; the debt is the rename migration, not a missing note.
  - **Immutability + forensic anchors.** "Never amend a shipped migration — ship V<n+1>" (V1/V2/V3/V4 headers) — the
    exact rule this journey just paid for with a `flyway repair`. Canonical UUIDv7 seed literals: generator
    `server/src/test/resources/scripts/GenerateCanonicalUuids.java`, ground truth `reference-seeds-canonical-uuids.json`,
    "DO NOT regenerate after ship" (V42 continues the family at offset 18_000). `legacy_int_id … UNIQUE` retained
    forever for the `ReferenceLookupResolver` point lookup (V22/V25 backfill the tables V2 missed). V18's forensic
    triple, and its `legacy_orphan_actor_id` with **intentionally no FK to `t_user`** (ADR 0007 forbids the shadow).
  - **Hard do-not-add fences.** V2's AUTH-ARTIFACTS block — never add `password_hash` / `refresh_token` / `mfa_secret`
    / `lockout_enabled` / `email_confirmed` to `t_user`; Keycloak owns them, and a local `role`/`user_role` table would
    be parallel truth. That block was a PR-review tripwire and is now gone. V3: `flight_aircraft_type_id` is SMALLINT
    (1,2,4; 3 skipped) and deliberately not an FK — ~70 MB saved at ~5M rows. V4: `tstzrange` not `tsrange` because the
    implicit `::timestamp` cast is session-TZ-dependent hence NOT IMMUTABLE and Postgres rejects it in a generation
    expression; `reservation_range` is GENERATED and must never be inserted.
  - **Dev-seed-in-prod posture** (V31/V34/V36 headers, the most-repeated block in the batch). V5 inserts seed-club-1
    **unconditionally, no profile guard**, and Flyway runs one `classpath:db/migration` location across every profile —
    so every dev seed lands in production too. It is inert there **only** because reads are `@TenantId`-filtered and
    real tenants get distinct UUIDs; the guarantee is NOT a Flyway location fence and NOT "seed-club-1 is absent in
    prod". Accepted cross-cutting debt with no other written home. Same block carries the **seed-band rule**: controller
    ITs must scope pre-clean to `id::text NOT LIKE '019e30c3-%'` or they wipe the seeds (J-5 T-34 wiped V31), plus the
    reasons behind V36's VALID process_state (NotProcessed leaked into the J-3 pending tile, 4→5), V34's LSPL-not-LSZW
    ICAO, "HB-SEED", and V39/V45's relative-date pinning.
  - **Tenancy classifications that no code states.** V46 PersonFlightTimeCredit is INDIRECTLY tenant-scoped (no
    `club_id`; the repo joins the owning Person's PersonClub). V16 `t_migration_upload` is pre-tenant, so the
    `@TenantId`-driven S-024 sweep does not touch it. V55/V56 + `t_deployment` / `t_migration_run` are platform tables
    with deliberately no tenant column. V3/V4: Aircraft cross-tenant (2026-05-16 amendment), Flight tenant-scoped by
    per-flight `operating_club_id` **not** denormalised from aircraft (charter case).
  - **Security posture.** V54's INSERT,SELECT-only app-role grant on `t_mutation_audit_event` (and V9:76 flagging it as
    the security-reviewed exception to the GRANT/REVOKE ban); V16's Tink AEAD with `uploadId` bound as `associatedData`;
    V4's 9 frozen `recipient_*` columns per Swiss OR Art. 957a with DSAR exemption at `process_state_id >= 20`.
  - **Supersession chains** (why V52 undid V4+V19's delivery-number model, why V53 dropped the never-wired counter, why
    V47 dropped V2's email-template table, why V43's `ALTER TYPE` was safe, why V58 is DATE not TIMESTAMPTZ, why V56
    ships ShedLock's table with `@EnableSchedulerLock` off). Recoverable from git, expensive to reconstruct.
  *(seam: one new `docs/modernization/schema-decisions.md`; source = `.comment-strip/manifest/migrations-sql.jsonl`,
  106 entries at score ≥ 8, all reviewed at T-12)*

## Pending (filed by /do-ship J-31 T-11, 2026-08-15)

- **[PERSONS-DETAIL-ROUTE-MAY-BE-SHADOWED]** [S2] `forms/validation-hardening.spec.ts:150-154` registers a persons
  **detail** route first and a broad `**/api/v1/persons**` list glob after. Playwright is last-registered-wins
  and the broad glob also matches `/api/v1/persons/{id}`, so the list array may be serving the detail GET. The
  deleted comment asserted the opposite. Needs a human read — if it is shadowed, the spec is passing against the
  wrong fixture. *(seam: that spec's route registration order)*

## Pending (filed by /do-ship J-31 T-10, 2026-08-15)

- **[PACKAGE-INFO-DOMAIN-VOCABULARY-LOST]** [S3] The `package-info.java` files are now bare `@NullMarked` /
  `@ApplicationModule` declarations. Layering stays ArchUnit-enforced and tenancy stays structural, but the
  **domain vocabulary** went with them — notably the aircraft three-axis ownership model
  (`managing_club_id` vs `owner_club_id` vs `aircraft_owner_person_id`) and the S-058 reversion of S-159 for
  the charter case. That belongs in `docs/modernization/`, which is where this policy says rationale lives —
  it just never got written there. *(seam: a short domain-vocabulary note under `docs/modernization/`)*
- **[DEAD-ACTOR-RESOLVER-EVICT]** [S3] `audit/application/ActorResolver.java:50` `evict(String sub)` has **zero
  callers** in `src/main`, `src/test` or e2e; the deleted comment claimed "Called by the user-deactivation
  flow". Wire it into user deactivation or delete it. *(seam: `ActorResolver` + the deactivation flow)*

- **[REQUEST-ID-NEVER-LOGGED]** [S2] `RequestIdFilter` puts MDC key **`requestId`**; `logback-spring.xml:11` renders
  **`%X{request_id:-}`**. They do not match, so the reserved request-id placeholder in every log line has
  **always been empty** — request tracing has never worked. The comment the sweep deleted asserted the two
  matched, which is presumably why nobody checked. One-character-class fix, but it changes log output, so it
  did not ride a comment sweep. *(seam: `RequestIdFilter` MDC key ↔ `logback-spring.xml`)*
- **[SERVER-MAIN-SWEEP-NITS]** [S3] Four pre-existing nits the strip exposed, none touched (all would change
  behaviour or a CI-verified artifact): delivery eligibility (`LOCKED` + billable type + `created_on <= today-3d`)
  lives in `DeliveryCreationService.eligibleFlights` rather than on an aggregate, in tension with ADR 0022 §2;
  `DeliveriesController`'s `@Tag(description = "Read-only delivery … viewer")` is stale — it also owns
  create/book/delete, and it feeds the OpenAPI snapshot; `PiiRedactor.MAX_SERIALIZED_BYTES` is compared against
  `json.length()` (chars, not bytes); `FlightReportRepository.ReportCriteria.tenantId` is populated and never
  read (tenant scoping is structural via `@TenantId`) — the deleted comment was the only thing explaining why
  that component looks unused. *(seam: those four files)*

## Pending (filed by /do-ship J-31 T-09, 2026-08-15)

- **[VACUOUS-NARROWING-ASSERTIONS]** [S2] Two tests were found asserting less than their names claimed, each held up
  only by a comment the sweep deleted. `FlightsControllerIT.list_default_window_returns_recent_flights_only`
  seeds **no old-dated row**, so "recent only" was never proven — renamed to
  `list_without_explicit_window_includes_a_flight_dated_today` rather than gerrymandering the seed mid-sweep.
  `MePersonControllerIT` carried a comment claiming the Person aggregate lower-cases email, but the PATCH input
  is **already lower-case**, so nothing proves it; an `.as(…)` was deliberately NOT added because it would
  assert a claim the test does not make. Both need an adversarial row / mixed-case payload to become real
  ([[feedback_adversarial_seed_for_narrowing_assertions]]). *(seam: those two ITs' seeds)*
- **[TENANT-ISOLATION-IT-PREFIX-COLLISION]** [S2] `FlightsTenantIsolationIT` and `FlightTypesTenantIsolationIT` share
  the same club-name/club-key prefixes (`IT_FTI_` / `IT_FT`) — the ADR 0021 rule-1 collision that a deleted
  `LocationsAuthorizationIT` comment existed to warn about. Pre-existing, not caused by the sweep; single-schema
  external-PG runs are where it bites. Give each class its own ids on next touch. *(seam: those two ITs' club ids)*

## Pending (filed by /do-ship J-31 T-08c, 2026-08-14)

- **[TAILWIND-LAYER-VS-NGZORRO-ADR]** [S3] The sweep deleted the only written explanation of why `!text-white`
  is needed at `reservations-calendar.page.ts:148-156` — Tailwind's layered utilities lose to ng-zorro's
  **unlayered** reset, a bug the operator reported and which was fixed **twice**. The rule now survives only
  as an undocumented idiom across **13 call sites**; ADR 0024 §11 does not state it. Rationale belongs in
  `docs/modernization/`, not in code, so the durable home is a one-line addition to **ADR 0024 §11** — but
  `/do-ship` does not auto-edit ADRs, so this needs the operator. Left undocumented, it gets fixed a third
  time. *(seam: ADR 0024 §11 + the 13 `!text-white` call sites)*

## Pending (filed by /do-ship J-31 T-08, 2026-08-14)

- **[PROD-DENSITY-ATTR-MISSING]** [S3] `alpenflight/web/src/index.prod.html` never sets `data-density`, so the ~15
  `body[data-density='comfortable']` rules in `styles.css` are **inert in production** while they apply in dev —
  the shipped app is denser than the one anyone reviews. Found because the comment describing the density
  system outlived the attribute it described. *(seam: `index.prod.html` + the density rules in `styles.css`)*
- **[DEAD-VIRTUAL-SCROLL-INPUT]** [S3] `af-data-table.component.ts:74` exposes a `virtualScroll` input with **zero
  consumers** — either wire it or delete it. *(seam: that component's public inputs)*

## Pending (filed by /do-ship J-31 T-07, 2026-08-14)

- **[MIGRATION-BUNDLE-DEAD-EDGES]** [S3] Three nits the strip exposed, each pre-existing: `UserMapper.java:19`
  `LEGACY_SYSTEM_USER_ID` is `public` with **zero references** (duplicate of the bindings GUID);
  `migration-bundle/build.gradle.kts:51` carried an archunit-1.4.2-vs-Java-25 workaround whose rationale died
  with its comment — the guard belongs in `ArchitectureTest` where it can fail loudly; and
  `accounting/package-info.java` is now an **empty file** with no `@NullMarked`, unlike its sibling
  `identity/package-info.java` — a nullness-annotation gap the comment was hiding. *(seam: those three files)*

## Pending (filed by /do-ship J-17 gate, 2026-08-06)

## Pending (filed by /do-ship J-17 T-17, 2026-08-03)

- **[FORM-FIRST-PAINT-RED]** [S3] `liveFieldErrors` (`shared/util/form/inline-validation.ts`) reports from first paint, so a
  blank form opens **fully red** before the user has typed anything. T-17 hit this on the public registration form and
  gated each message on `events` (touched/dirty) **locally in `registrant-fieldset.component.ts`**. The util is consumed by
  **8 other screens**, so every blank *create* form in the app plausibly opens showing all its validation errors. Fix it in
  the util (opt-in for the edit-form case if any screen genuinely wants eager reporting) rather than repeating the local
  gate per form. Check the shipped create screens before assuming it's cosmetic. *(seam: `inline-validation.ts` + its 8 consumers)*
- **[FIELDSET-LEGEND-SIZE]** [S3] `text-sm` loses against the UA stylesheet on `<legend>`, so fieldset legends render ~20px
  instead of the intended size (both the T-16 "Contact" and T-17 "Pick a day" legends). Cosmetic and consistent.
  *(seam: legend styling in the public form components)*

## Pending (filed by /do-ship J-17 T-15c, 2026-08-02)

- **[MOCK-CLUB-ID-SHAPE]** [S2] `MOCK_CLUB_ID` in the web mock fixtures is a raw UUID while real club ids are
  `clb-<uuid>`. A dishonest inner-loop fixture in the sense of [[feedback_honest_inner_loop_fixtures]]: mocked
  specs pass against an id shape the backend never emits, so an id-shape bug goes green locally and reds at the
  gate. T-15c left it alone because the ripple is broad (many specs share the constant). This is the same class
  as the club-key-vs-UUID confusion that made `GET /clubs/{id}` 403 for every club admin — worth fixing before
  it hides a third one. *(seam: web e2e mock fixtures' club-id constant)*

## Pending (filed by /do-ship J-17 T-07b, 2026-08-02)

- **[AUDIT-ACTOR-KIND]** [S3] `AuditActorKind.SYSTEM` has no writer anywhere in the repo: the listener leaves every
  runtime row at `NORMAL` and the cutover importer is the only thing that writes `LEGACY_MIGRATED`, so
  `actor_kind` is in practice a native-vs-imported marker while the `system_actor` boolean does the actual
  actor classification. T-07b confirmed `/system/logs` renders `system_actor` (`AuditEventDtos.AuditEventRow`
  carries no `actorKind`) and pinned that in `AnonymousActorProjectionIT`, so nothing is user-visibly broken —
  but the enum keeps a dead constant. Decide one way: **delete `SYSTEM`** (needs a migration to refresh the
  `COMMENT ON COLUMN t_mutation_audit_event.actor_kind` V18 planted, which still enumerates it — the V18 file
  itself is applied and must not be edited), **or** promote `actor_kind` to the single classifier and retire
  `system_actor`, carrying it through the projection + the viewer's actor cell in the same change.
  `AnonymousActorProjectionIT.actor_kind_does_not_separate_the_two_rows` goes red either way and is the
  intended tripwire. *(seam: `AuditActorKind` + `AuditEventDtos.AuditEventRow` + `audit-logs-list.page.ts`)*

## Pending (filed by /do-ship J-30 gate, 2026-07-22)

- **[LEGACY-J2-READINESS]** [S2] `e2e/tests/flights/flights-parity-J2.spec.ts` is `@quarantine-legacy`'d (grep-inverted
  from the nightly) — the heaviest legacy parity spec (list → flight-edit → tow-form → motor) is irreducibly flaky
  on the Mono/AngularJS reference stack under CI load: the HB-3407 row render + `flightDetails.StartType` bind never
  arrive reliably, exhausting `retries:3` even after three rounds of step-wait hardening (T-15/T-16/T-18). All 12
  GENUINE legacy reds were fixed; this is the residual. Un-quarantine via a dedicated legacy render-readiness pass,
  or re-home that parity coverage on the AlpenFlight side. *(seam: flights-parity-J2 + the legacy flight-edit form load timing)*
- **[LAN-PG-SEED-DRIFT — dev-box only]** [S3] On the shared LAN Postgres, V48's `UPDATE … SET join_code='SEEDCLUB'` hit
  0 rows for the seed club (`019e30c3-…-001` carries `join_code=L8PDJDXF`) → the default join-code path is broken
  LOCALLY (T-11 verified via a throwaway env override, reverted). CI's fresh migrate is unaffected. Re-seed the dev
  DB or make V48 idempotent-by-id. *(seam: dev LAN-PG state / V48)*
## Pending (filed by /do-retro J-12a window, 2026-06-24)

- **[PER-JOURNEY-DOC]** [S3] (standing rider — activates once the doc-gen documentation journey ships) Each feature journey contributes its user-manual page + architecture-diagram delta to the generated docs site as a gate rider, so the manual/diagrams stay current as a byproduct of shipping (operator 2026-06-24). *(seam: the doc-gen site generator + per-journey doc delta)*

## Pending (filed by /do-ship J-27 gate, 2026-06-20)

- **[SUITE-ISOLATION — operator principle 2026-06-19].** [S2] Non-migration parity specs should set up their own data;
  migration specs run FIRST and rely ONLY on legacy seed data (assert what it genuinely produces, never gerrymander
  the seed). J-27 applied this to `:577`; the broader suite restructure (audit the other parity specs for hand-crafted
  `_test-fixture.sql` dependencies) rides a future test-architecture slot. *(seam: e2e/tests/real-idp parity specs + `_test-fixture.sql` §4/§5 hand-crafted rows)*

## Pending (filed by /do-retro 2026-06-14, J-7/J-26/J-8 window — operator debt-burndown)

- **[WORKFLOW-SLIM] Extract the repeated per-journey YAML blocks into composite actions (`.github/actions/`)** [S3]
  to cut the workflow YAML (~4.5k→~2k) — the only still-pending half (the mock-suite sharding, real-idp shard,
  and KC-26 quarantine all shipped). *(seam: `ci.yml` + `alpenflight-proof-fanout.yml` + `alpenflight-e2e.yml` +
  new composites)*
- **[QODANA-BUILD-FILE-BLIND-SPOT]** [S3] Filed by J-19 T-22's audit of every path-filtered workflow.
  `qodana.yml:29-33` filters to `alpenflight/server/src/main/java/**` + the three qodana files, which
  matches `qodana.yaml`'s own `include.paths` exactly — so the inspected sources are covered. The gap is
  one level out: Qodana resolves the inspection classpath from `alpenflight/server/build.gradle.kts` and
  `settings.gradle.kts`, and neither is in the filter. A build-file change that breaks resolution reports
  no finding until the next Java-source push. Add the two build files to the filter.
  *(seam: `.github/workflows/qodana.yml`)* [[project_gate_must_cover_its_own_inputs]]

## Pending (filed by /do-ship 2026-06-13, J-26 gate)

- **[MAINTAINABILITY-TOOLING — Qodana baseline backfill].** [S3] Qodana shipped report-only (J-8 T-15), but the
  committed `qodana.sarif.json` is a PLACEHOLDER empty baseline (the local Docker run OOM-killed on the LXC box);
  the first CI `qodana-scan` run establishes the real baseline → download its `qodana-sarif-<run_id>` artifact +
  commit it over the placeholder. *(rides the next journey that touches CI / a maintainability slot)*
  [[reference_fallow_maintainability_analyzer]]

## Pending (filed by form-validation parity audit 2026-06-09)

Full analysis + per-form verified gaps: `docs/modernization/form-validation-parity-audit.md`
(ultracode sweep — 12 forms, legacy-oracle → parity-review → gap-hunter verify). Operator bar:
legacy = minimum; **all** validations as-you-type (debounced ~200ms); server-on-submit stays the
safety step. **Each rider rides the next touch of its form.**

**P4 — server-roundtrip as-you-type pre-checks (submit-time 409 already CONFIRMED safe — UX only):**
- [S3] Add a non-mutating `…/validate` endpoint + debounced store rxMethod (model on reservation overlap
  `AircraftReservationsService.java:229-244`) + merge via `asyncErrors$`/`mergeFieldErrors`
  (`inline-validation.ts:56,67`) for: aircraft immatriculation, article articleNumber, location ICAO,
  user username. *(seam: per-aggregate /validate endpoint + store)*

**P5 — declined better-than-legacy / cosmetic (low):**
- [S3] Planning-setup: client `start ≤ end` + `≥1 weekday` cross-field validators + error region
  (`planning-setup.page.ts:170-191,242-254`); planning info `maxLength(4000)` client-side
  (`planning-edit.page.ts:376`). DIVE→400 handlers for reservation/planning FK→500 (phantom
  type/location/person ids — parity-met, lowest). *(seam: planning-setup validators + reservation/planning
  DIVE handlers)*

## Pending (filed by /do-retro 2026-06-07, J-6 window)

## Pending (filed by /do-retro 2026-06-06, J-5 window)

- **CI fail-aggregate (surface ALL reds in one run).** [S3] ci.yml stops at the first failing layer (build → server-test → web-lint → mock-e2e discovered serially across cycles). Run the independent checks as parallel jobs that all report, so one run shows every red at once. *(seam: ci.yml job parallelism/aggregation)*

## Pending (filed by /do-plan 2026-06-06, J-5 carve — maintainability tooling)

**Maintainability = complexity + duplication + dead code** (operator, 2026-06-06 —
[[feedback_maintainability_includes_dupes_and_deadcode]]): run fallow's **full default**
(`dead-code` + `dupes` + `health`), not just `health`, and report/track all three.

- **Refactor the genuine complexity hotspots — each rides the journey that TOUCHES it (operator:
  riders only, no ad-hoc project-code change).** [S3] STILL PENDING (their own next-touch journey):
  `flights/list/flights-list.page.ts` (24cyc, 315 LOC, untouched), and the flights `store.errorPatch`
  (deliberately unconverted — it's a 412/409 optimistic-lock state machine, not a kind-table). *(seam:
  `flights-list.page.ts` + the flights store `errorPatch`)* [[reference_fallow_maintainability_analyzer]]

## Pending (filed by /do-ship 2026-06-05, J-4 window)

- **Legacy `/profile` walkthrough video doesn't stage in the fanout `legacy-parity` gallery (J-4 done-bar
  loose end).** [S3] The legacy parity spec `e2e/tests/profile/profile-parity-J4.spec.ts` now PASSES (accordion-
  expand fix) + the 8 paired screenshots render, but the staging `find /tmp/fls-e2e-results -path
  '*profile-parity-J4*' -name '*.webm'` finds no video → `profile-parity-J4.webm` not declared. The J-0c/J-1/J-2
  legacy specs DO stage videos on pass, so it's a per-`profile`-project video-retention/output-dir quirk, not
  pass-vs-fail. Done-bar was met by the paired screenshots ("judgeable side-by-side"); add the video on the next
  fanout-touching task. *(seam: top-level e2e `profile` project video config / the fanout video-find path)*
  **CAUSE FOUND — J-19 T-14, by probe, not by reading.** The fault is spec-scoped, not project-scoped:
  `test.use({ video: 'on' })` is INERT for a context the spec creates itself, and
  `profile-parity-J4.spec.ts:29` makes its own context through `fixtures.ts:107`. So Playwright records
  nothing to stage. The fix is to pass `recordVideo` explicitly at the `newContext` call, which is what
  J-19's `lostpassword-parity-J19.spec.ts` does. Any other legacy spec that builds its own context carries
  the same silent hole — sweep for `newContext` without `recordVideo` when this ships.

## Pending (filed by /do-ship 2026-06-04, J-2 window)

- **e2e tsc-strictness** [S3] — `tsc -p alpenflight/web/e2e/tsconfig.json` reports ~23 pre-existing
  `exactOptionalPropertyTypes`/`maxFailures` errors (`playwright.config.ts`, `flights-list.spec.ts`,
  `aircraft-crud.spec.ts`, `persons-add-modal.spec.ts`, `proof-gallery.spec.ts`, `migration/handshake.spec.ts`).
  Playwright's esbuild transpile tolerates them; harmless until/unless an e2e `tsc` gate is wired.
  *(seam: e2e/tsconfig strict-mode cleanup)*
- **e2e prettier-glob not clean** [S3] — `prettier --check 'alpenflight/web/e2e/**/*.{ts,json}'` flags ~42
  pre-existing unformatted specs (repo-wide, predates J-2). A format-normalization pass; don't fold
  into a feature PR. *(seam: e2e prettier normalization)*
- **op-field-mutate test coverage (gap-hunter nit, T-21)** [S2] — `FlightCrew.updateOperationalFields` (the
  kept-row in-place reconcile) is only exercised by a re-assert with *identical* values; a changed
  `nrOfLdgs`/time on an unchanged-identity crew row isn't asserted. Code is correct; add the assertion
  on the next flights touch. *(seam: FlightDomainTest / FlightsControllerIT crew-op-field case)*
- **orphaned clubadmin4 realm-user + V29 seed** [S3] — T-24 added `clubadmin4` (realm-export user +
  `V29__dev_user_seed_clubadmin4.sql`) as a motor-test principal; T-36 unified motor into /flights and
  the motor test reverted to `fixture.clubA`, leaving clubadmin4 + V29 self-referenced only. Inert
  (realm user + a `t_user` row); a clean removal needs care (removing a landed Flyway migration mid-line
  risks a checksum surprise). Remove on a later journey. *(seam: realm-export.json clubadmin4 + V29)*
- **JIT-username robustness (observation, T-22/T-23)** [S2] — `JitUserMaterializerImpl` reconcile-by-username
  (T-23) handles the concurrent-sub-race; the residual is that a genuinely distinct sub reusing a live
  username is rebound rather than rejected — defensible (username = person identity) but worth a
  `legacy-oracle`/security look if multi-IdP lands. *(seam: JitUserMaterializerImpl)*

## Pending (filed by /do-ship 2026-06-05, J-3 window)

- **orval positional `getN` method naming is fragile across regenerations** [S2] — the generated TS client
  names methods positionally (`get2`, `get3`, …); adding an endpoint (J-3 T-10 `/me/system-dashboard`)
  renumbered them, silently re-pointing T-09's `ClubDashboardStore.get2()` at the wrong endpoint (caught
  + fixed in T-11, but only because the next consumer broke the typecheck). Make the binding stable:
  set explicit `operationId`s on the `me`-dashboard endpoints (and ideally project-wide) so orval emits
  named methods, not positional `getN`. The new accounting endpoints (J-8 T-06/T-07) already carry explicit
  `@Operation(operationId=…)`; the **project-wide** pass over the remaining legacy `getN` endpoints is STILL
  PENDING — do it in isolation on a future web journey, not bolted onto a feature journey. *(seam: backend
  operationId annotations + orval config + the `meService.getN()` call sites)*

## Pending (filed by /do-ship 2026-06-09, J-7 gate)

- **Planning `:410` edit-crew cold-`page.goto` reopen flakes on OIDC reboot/renew stall (J-7 T-21).** [S3] The
  reopen via `page.goto('/planning/{id}/edit')` hits the documented cold lazy-chunk/OIDC-renew stall
  ([[project_real_idp_goto_reboot_renew_stall]]); self-heals warm + CI `retries:1`. Switch that reopen to warm
  in-app nav to harden. *(seam: planning-migration-parity :410 reopen → warm nav)*

## Pending (filed by PR #215 review, 2026-06-10 — ADR 0027 JPA-first / no-JDBC)

- **Retire the remaining main-code JDBC/native sites per-module on next touch (ADR 0027 §1).** [S3] STILL PENDING
  convert-on-touch: `JpaUserRepository` (remaining native), `JpaPersonRepository` (cross-tenant check),
  `AircraftReservationConflictProbeImpl` (KEEP-GiST recorded T-17), `ShowcaseSeeder`. Structurally-pre-tenant
  seams stay register-listed (`UserPrincipalLookup`, `PreTenantUserLookup`, `ReferenceDataSeeder`,
  `MutationAuditEventListener` system-actor write). *(seam: per-module infra layer)*
- **IT seeding: raw-JDBC → production-code per-touch (ADR 0027 §3).** [S3] ~85 ITs (incl. `TenantScopedRowBuilders` /
  `TwoClubFixture` consumers) seed via `JdbcTemplate`; convert each file the next time it's materially edited —
  convention, NOT a sweep story. ADR 0021 isolation rules unchanged. **Same per-touch convention now also covers
  club-id collisions:** single-schema external-PG runs (RM-2a) surfaced classes sharing club UUID literals by
  value with club-HARD-DELETING classes; latent pairs left — the migration round-trip family's bundle clubs
  `…04be`/`…0bb8` are also referenced by Audit*/Clubs* ITs — give a class ITS OWN club ids when touching it;
  production-reserved ids (ShowcaseSeeder, V-seeds) are off-limits as foreign fixture clubs. *(seam: server
  src/test, per-touch)*
- **Fanout has NO reporting spec over MIGRATED data (predates the read-model conversion; found at RM-5).** [S2] The
  fanout's AlpenFlight parity step runs J-0c/J-1/J-2/J-5/J-6 migration-parity specs — `/flightreports` over the
  migrated dataset has never been e2e-asserted (the J-7 reporting specs run against the CLEAN seed in ci.yml; the
  fanout only captures the LEGACY reporting side). Server-side the seam IS covered (RM-2 ingest-rebuild ITs assert
  read-model rows + decorations post-ingest). Next reporting touch: add a small AlpenFlight-side
  `reporting-migration-parity` assertion (open /flightreports as the migrated club, summary+rows non-empty,
  location names render) to the fanout spec list. Also fix the step's stale name (it predates J-5/J-6 too).
  **J-27 T-04 confirmed (2026-06-19):** the block must be AUTHORED (no `useRealBundle()`-guarded migrated
  block exists in `flight-reports-parity.spec.ts` to merely list) and may surface its own fidelity red — so
  it rides a journey that can absorb that, not a wiring one-liner. *(seam: alpenflight-proof-fanout.yml parity-spec step + e2e/tests/real-idp)*

## Pending (filed by J-26 T-20, 2026-06-12 — IT-seeding conversion remainder, empirically measured)

- **IT seeding: per-IT raw-JDBC seeders → production code, PER-TOUCH (ADR 0027 §3).** [S3] The shared seeders are now
  done. The standing remainder is the **per-IT** seeders: of the **84** server test files touching `JdbcTemplate`
  (verified by grep, not estimated), **44 SEED (raw `INSERT`)** and **40 use JDBC purely for assertion (`SELECT`)
  / hard teardown (`DELETE`)**. The 40 assert/teardown files are NOT the §3 anti-pattern (no domain-invariant
  bypass — a hard `DELETE` that bypasses soft-delete is legitimate test infra) and should stay JDBC. The ~44 seed
  files convert **one file at a time, on its next material edit** — ADR 0027 §3's own rule is per-touch, NOT a
  sweep story (an 84-file sweep is explicitly forbidden). *(seam: `server/src/test`, per-touch)*
- **Pinned-id aggregate seeds legitimately stay JDBC (the @GeneratedValue + Hibernate-7-overwrite wall).** [S3] When an
  IT must seed an aggregate at an **externally-pinned** id (a consumer asserts on that exact id), production save
  paths cannot deliver it: aggregate roots use `@GeneratedValue(UUID)` and Hibernate 7's `UuidGenerator`
  (a `BeforeExecutionGenerator`) OVERWRITES any reflection-set id at insert — so save/persist/stateless-insert
  all mint a fresh id (the T-19 wall). Such pinned-id seeds keep their raw `INSERT` (documented inline), citing
  the `tenancy-showcase-seed-deterministic-ids` native-sql-register precedent. *(seam: `server/src/test`, per-touch)*

