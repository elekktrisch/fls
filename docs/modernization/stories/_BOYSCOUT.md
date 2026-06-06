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

## Pending (filed by /do-plan 2026-06-06, J-5 carve — maintainability tooling)

**Maintainability = complexity + duplication + dead code** (operator, 2026-06-06 —
[[feedback_maintainability_includes_dupes_and_deadcode]]): run fallow's **full default**
(`dead-code` + `dupes` + `health`), not just `health`, and report/track all three.

Ran `npx fallow@latest` (full default; deterministic TS/JS code-intelligence) on `alpenflight/web`.
Raw score **52 D** was misleading — fallow scanned `node_modules` + a stray `node_modules.windows`
tree (reported **2371** unused deps vs **41** declared) and counted the orval-generated client in
duplication (**20.8%**). With `node_modules.windows` + `src/app/api/generated` excluded the honest
read across all three axes is: **complexity** MI **92 (good)** but **87** high-complexity fns;
**duplication 11.7%** (192 clone groups, 83 files); **dead code** small — **3** unused files, **1**
unused dep + **5** unused devDeps, **1** unresolved import. NOT in trouble — a tight, real hotspot
short-list + needs a committed config to stop crying wolf.

- **Commit `alpenflight/web/.fallowrc.json` so the score is honest (operator: commit config).** Add
  `ignorePatterns: ["**/node_modules.windows/**", "src/app/api/generated/**", "dist/**", "coverage/**"]`
  + `health.ignore: ["src/app/api/generated/**", "**/*.spec.ts", "e2e/**"]`. Validated locally (52 D →
  70 B; 2371→6 unused deps; 20.8%→6.3% dup). First/cheapest fold into J-5's ≤40% budget; unblocks any
  later `fallow audit`/CI use. *(seam: new `alpenflight/web/.fallowrc.json`)* — see [[reference_fallow_maintainability_analyzer]].

- **Refactor the genuine complexity hotspots — each rides the journey that TOUCHES it (operator:
  riders only, no ad-hoc project-code change).** Real production offenders fallow flags (CRITICAL/HIGH
  CRAP, after excluding tests/generated): `aircraft/edit/aircraft-edit.page.ts` `<component>`/`formToUpdateRequest`
  (35cyc, 366 LOC); `flights/edit/flight-form.defaults.ts:53 applyLastContextThenPrefs` (29cyc, **CRAP 210**);
  `flights/edit/flights-edit.page.ts:613` `finalSubmit` (27cyc); `users/edit/users-edit.page.ts:442 onSubmit`
  (29cyc); `users/users.store.ts:307 errorPatch` (25cyc, CRAP 160); `flights/list/flights-list.page.ts` (24cyc,
  315 LOC); `persons/edit/persons-edit.page.ts hydrate`. They cluster in the shared `*-edit.page.ts`
  form-mapping + store-`errorPatch` pattern. **For J-5 specifically:** build the new reservation edit page
  WITHOUT replicating that `formToUpdateRequest`/`finalSubmit`/`errorPatch` complexity — extract the shared
  form↔request + error-patch helper so the new page lands low-CRAP (and the extraction can later absorb the
  aircraft/flights/users hotspots as each is touched). The non-J-5 hotspots (aircraft/users/persons edit
  pages) ride their own next-touch journey, not J-5. *(seam: `*-edit.page.ts` form-mapping helper extraction
  + the per-feature store `errorPatch`)*
  Minor, same budget: drop the **3 unused files + 6 unused deps + 1 unresolved import** fallow lists once
  the config lands (`fallow dead-code` enumerates). Not worth `fallow fix` (dead files only 1.1%).

- **Java maintainability tool for `alpenflight/server` — add PMD + CPD (+ SpotBugs) (operator: "similar
  tool for Java").** The backend already covers the *architecture* axis (Spring Modulith
  `ApplicationModulesTest` + ArchUnit boundary rules), but has **no** complexity / duplication / dead-code
  analysis — the other three fallow axes. Closest deterministic-CLI Java analog (CI-friendly, gradle
  plugins): **PMD** (cyclomatic/cognitive complexity rules + unused private code) + **CPD** (PMD's
  copy-paste detector, the duplication axis) + optionally **SpotBugs** (dead stores / bug patterns from
  bytecode). Concrete baseline today (jscpd proxy, `--min-lines 8`): **5.57% duplicated lines, 101 clones
  across 434 files** — modest, worth tracking not firefighting. Recommend gradle `pmd` + a `cpdCheck` task
  wired into `check` with a baseline so it ratchets, NOT a hard fail on existing debt. (SonarQube is the
  fuller dashboard but heavier / server-hosted — defer unless the operator wants the dashboard.) Rides the
  next backend-touching journey's ≤40% budget (J-5 builds the reservation aggregate — a natural first
  PMD/CPD target). *(seam: `alpenflight/server/build.gradle.kts` pmd+cpd plugin + baseline + `check` wiring)*
  — see [[reference_fallow_maintainability_analyzer]].

