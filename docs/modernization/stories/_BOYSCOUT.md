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

## Pending (filed by /do-ship J-17 T-07b, 2026-08-02)

- **[AUDIT-ACTOR-KIND]** `AuditActorKind.SYSTEM` has no writer anywhere in the repo: the listener leaves every
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
- **[AUDIT-ACTOR-CELL]** Same cell, separate nit: for an authenticated row `/system/logs` prints the raw
  `actorUserId` UUID, and prints **nothing** when the principal has no `t_user` row (a federated sub the
  lookup can't resolve — `ActorResolver` legitimately yields a null id while `system_actor` stays false). Give
  the cell a username/display-name (or at minimum fall back to `actorKeycloakSub`) so an audit reader can tell
  who acted. *(seam: `AuditEventDtos.AuditEventRow` + `audit-logs-list.page.ts:187`)*

## Pending (filed by /do-ship J-15 gate, 2026-08-02)

- **[J-15-MAILPIT-REPORT]** The J-15 AC "Run now → Daily Report → Mailpit email" is the one AC not proven as
  written. The mail itself IS proven — `DailyReportJobIT` asserts the per-person report against the captured
  outbox including the opt-out negative (two people on one flight, only the `receiveFlightReports` member is
  mailed) — but the real-idp gate never drives it through Mailpit, so the console-triggered path is unasserted.
  Closing it needs a **clean-seed floor**, which is why it was deferred rather than bodged: an opted-in pilot
  (`PersonClub.receiveFlightReports = true`) with an unreported flight inside the job's 2-day window, seeded
  through production code (ADR 0027 §3), then a `waitForMessageWithSubject('Flugrapport')` assertion in
  `jobs-console-parity.spec.ts` after the Run-now click. Seed it in the spec rather than the shared clean seed
  so no other journey's gate inherits a surprise mail. Rides the next journey that touches the jobs console or
  the mail path. *(seam: `e2e/tests/real-idp/jobs-console-parity.spec.ts` + its seed)*

## Pending (filed by /do-retro J-15/J-16/J-30 window, 2026-08-02)

- **[CI-TROUBLESHOOTING-MARKER]** Implement the fail-closed troubleshooting switch the operator chose
  (2026-08-02): a committed `.ci-troubleshooting` marker makes `ci.yml` skip the heavy lane so diagnosis can
  run as temporary GitHub workflows IN PARALLEL with local coding (this box is 2 cores — J-15 lost two rounds
  to Gradle-vs-Playwright contention), **and** makes `required` hard-FAIL with an explicit
  "CI is in troubleshooting mode" message. Fail-closed is the whole point: the PR must be physically unable to
  go green while the marker exists, so a forgotten re-enable cannot hand the operator a green-over-nothing PR.
  `/do-ship` §4 documents the workflow half. *(seam: `.github/workflows/ci.yml` `required` job)*

## Pending (filed by /do-ship J-30 gate, 2026-07-22)

- **[LEGACY-J2-READINESS]** `e2e/tests/flights/flights-parity-J2.spec.ts` is `@quarantine-legacy`'d (grep-inverted
  from the nightly) — the heaviest legacy parity spec (list → flight-edit → tow-form → motor) is irreducibly flaky
  on the Mono/AngularJS reference stack under CI load: the HB-3407 row render + `flightDetails.StartType` bind never
  arrive reliably, exhausting `retries:3` even after three rounds of step-wait hardening (T-15/T-16/T-18). All 12
  GENUINE legacy reds were fixed; this is the residual. Un-quarantine via a dedicated legacy render-readiness pass,
  or re-home that parity coverage on the AlpenFlight side. *(seam: flights-parity-J2 + the legacy flight-edit form load timing)*
- **[LAN-PG-SEED-DRIFT — dev-box only]** On the shared LAN Postgres, V48's `UPDATE … SET join_code='SEEDCLUB'` hit
  0 rows for the seed club (`019e30c3-…-001` carries `join_code=L8PDJDXF`) → the default join-code path is broken
  LOCALLY (T-11 verified via a throwaway env override, reverted). CI's fresh migrate is unaffected. Re-seed the dev
  DB or make V48 idempotent-by-id. *(seam: dev LAN-PG state / V48)*
## Pending (filed by /do-retro J-12a window, 2026-06-24)

- **[PER-JOURNEY-DOC]** (standing rider — activates once the doc-gen documentation journey ships) Each feature journey contributes its user-manual page + architecture-diagram delta to the generated docs site as a gate rider, so the manual/diagrams stay current as a byproduct of shipping (operator 2026-06-24). *(seam: the doc-gen site generator + per-journey doc delta)*

## Pending (filed by /do-ship J-27 gate, 2026-06-20)

- **[SUITE-ISOLATION — operator principle 2026-06-19].** Non-migration parity specs should set up their own data;
  migration specs run FIRST and rely ONLY on legacy seed data (assert what it genuinely produces, never gerrymander
  the seed). J-27 applied this to `:577`; the broader suite restructure (audit the other parity specs for hand-crafted
  `_test-fixture.sql` dependencies) rides a future test-architecture slot. *(seam: e2e/tests/real-idp parity specs + `_test-fixture.sql` §4/§5 hand-crafted rows)*

## Pending (filed by /do-retro 2026-06-14, J-7/J-26/J-8 window — operator debt-burndown)

- **[WORKFLOW-SLIM] Extract the repeated per-journey YAML blocks into composite actions (`.github/actions/`)**
  to cut the workflow YAML (~4.5k→~2k) — the only still-pending half (the mock-suite sharding, real-idp shard,
  and KC-26 quarantine all shipped). *(seam: `ci.yml` + `alpenflight-proof-fanout.yml` + `alpenflight-e2e.yml` +
  new composites)*
- **[COMMENT-STRIP] Self-explanatory code, why-only comments** — the pre-existing cross-journey narration carried
  in `MapperLegacyBindings.java` and the real-idp `_helpers/fan-out-parity-fixture.ts` (its own burndown slot —
  too big for a per-touch fold). The do-* skills enforce why-only going forward.
  *(seam: those two files + per-touch elsewhere)*
  [[feedback_self_explanatory_no_history_comments]]