- **Add a per-journey Maintainability panel to each proof-gallery journey page (operator: "add the reports
  to the proof gallery for each journey page").** Extends the gallery per-journey re-arch rider above (the
  index → one page per journey). Each journey page gains a **Maintainability** section rendering the
  journey's *delta* + repo snapshot across the three axes: **frontend** via fallow's changed-files envelope
  for the journey's `integration/J-NNN` branch (`fallow ci`/`audit` emits a PR/MR JSON envelope = exactly
  the complexity/dupes/dead-code introduced by the journey's diff) + the snapshot (MI, dup%, dead-code);
  **backend** via the PMD/CPD (+SpotBugs) report on the changed Java. The gallery deploy step runs
  `fallow ci --format json` + the gradle pmd/cpd XML, and `generate-gallery.mjs` renders an HTML panel
  (green/amber/red on the delta, link to full report). So each journey's page shows not just "the screen
  works" but "the journey didn't rot maintainability". Project-code/CI work → rides a journey's ≤40% budget
  *with* the gallery re-arch (it's the same generator + deploy seam). *(seam: `generate-gallery.mjs`
  maintainability panel + ci.yml/fanout `fallow ci` + pmd/cpd report-emit steps)* — see
  [[feedback_proof_gallery_per_journey_one_bookmark]], [[feedback_maintainability_includes_dupes_and_deadcode]].

## Pending (filed by /do-ship 2026-06-05, J-4 window)

- **Legacy `/profile` walkthrough video doesn't stage in the fanout `legacy-parity` gallery (J-4 done-bar
  loose end).** The legacy parity spec `e2e/tests/profile/profile-parity-J4.spec.ts` now PASSES (accordion-
  expand fix) + the 8 paired screenshots render, but the staging `find /tmp/fls-e2e-results -path
  '*profile-parity-J4*' -name '*.webm'` finds no video → `profile-parity-J4.webm` not declared. The J-0c/J-1/J-2
  legacy specs DO stage videos on pass, so it's a per-`profile`-project video-retention/output-dir quirk, not
  pass-vs-fail. Done-bar was met by the paired screenshots ("judgeable side-by-side"); add the video on the next
  fanout-touching task. *(seam: top-level e2e `profile` project video config / the fanout video-find path)*

- ~~**Docker disk leak — orphaned Testcontainers PG fills the LXC box (operator flagged twice, J-4).**~~
  **Shipped J-5 T-02.** `PostgresTestContainerLifecycle.start()` now runs a **pre-start sweep** that
  `docker rm -f -v`s stale `alpenflight-pg-test-*` containers + their volumes (concurrency-safe: only reaps
  containers older than a 60s age guard, never a sibling run's booting container; containers now carry a
  `ch.alpenflight.test=pg` label for precise targeting); the **readiness cap was raised 60s → 120s** so
  workers can self-verify ITs locally under load; and a fail-soft **settings.json Stop hook**
  (`.claude/settings.json` → `.claude/hooks/prune-test-containers.sh`) prunes orphaned `alpenflight-pg-test-*`
  + dangling volumes at session end. Verified via the `PostgresIntegrationTestSmokeIT` (lifecycle brings up a
  container, Flyway migrates, container reaped cleanly). — see [[project_docker_disk_leak_orphaned_testcontainers]].

## Pending (filed by /do-retro 2026-06-06, J-4 window)