- **[HISTORY→GIT] Journey/story files contract-only.** Prune journey files to frontmatter + ACs + the
  task checklist + load-bearing decisions + a short Outcome — drop the per-task implementation prose
  + any "Original (for trace)" blocks; that history is in git/commit messages. Per-touch (the in-flight +
  next journeys; don't churn merged ones). *(seam: `docs/modernization/stories/*.md` per-touch)*
  [[feedback_self_explanatory_no_history_comments]]

## Pending (filed by /do-ship 2026-06-13, J-26 gate)

- **[MAINTAINABILITY-TOOLING — Qodana baseline backfill].** Qodana shipped report-only (J-8 T-15), but the
  committed `qodana.sarif.json` is a PLACEHOLDER empty baseline (the local Docker run OOM-killed on the LXC box);
  the first CI `qodana-scan` run establishes the real baseline → download its `qodana-sarif-<run_id>` artifact +
  commit it over the placeholder. *(rides the next journey that touches CI / a maintainability slot)*
  [[reference_fallow_maintainability_analyzer]]
- **[KC-26 UPGRADE DRIFT] 3 cross-journey real-idp nightly reds (pre-existing, surfaced when J-26 T-03 re-enabled the 12-day-dead nightly).** NOT J-26's vertical (validation/JDBC) — KC-26-upgrade reconciliation that needs iterative live-stack debugging; J-26's OWN real-idp proof is green. T-30a/d authored first fixes that the gate proved insufficient: (1) `login.spec.ts:92` `?ui_locales=fr` → `<html lang="fr">` still renders `en` (KC 26 honors the param differently / login-theme or realm i18n-resolver — needs live-KC iteration, possibly a realm/theme config change); (2) `register.spec.ts:49` KC→Mailpit verify-mail never arrives (T-30d added a fail-loud SMTP preflight + 45s timeout — next dispatch's preflight output pinpoints DNS/SMTP vs KC-not-sending); (3) `token-lifecycle.spec.ts:47` silent-refresh still red after the wait-hardening (likely real KC-26 refresh-grant/SSO behavior, not timing). Each fix → ~25-min nightly dispatch → observe → repeat. Ride the next journey's gate (or a focused KC-26-reconciliation slice). *(seam: realm-export.json i18n/SMTP + login/token real-idp specs + KC 26 OIDC behavior)* [[project_real_idp_real_roles_catches_authz_gaps]]

## Pending (filed by form-validation parity audit 2026-06-09)

Full analysis + per-form verified gaps: `docs/modernization/form-validation-parity-audit.md`
(ultracode sweep — 12 forms, legacy-oracle → parity-review → gap-hunter verify). Operator bar:
legacy = minimum; **all** validations as-you-type (debounced ~200ms); server-on-submit stays the
safety step. **Each rider rides the next touch of its form.**

**P4 — server-roundtrip as-you-type pre-checks (submit-time 409 already CONFIRMED safe — UX only):**
- Add a non-mutating `…/validate` endpoint + debounced store rxMethod (model on reservation overlap
  `AircraftReservationsService.java:229-244`) + merge via `asyncErrors$`/`mergeFieldErrors`
  (`inline-validation.ts:56,67`) for: aircraft immatriculation, article articleNumber, location ICAO,
  user username. *(seam: per-aggregate /validate endpoint + store)*

**P5 — declined better-than-legacy / cosmetic (low):**
- Planning-setup: client `start ≤ end` + `≥1 weekday` cross-field validators + error region
  (`planning-setup.page.ts:170-191,242-254`); planning info `maxLength(4000)` client-side
  (`planning-edit.page.ts:376`). DIVE→400 handlers for reservation/planning FK→500 (phantom
  type/location/person ids — parity-met, lowest). *(seam: planning-setup validators + reservation/planning
  DIVE handlers)*

## Pending (filed by /do-retro 2026-06-07, J-6 window)

- **Un-mask the migration-ingest constraint in dev/test (operator grill, J-6 retro).** The bundle-ingest path
  catches the JDBC `SQLException` and returns only `{"detail":"Database error during ingest [sqlstate=23505]",
  "errorCode":"INGEST_INTERNAL_ERROR"}` — the real constraint name (`ux_pln_club_date_loc`, the FK name) is
  buried in the server log. In dev/test profiles, include the constraint name in the error body/detail so a
  fanout red is diagnosable without server-log archaeology (J-6 23505/23503 each cost a log-dig). Keep prod
  masked. *(seam: MigrationBundleIngestService catch → dev/test constraint-name surfacing)*
  [[project_synth_bundle_doesnt_validate_producer_select]]

## Pending (filed by /do-ship 2026-06-07, J-6 gate — gap-hunter suspects)

- **Producer dedupe is soft-delete-blind (gap-hunter, J-6 T-11b/T-16).** The PLANNING_DAY (and the
  assignment FIRST_VALUE remap) producer SELECT partitions across ALL legacy `PlanningDays` rows with NO
  `WHERE DeletedOn IS NULL` filter, but `ux_pln_club_date_loc` is PARTIAL (`WHERE deleted_on IS NULL`,
  V4:303-305). If a `(Club,Day,Loc)` partition ever held an earlier-`CreatedOn` *deleted* row + a later
  *live* row, `ROW_NUMBER ORDER BY CreatedOn` keeps the DELETED one → silently drops the live planning day
  (the partial index would never have collided). **Neutralized for J-6**: legacy `PlanningDayService.cs:407`
  HARD-deletes planning days, so `DeletedOn`/`IsDeleted` are vestigially never set — no soft-deleted days
  exist to trigger it. **Fix before this dedupe pattern is copied to a SOFT-deleting table:** add
  `WHERE DeletedOn IS NULL` to the dedupe inner source (+ extend `PlanningDayProducerDedupeIT` with a
  deleted-vs-live partition case) OR an explicit "legacy hard-deletes → safe" comment. *(seam:
  MapperLegacyBindings producer dedupe SELECT)* [[project_synth_bundle_doesnt_validate_producer_select]]

## Pending (filed by /do-retro 2026-06-06, J-5 window)

- **CI fail-aggregate (surface ALL reds in one run).** ci.yml stops at the first failing layer (build → server-test → web-lint → mock-e2e discovered serially across cycles). Run the independent checks as parallel jobs that all report, so one run shows every red at once. *(seam: ci.yml job parallelism/aggregation)*

## Pending (filed by /do-plan 2026-06-06, J-5 carve — maintainability tooling)

**Maintainability = complexity + duplication + dead code** (operator, 2026-06-06 —
[[feedback_maintainability_includes_dupes_and_deadcode]]): run fallow's **full default**
(`dead-code` + `dupes` + `health`), not just `health`, and report/track all three.

- **Refactor the genuine complexity hotspots — each rides the journey that TOUCHES it (operator:
  riders only, no ad-hoc project-code change).** STILL PENDING (their own next-touch journey):
  `flights/list/flights-list.page.ts` (24cyc, 315 LOC, untouched), and the flights `store.errorPatch`
  (deliberately unconverted — it's a 412/409 optimistic-lock state machine, not a kind-table). *(seam:
  `flights-list.page.ts` + the flights store `errorPatch`)* [[reference_fallow_maintainability_analyzer]]

## Pending (filed by /do-ship 2026-06-05, J-4 window)

- **Legacy `/profile` walkthrough video doesn't stage in the fanout `legacy-parity` gallery (J-4 done-bar
  loose end).** The legacy parity spec `e2e/tests/profile/profile-parity-J4.spec.ts` now PASSES (accordion-
  expand fix) + the 8 paired screenshots render, but the staging `find /tmp/fls-e2e-results -path
  '*profile-parity-J4*' -name '*.webm'` finds no video → `profile-parity-J4.webm` not declared. The J-0c/J-1/J-2
  legacy specs DO stage videos on pass, so it's a per-`profile`-project video-retention/output-dir quirk, not
  pass-vs-fail. Done-bar was met by the paired screenshots ("judgeable side-by-side"); add the video on the next
  fanout-touching task. *(seam: top-level e2e `profile` project video config / the fanout video-find path)*

## Pending (filed by /do-ship 2026-06-04, J-2 window)

- **e2e tsc-strictness** — `tsc -p alpenflight/web/e2e/tsconfig.json` reports ~23 pre-existing
  `exactOptionalPropertyTypes`/`maxFailures` errors (`playwright.config.ts`, `flights-list.spec.ts`,
  `aircraft-crud.spec.ts`, `persons-add-modal.spec.ts`, `proof-gallery.spec.ts`, `migration/handshake.spec.ts`).
  Playwright's esbuild transpile tolerates them; harmless until/unless an e2e `tsc` gate is wired.
  *(seam: e2e/tsconfig strict-mode cleanup)*
- **e2e prettier-glob not clean** — `prettier --check 'alpenflight/web/e2e/**/*.{ts,json}'` flags ~42
  pre-existing unformatted specs (repo-wide, predates J-2). A format-normalization pass; don't fold
  into a feature PR. *(seam: e2e prettier normalization)*
- **op-field-mutate test coverage (gap-hunter nit, T-21)** — `FlightCrew.updateOperationalFields` (the
  kept-row in-place reconcile) is only exercised by a re-assert with *identical* values; a changed
  `nrOfLdgs`/time on an unchanged-identity crew row isn't asserted. Code is correct; add the assertion
  on the next flights touch. *(seam: FlightDomainTest / FlightsControllerIT crew-op-field case)*
- **orphaned clubadmin4 realm-user + V29 seed** — T-24 added `clubadmin4` (realm-export user +
  `V29__dev_user_seed_clubadmin4.sql`) as a motor-test principal; T-36 unified motor into /flights and
  the motor test reverted to `fixture.clubA`, leaving clubadmin4 + V29 self-referenced only. Inert
  (realm user + a `t_user` row); a clean removal needs care (removing a landed Flyway migration mid-line
  risks a checksum surprise). Remove on a later journey. *(seam: realm-export.json clubadmin4 + V29)*
- **JIT-username robustness (observation, T-22/T-23)** — `JitUserMaterializerImpl` reconcile-by-username
  (T-23) handles the concurrent-sub-race; the residual is that a genuinely distinct sub reusing a live
  username is rebound rather than rejected — defensible (username = person identity) but worth a
  `legacy-oracle`/security look if multi-IdP lands. *(seam: JitUserMaterializerImpl)*

## Pending (filed by /do-ship 2026-06-05, J-3 window)

- **orval positional `getN` method naming is fragile across regenerations** — the generated TS client
  names methods positionally (`get2`, `get3`, …); adding an endpoint (J-3 T-10 `/me/system-dashboard`)
  renumbered them, silently re-pointing T-09's `ClubDashboardStore.get2()` at the wrong endpoint (caught
  + fixed in T-11, but only because the next consumer broke the typecheck). Make the binding stable:
  set explicit `operationId`s on the `me`-dashboard endpoints (and ideally project-wide) so orval emits
  named methods, not positional `getN`. The new accounting endpoints (J-8 T-06/T-07) already carry explicit
  `@Operation(operationId=…)`; the **project-wide** pass over the remaining legacy `getN` endpoints is STILL
  PENDING — do it in isolation on a future web journey, not bolted onto a feature journey. *(seam: backend
  operationId annotations + orval config + the `meService.getN()` call sites)*

## Pending (filed by /do-ship 2026-06-09, J-7 gate)

- **planning fixture club-B KC provisioning `beforeAll` can 45s-timeout under contention (J-7 T-20).** Did not
  reproduce on re-run (fanout `retries:1` absorbs it); if it recurs, bump that fixture's provisioning timeout
  or warm the KC admin client. *(seam: planning-migration-parity beforeAll club-B provisioning timeout)*
- **Planning `:410` edit-crew cold-`page.goto` reopen flakes on OIDC reboot/renew stall (J-7 T-21).** The
  reopen via `page.goto('/planning/{id}/edit')` hits the documented cold lazy-chunk/OIDC-renew stall
  ([[project_real_idp_goto_reboot_renew_stall]]); self-heals warm + CI `retries:1`. Switch that reopen to warm
  in-app nav to harden. *(seam: planning-migration-parity :410 reopen → warm nav)*

## Pending (filed by PR #215 review, 2026-06-10 — ADR 0027 JPA-first / no-JDBC)

- **Retire the remaining main-code JDBC/native sites per-module on next touch (ADR 0027 §1).** STILL PENDING
  convert-on-touch: `JpaUserRepository` (remaining native), `JpaPersonRepository` (cross-tenant check),
  `AircraftReservationConflictProbeImpl` (KEEP-GiST recorded T-17), `ShowcaseSeeder`. Structurally-pre-tenant
  seams stay register-listed (`UserPrincipalLookup`, `PreTenantUserLookup`, `ReferenceDataSeeder`,
  `MutationAuditEventListener` system-actor write). *(seam: per-module infra layer)*