- **Proof galleries: collapse the 4 per-proof-type galleries into ONE page PER JOURNEY (operator design,
  /do-retro J-4).** Today there are 4 gh-pages destinations (clean-seed `…/<branch>/`, showcase `…/dashboard/`,
  `…/profile/`, fanout `…/legacy-parity/`) each rendering a DIFFERENT subset of state across ALL journeys, plus
  the new persistent `…/alpenflight/previews/index.html` link-directory (J-4 T-24) that links to them. The
  operator can't find one journey's current proof. **Target model (operator-confirmed):** the index lists
  JOURNEYS (J-0…J-N); each links to ONE **per-journey page** that aggregates THAT journey's full proof — paired
  legacy↔AlpenFlight screenshots/videos + the real-idp run — filtered to the single journey. The per-proof-type
  galleries stop being separate destinations and become **sources** the per-journey page assembles from. Likely:
  the gallery generator keys by `journey` (it already carries a `journey` field on every shot/video sidecar
  entry) and emits one page per journey to `…/<branch>/J-<n>/` (or `…/previews/<branch>/J-<n>/`); the index +
  the per-push/fanout deploy steps target the per-journey pages; retire the dashboard/profile/legacy-parity/
  clean-seed sub-paths + their deploy steps. **SUBSTANTIAL pure tech-debt → rides journeys' ≤40% tech-debt
  budget** (operator: a journey is a Scrum sprint ≥60% AlpenFlight feature / ≤40% tech-debt — gallery re-arch
  delivers no AlpenFlight functionality so it is NOT its own journey). Too big for one 40% slot → split the
  re-arch across the next 2-3 journeys' tech-debt budgets (e.g. generator keys-by-journey first, then retire
  sub-paths, then the deploy/index rewire). *(seam: `generate-gallery.mjs` + `generate-previews-index.mjs` +
  the gallery-deploy steps across ci.yml + alpenflight-proof-fanout.yml + the rebuild-previews-index composite)*


- ~~**Make "Run Playwright" part of the required `ci` gate (operator, J-2 retro).**~~ **Shipped J-3 T-12**
  — folded the mock-auth chromium suite into `ci.yml` as the `alpenflight-mock-e2e` ("Run Playwright")
  job, added it to the `required` aggregator's `needs` + result-check loop, and gated it on the same
  `next && !docs_only` path-filter as the other heavy jobs (skipped→success on docs-only). The suite no
  longer double-runs: `alpenflight-e2e.yml` lost its `pull_request` trigger (now `push`-to-main only,
  retaining the distinct `/alpenflight/` gh-pages e2e dashboard publish; ci.yml owns PR gating). A red
  mock-auth e2e now turns `required` red and blocks merge like `alpenflight-proof` does.

## Pending (filed by /do-ship 2026-06-04, J-2 window)

- **Collapse the two proof galleries into one (operator-requested, J-2 window).** Each PR carries
  TWO gallery sticky comments: the ci.yml AlpenFlight-only preview (`…/proof-preview/integration-J-N/`,
  built every push, **no legacy pairing**) and the fanout `legacy-parity` gallery
  (`…/legacy-parity/`, the complete paired legacy↔AlpenFlight proof). The operator only consumes the
  full one — the AlpenFlight-only gallery + its second comment is noise. Make the fanout `legacy-parity`
  gallery THE gallery at the canonical path and drop ci.yml's AlpenFlight-only deploy + its sticky
  comment (or have ci point at the last fanout gallery). Needs a fanout run to validate → ride the next
  journey's gate. *(seam: ci.yml gallery-deploy job + alpenflight-proof-fanout.yml deploy + the two
  `<!-- proof-preview -->` / `<!-- fanout-proof-preview -->` comment upserts)*
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

## Pending (filed by /do-retro 2026-06-03, J-1 window)

_(no pending riders — see Shipped below)_

_Deferred (operator, Q3): fanout-perf (own runner / sharding / no spec co-location) + re-enable
the T-18 J-0c rename test — recorded in the J-1 journey file, not filed as an active rider yet._

_Scan note: no e2e specs carry `@helper`/`covered-by` tags yet → no helper-pruning rider this round._

## Shipped

- **modernize-\* sunset** — shipped J-3 T-15. do-* proven across J-0/J-0b/J-0c/J-1/J-2/J-3
  (incl. the non-migration feature journeys), so the trigger was met. Deleted the 9 `modernize-*`
  skills + the 12 modernize-specific agents (`requirements-engineer`, `solution-architect`,
  `security-engineer`, `qa-engineer`, `performance-engineer`, `maintainability-reviewer`,
  `parity-reviewer`, `security-reviewer`, `usability-reviewer`, `tech-writer-reviewer`,
  `legacy-investigator`, `implementation-architect`) and pruned the 15 `rolled_up_into:` horizontal
  `S-*` stories. Kept the 4 do-* agents (`legacy-oracle`, `slice-carver`, `gap-hunter`, `e2e-driver`)
  and the 47 `implemented/` stories (history). Rewrote `docs/modernization/README.md` + the CLAUDE.md
  triage table to the do-* workflow; updated do-retro's sunset section to past-tense. (The 2026-06-02
  + 2026-06-04 sunset entries were consolidated into this one bullet.)

- **ci.yml path-filter for docs/story-only pushes** — shipped J-2 T-11. Root cause: on
  `integration/**` branches the `pull_request` trigger made `dorny/paths-filter` diff the
  WHOLE PR vs `main`, so `changes.next` was always true (the branch already carries
  alpenflight/ commits) → every doc-only push re-ran build+proof. Fix: a new
  `changes.docs_only` output computed from the INCREMENTAL push diff
  (`github.event.before..after`); the three heavy jobs now gate on
  `next == 'true' && docs_only != 'true'`. Fail-safe toward running (undeterminable range
  → run). `required` aggregator stays green via the existing skipped-to-success case.

## Pending (filed by /do-ship 2026-06-05, J-3 window)

- **orval positional `getN` method naming is fragile across regenerations** — the generated TS client
  names methods positionally (`get2`, `get3`, …); adding an endpoint (J-3 T-10 `/me/system-dashboard`)
  renumbered them, silently re-pointing T-09's `ClubDashboardStore.get2()` at the wrong endpoint (caught
  + fixed in T-11, but only because the next consumer broke the typecheck). Make the binding stable:
  set explicit `operationId`s on the `me`-dashboard endpoints (and ideally project-wide) so orval emits
  named methods, not positional `getN`. *(seam: backend operationId annotations + orval config + the few
  `meService.getN()` call sites)* — fix-forward on the next web-touching journey.

## Pending (filed by /do-retro 2026-06-05, J-3 window)

- **Scope the clean-seed `alpenflight-proof` job to the journey-under-work's spec (operator ask, J-3
  retro).** Today `ci.yml`'s `alpenflight-proof` re-runs J-0+J-1+J-2(+…) real-idp specs on EVERY push —
  slow, expensive, and it lets an unrelated prior-journey flake red the current journey's gate (J-3:
  the J-1 aircraft retry-flake blocked J-3, which never touched aircraft). Make the per-push proof run
  only the **current journey's** spec(s) (parameterize the spec list off the integration branch / a
  journey marker), and move the **full cross-journey regression** to a **gate-only / nightly** run
  (`alpenflight-e2e-real-idp.yml` already hosts a nightly full suite — point the regression there). Pairs
  with the [[J-1 aircraft flake]] rider (lighter + scoped proof also stops that flake gating other
  journeys). *(seam: ci.yml alpenflight-proof spec selection + the nightly full-suite trigger)*

## Pending (filed by /do-ship 2026-06-05, J-3 window)

- **J-1 aircraft real-idp spec flake (S-163 timeout → retry → create-residue → 6≠3).** In the shared
  clean-seed `alpenflight-proof` run, `aircraft-migration-parity.spec.ts` intermittently fails: the
  `S-163` case (`:407`, non-managing-club owner edit) times out at 45s, Playwright retries the
  create-aircraft test, the create isn't cleaned up across attempts, so the initial
  `toHaveCount(3)` (`:228`) sees 6 on the retry. Pre-existing (predates J-3 — it's in `main` via J-1);
  passed J-3's gate by luck on the final run. Fix on the next aircraft-touching journey: make the
  aircraft-create test idempotent across retries (clean up the created row / assert a delta not an
  absolute) AND diagnose the S-163 45s timeout (raise it or fix the slow non-managing-club edit path).
  *(seam: aircraft-migration-parity.spec.ts retry-isolation + S-163 timeout)*
- **PILOT flights-read authz gap — FIXED in J-3, lesson for /do-retro.** `FlightsController.list/get`
  was `@PreAuthorize(hasAnyRole('CLUB_ADMINISTRATOR','FLIGHT_OPERATOR'))` (S-159, predating the pilot
  dashboard) → a PILOT got 403 reading their OWN last flight; the pilot dashboard card hung. Fixed in
  J-3 (PILOT granted tenant-scoped read on list+get + StartStore catchError). **Not a pending rider**
  (shipped) — recorded here as the /do-retro lesson: the **mock-auth** suite's admin principal HID this
  authz gap; only the **real-idp showcase run with a real PILOT principal** surfaced it. Real-roles
  end-to-end catches authz gaps mock-auth can't. *(retro lesson, not a code rider)*