- **IT seeding: raw-JDBC → production-code per-touch (ADR 0027 §3).** ~85 ITs (incl. `TenantScopedRowBuilders` /
  `TwoClubFixture` consumers) seed via `JdbcTemplate`; convert each file the next time it's materially edited —
  convention, NOT a sweep story. ADR 0021 isolation rules unchanged. **Same per-touch convention now also covers
  club-id collisions:** single-schema external-PG runs (RM-2a) surfaced classes sharing club UUID literals by
  value with club-HARD-DELETING classes; latent pairs left — the migration round-trip family's bundle clubs
  `…04be`/`…0bb8` are also referenced by Audit*/Clubs* ITs — give a class ITS OWN club ids when touching it;
  production-reserved ids (ShowcaseSeeder, V-seeds) are off-limits as foreign fixture clubs. *(seam: server
  src/test, per-touch)*
- **Fanout has NO reporting spec over MIGRATED data (predates the read-model conversion; found at RM-5).** The
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

- **IT seeding: per-IT raw-JDBC seeders → production code, PER-TOUCH (ADR 0027 §3).** The shared seeders are now
  done. The standing remainder is the **per-IT** seeders: of the **84** server test files touching `JdbcTemplate`
  (verified by grep, not estimated), **44 SEED (raw `INSERT`)** and **40 use JDBC purely for assertion (`SELECT`)
  / hard teardown (`DELETE`)**. The 40 assert/teardown files are NOT the §3 anti-pattern (no domain-invariant
  bypass — a hard `DELETE` that bypasses soft-delete is legitimate test infra) and should stay JDBC. The ~44 seed
  files convert **one file at a time, on its next material edit** — ADR 0027 §3's own rule is per-touch, NOT a
  sweep story (an 84-file sweep is explicitly forbidden). *(seam: `server/src/test`, per-touch)*
- **Pinned-id aggregate seeds legitimately stay JDBC (the @GeneratedValue + Hibernate-7-overwrite wall).** When an
  IT must seed an aggregate at an **externally-pinned** id (a consumer asserts on that exact id), production save
  paths cannot deliver it: aggregate roots use `@GeneratedValue(UUID)` and Hibernate 7's `UuidGenerator`
  (a `BeforeExecutionGenerator`) OVERWRITES any reflection-set id at insert — so save/persist/stateless-insert
  all mint a fresh id (the T-19 wall). Such pinned-id seeds keep their raw `INSERT` (documented inline), citing
  the `tenancy-showcase-seed-deterministic-ids` native-sql-register precedent. *(seam: `server/src/test`, per-touch)*

## Pending (J-9-filed, UPDATED by J-10 2026-06-15 — the fanout now runs end to end)

**⚠ BLOCKS the next MIGRATION journey** (hard fanout gate, J-9 retro). Fold into the next migration journey
(J-1 / J-21, whichever ships first):

- **J-9 article-5001 — the migrated FlightTime filter emits no article-5001 line.** T-07's poll-to-COMPLETED
  did NOT resolve it (so it's not just deployment timing) — the migrated "FlightTime: Glider per minute"
  filter genuinely isn't applying over the migrated glider flight. Investigate the migrated filter's
  predicate/scope vs the migrated flight. T-08 strengthened the assertion to bit-exact (`=== 47`), so it
  fails loud. *(`delivery-creation-test-parity.spec.ts` migrated block)*
- **J-8 AccountingRuleFilter migrated predicate config not intact.** `accounting-rules-parity.spec.ts:524`
  — the migrated filter renders but its `filter_config` predicate doesn't match legacy (an
  AccountingRuleFilter migration-fidelity gap). *(`accounting-rules-parity.spec.ts` + the filter mapper)*
- **J-0c Location migrated render.** `fan-out-migration-parity.spec.ts:167` fails — investigate the migrated
  Location render. *(`fan-out-migration-parity.spec.ts`)*
