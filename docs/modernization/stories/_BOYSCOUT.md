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

## Retro-process flags

**✅ Adjudicated + encoded by /do-retro (J-6 window, 2026-06-07)** — the four flags below were resolved this
retro; kept for trace. (1) **Squash-merge branch handoff** → encoded in `/do-plan` §4 (squash-merge guard:
base on `origin/main` + cherry-pick the retro's net commit). (2) **T-01/T-02 standard slots** → encoded in
`/do-ship` §2 default decomposition (T-01 scaffolds the proof page; T-02 moves prior journeys to mock-IdP).
(3) **Proof-index fragility + verify-the-deployed-artifact** → encoded in `/do-ship` §4 (ONE-source gallery
model) + `e2e-driver` (verify the DEPLOYED page, never the unit test) + the structural post-deploy guard rider
below. (4) **Paired shots only at the fanout** → resolved by the J-6 T-17 capture-legacy-once-and-commit model
(now the standard in `/do-ship` §4 + `e2e-driver`). The original flag text follows.


- **🔁 The retro→new-carve branch handoff is rough — 5th time running (operator flag, J-6 carve 2026-06-06).**
  Every cycle, starting the next journey's `integration/J-NNN` off the `/do-retro` output is friction-y.
  **This cycle's concrete failure:** J-5 was **squash-merged** to `main`, but `do-retro/J-5-window` had
  branched off the *pre-squash* J-4 line and carried all ~83 redundant J-5 commits. `/do-plan`'s rule
  ("base the carve on the unmerged retro branch so riders ride forward") then produced a J-6 branch with a
  clean 11-file **net** diff but a junk 83-commit / 126-file **history** — the operator saw the inflated
  file count. Fixed by hand (reset to `origin/main` + cherry-pick the retro's net commit + the carve).
  **Root mismatch:** `/do-retro` lands its output on a branch off `main` *intending* it to ride the next
  journey, but once that journey squash-merges, the retro branch becomes divergent history; `/do-plan`
  still treats it as the live integration line. **For /do-retro to decide:** make the do-plan↔do-retro
  branch handoff deterministic — e.g. (a) `/do-plan` Mode B auto-detects a squash-merged prior journey and
  bases on `origin/main` + cherry-picks the retro's net commit (never branches off the stale retro branch);
  and/or (b) `/do-retro` keeps its net output as a *single* commit easy to cherry-pick forward; and/or
  (c) the retro branch is rebased/refreshed onto `main` at carve time. Encode the chosen rule in both
  SKILL.md files so this stops recurring. [[project_do_plan_carve_base_after_squash_merge]]

- **📋 Standardize the first two do-ship task slots (operator flag, J-6 ship 2026-06-06).** Two things
  should be *invariant* T-slots in `/do-ship`'s default decomposition, not ad-hoc late riders:
  - **T-01 always sets up the journey proof page** — scaffold the per-journey gallery page + link it from
    the persistent index at the very first task, so the operator's glanceable window exists from the start
    and accumulates captures as screens land (today the proof/gallery work drifts to a late task — J-5 had
    it at T-13, this journey carved it at T-14). Pairs with do-ship §4 "surface the gallery EARLY."
    [[feedback_surface_proof_early_on_repeated_failure]] [[feedback_proof_gallery_per_journey_one_bookmark]]
  - **T-02 always moves the previous journeys' specs from heavy (real-idp) proof to mocked-IdP** — so the
    per-push gate runs ONLY the journey-under-work heavy + prior journeys mock-IdP, from the second task
    onward (not held hostage by an unrelated heavy spec, and not deferred to a late CI rider — J-5 carved
    this as T-14/T-15-ish). Codifies [[feedback_dev_time_test_strategy]] as a fixed slot.
  Encode both as standing steps in `/do-ship` (§2 default decomposition) + `/do-task` so every journey gets
  them for free. (Applied retroactively to J-6: pulled forward as T-01b proof-page scaffold + T-02b prior-
  journeys→mock-IdP, run before the feature backend continues.)

- **🔁 The proof-previews INDEX keeps regressing the operator's bookmark — fragile gallery plumbing (operator flag, J-6 ship 2026-06-07).**
  The persistent index (`…/alpenflight/previews/index.html`) has now broken ≥3 distinct ways across journeys:
  J-4 T-24 (per-active-branch only → showed just the open branch), T-37 (narrowed the branch probe to
  `legacy-parity/` only → silently hid the per-push clean-seed per-journey page, so J-6 read `pending` on the
  bookmark all day until T-13b), AND **canonical `proof/J-2…J-5/` 404** (only J-0/J-0c/J-1 were ever published
  canonically → merged journeys aren't on the bookmark either). Each fix is a point patch; the path-matching
  between three deploy schemes (per-push clean-seed `proof-preview/<b>/J-n/`, fanout
  `proof-preview/<b>/legacy-parity/J-n/`, canonical `proof/J-n/`) is brittle and keeps drifting. **For /do-retro:**
  (a) add an END-TO-END guard — after a per-push proof deploy, assert the journey-under-work is actually a LIVE
  LINK on the DEPLOYED index (the unit test passed while the deployed page stayed `pending` because deploy-path
  and probe-path drift independently); (b) backfill canonical `proof/J-2…J-5/` per-journey pages (gallery re-arch
  debt, T-14 family); (c) consider collapsing the three deploy schemes to one convention so the probe can't drift.
  [[feedback_surface_proof_early_on_repeated_failure]] [[feedback_proof_gallery_per_journey_one_bookmark]]

- **🖼️ Paired legacy↔AlpenFlight parity screenshots come ONLY from the nightly/dispatch FANOUT — missing per-push during dev (operator flag, J-6 ship 2026-06-07).**
  "Surface proof early" is only half-met: per-push deploys the AlpenFlight pass-VIDEOS (real screens), but the
  **paired legacy↔AlpenFlight side-by-side screenshots** (the done-bar demonstrability) are staged ONLY by the
  heavy `alpenflight-proof-fanout.yml` (nightly + workflow_dispatch). So the operator opens the J-6 page mid-dev,
  sees AlpenFlight videos but **no legacy pairing**, until the §4 gate. **For /do-retro:** make paired-legacy
  capture available EARLIER — fold a cheap legacy-parity capture into the per-push proof for the journey-under-work,
  OR make the standard proof-page slots (T-01b/T-13) explicitly author + trigger the legacy parity capture so the
  paired shots land with the first screens. At minimum the proof page should SAY "legacy pairing pending — runs at
  gate" rather than silently omitting it. [[feedback_demonstrable_proof_prefer_ui]] [[feedback_surface_proof_early_on_repeated_failure]]

## Pending (filed by /do-retro 2026-06-07, J-6 window)

- **Structural post-deploy proof-gallery guard (operator grill, J-6 retro).** CI machinery, not procedure:
  after the per-push proof deploys, a job asserts the **journey-under-work's bookmark row is a LIVE LINK** AND
  **every declared asset (videos + paired screenshots) resolves 200 on the DEPLOYED page** — fails the proof
  job otherwise. The unit/generator tests passed ~4× this journey while the deployed page was wrong (wrong probe
  path, freshest-wins linking the thinner page); a procedure rule kept failing, so this must be structural.
  *(seam: a new deployed-gallery-guard step/spec the proof + fanout jobs run post-deploy — extends the existing
  `proof-gallery-links` deployed-link-check to also assert the journey-under-work page is linked + complete)*
  [[feedback_surface_proof_early_on_repeated_failure]] [[feedback_proof_gallery_per_journey_one_bookmark]]
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
- **Cascade-delete asserted only indirectly (gap-hunter, J-6 T-16).** The `[key-error]` delete proves the
  user-visible AC (day leaves the list, parent GET 404, freed slot re-creatable) but never asserts the 3
  child `t_planning_day_assignment` rows are gone — and since delete is a soft-delete on the aggregate, the
  V4 `ON DELETE CASCADE` FK never fires, so orphaned/leaked assignment rows wouldn't be caught. Add an
  assertion that a deleted day's assignments are excluded from reads. Low-risk; rides the next planning touch.
  *(seam: planning delete spec / assignment soft-delete reconcile)*

## Pending (filed by /do-retro 2026-06-06, J-5 window)

- ~~**Scope the per-push `alpenflight-mock-e2e` gate to the journey-under-work (dev-time test strategy).**~~ **Shipped J-6 T-02b.** Mirrored J-5 T-14's real-idp scoping for the mock half: a new `mock_test:` journey-frontmatter field + a `changes`-job "Derive journey mock-e2e filter" step derives the journey-under-work's `tests/<feature>/` filter off the integration branch; the `alpenflight-mock-e2e` "Run Playwright" step passes it to `--project=chromium`, so per-push runs ONLY that journey's own mock specs (J-6 → its 11 `tests/planning/` specs, verified via `--list`). The J-5-articles-crud hostage case can no longer red an unrelated journey's `required`. FAIL-SAFE: a non-integration branch / no `mock_test:` frontmatter / underivable filter → `mock_is_full=true` → the FULL chromium suite runs (pre-T-02b baseline), never a no-spec run. Full cross-journey mock regression stays nightly (`alpenflight-e2e.yml` main-push) + the §4 do-ship gate. actionlint-clean. `required` aggregator unchanged (skipped→success). *(seam: ci.yml mock-e2e spec selection + journey `mock_test:` frontmatter)* [[feedback_dev_time_test_strategy]]
- **CI fail-aggregate (surface ALL reds in one run).** ci.yml stops at the first failing layer (build → server-test → web-lint → mock-e2e discovered serially across cycles). Run the independent checks as parallel jobs that all report, so one run shows every red at once. *(seam: ci.yml job parallelism/aggregation)*
- **Assert the per-journey gallery shots are PRESENT, don’t tolerate absence.** `add_shot` silently skips a missing PNG + the deployed-link-check only validates DECLARED shots — a future partial-red capture could drop shots while the gate stays green. Add a guard asserting each journey’s expected paired shots exist before deploy. *(seam: alpenflight-proof-fanout.yml add_shot presence guard)*
- ~~**Cheap early mapper-binding check for migration journeys.**~~ **Shipped J-6 T-12.** Generic, registry-wide build-time contract test `MapperBindingContractTest` in `migration-bundle` (runs in `check`): for EVERY `KnownMappers` mapper it asserts (1) binding-presence — the `EntityType` HAS a `MapperLegacyBindings` entry OR is in an explicit `KNOWN_UNBOUND` pending-set (PlanningDay trio listed there until T-11 wires them; a NEW unbound mapper not allowlisted → RED, the J-5 T-07 zero-binding class), with a hygiene test that `KNOWN_UNBOUND` shrinks (a now-bound entry left in the set → RED) and is honest (orphan entries → RED); (2) producer-SELECT ↔ mapper-reads coherence — every legacy column the mapper's `writeNdjson` reads (`source.getXxx("…")`, source-parsed) is projected by the bound SELECT, else export-abort/silent-NULL; (3) FULL_PORT carries a consumer INSERT targeting its table (SYSTEM_GLOBAL empty by contract); (4) bound mapper declares ≥1 column. **Static-only residual deferred to the real fanout** ([[project_synth_bundle_doesnt_validate_producer_select]]): whether a SELECTed column EXISTS in the live MSSQL FLSTest schema, and type-fidelity coercions — this test proves SELECT and mapper AGREE, not that either matches the real legacy DDL. *(seam: migration-bundle MapperLegacyBindings contract test)* [[verify_infra_is_run_not_just_authored]]

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

- ~~**Commit `alpenflight/web/.fallowrc.json` so the score is honest (operator: commit config).** Add
  `ignorePatterns: ["**/node_modules.windows/**", "src/app/api/generated/**", "dist/**", "coverage/**"]`
  + `health.ignore: ["src/app/api/generated/**", "**/*.spec.ts", "e2e/**"]`.~~ **Shipped J-5 T-12.**
  Committed `.fallowrc.json` (fallow loads it; score now **B (71.1)** vs the misleading 52 D — confirmed via
  `fallow health --format badge --score` + `--format json`). Plus the CI report-emit (the T-13 panel feed):
  fail-soft (`continue-on-error`) steps in both `ci.yml` + `alpenflight-proof-fanout.yml` emit the FE journey
  delta (`fallow audit --base origin/main --format json` — changed-files envelope w/ `introduced` attribution),
  the FE repo snapshot (`fallow health --format json`), and the BE PMD/CPD XML (`:pmdMain :cpdCheck`) to the
  stable T-13-consumable paths `public/alpenflight/proof/maintainability/{fallow-audit.json,fallow-health.json,pmd-main.xml,cpd-check.xml}`.
  *(seam: new `alpenflight/web/.fallowrc.json` + ci/fanout emit steps)* — see [[reference_fallow_maintainability_analyzer]].

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

- ~~**Java maintainability tool for `alpenflight/server` — add PMD + CPD (+ SpotBugs) (operator: "similar
  tool for Java").**~~ **Shipped J-5 T-11.** Added the gradle built-in **PMD** (7.25.0 — 7.7.0 hit a
  type-resolution StackOverflow on this codebase) with a **curated** `config/pmd/ruleset.xml` (complexity:
  Cyclomatic/Cognitive/NPath/NcssCount/ExcessiveParameterList/TooManyMethods/TooManyFields + dead/unused
  code: UnusedPrivate{Field,Method}/UnusedLocalVariable/UnusedAssignment/UnusedFormalParameter/Empty* — NO
  style/naming/doc noise) and **CPD** (`de.aaschmid.cpd` 3.5, `cpdCheck`, minTokenCount=50). Both wired into
  `check` as **report-generating, NOT hard-failing**: `pmdMain.ignoreFailures=true`; CPD duplication gated by
  a **ratchet** (`cpdRatchet` task vs `config/pmd/cpd-baseline.txt` = 5300 tokens) that fails ONLY on growth
  (verified: passes at 5300==baseline, reds when baseline lowered). Measured server-main: PMD **65 violations**
  (34 cyclomatic / 15 excessive-params / 5 cognitive / 4 NPath / 4 too-many-fields / 3 too-many-methods;
  **0 dead-code** — a clean signal), CPD **2.46%** dup (5300 tokens / 858 lines / 65 blocks / 34,769 LOC —
  lower than the 5.57% jscpd proxy; CPD's token model at minTokens=50 is stricter). Reservation aggregate
  (`ch.alpenflight.reservations`, T-03/T-09) confirmed clean: 2 benign PMD hits (class-sum cyc 62 but max
  method cyc 9 < 10; 11-param factory) + 9 small DTO/exception boilerplate clones (no logic dup). Reports →
  `build/reports/pmd/main.{xml,html}` + `build/reports/cpd/cpdCheck.xml` (T-12/T-13 panel feed). SpotBugs
  deferred (operator scope was complexity+dup+dead-code; PMD covers dead code). — see
  [[reference_fallow_maintainability_analyzer]].

- ~~**Add a per-journey Maintainability panel to each proof-gallery journey page (operator: "add the reports
  to the proof gallery for each journey page").**~~ **Shipped J-6 T-14.** Each per-journey page now carries a
  **Maintainability** section (`renderMaintainabilityPanel` in `generate-gallery.mjs`) rendering the journey's
  *delta* + repo snapshot across the three axes: **frontend** via fallow's changed-files audit envelope
  (`fallow audit --base origin/main` → `attribution.{complexity,duplication,dead_code}_introduced` + `verdict`)
  + the repo snapshot (`fallow health` → MI, dup%, dead-file%); **backend** via the PMD (complexity + dead-code
  violation counts by rule) and CPD (duplicated-token % + clone-group count) XML. Green/amber/red roll-up pill
  driven by the FE delta (green = no new findings, amber = introduced >0, red = fail verdict); a non-journey-
  under-work page shows the snapshot only (no false historical delta); absent artifacts degrade to "no data"
  (fail-soft, never a crash/dead link). The panel renderer + parsers + the T-12 CI emit steps landed under the
  J-5 carve (commit history); T-14 closed the wiring gap so the journey page reads its DELTA not "snapshot
  only" — the per-push `ci.yml` + fanout gallery steps now pass `--journey-under-work` (the generator's
  branch-name fallback can't derive the journey from a PR merge ref). A "Full maintainability reports →" link
  targets the `maintainability/` dir (which carries an `index.html` so the dir URL serves 200 on gh-pages).
  *(seam: `generate-gallery.mjs` maintainability panel + ci.yml/fanout `--journey-under-work` wiring + the T-12
  fallow/pmd/cpd report-emit steps)* — see [[feedback_proof_gallery_per_journey_one_bookmark]],
  [[feedback_maintainability_includes_dupes_and_deadcode]], [[reference_fallow_maintainability_analyzer]].

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

- ~~**Scope the clean-seed `alpenflight-proof` job to the journey-under-work's spec (operator ask, J-3
  retro).**~~ **Shipped J-5 T-14.** The per-push `ci.yml` `alpenflight-proof` job no longer hardcodes the
  J-0 Locations spec — a `changes`-job step DERIVES the journey-under-work's real-idp spec off the
  integration branch (`integration/J-NNN` → the journey file's `parity_test:` frontmatter first token,
  normalized relative to `alpenflight/web/`) and the proof job runs ONLY that single spec
  (`--project=real-idp`). FAIL-SAFE to the J-0 Locations baseline for a mock-auth parity journey (J-5's
  spec runs in `alpenflight-mock-e2e`), a showcase-seeded `tests/profile/` spec (J-4, own gating job), a
  non-integration branch, or any underivable case — never a no-spec run. The J-0-caption live-link-check
  is gated on the baseline having run, so a journey-specific run can't false-red. The **full
  cross-journey regression** already lives nightly in `alpenflight-e2e-real-idp.yml` (J-4 T-21/T-22 moved
  it there; J-5 T-14 reconfirmed + documented it) + runs once at the §4 do-ship gate — never per-push.
  `required` aggregator unchanged. Pairs with the [[J-1 aircraft flake]] rider (T-15). *(seam: ci.yml
  alpenflight-proof spec selection + the nightly full-suite trigger)*

## Pending (filed by /do-ship 2026-06-05, J-3 window)

- ~~**J-1 aircraft real-idp spec flake (S-163 timeout → retry → create-residue → 6≠3).**~~ **Shipped J-5
  T-15.** Root cause: club A in `aircraft-migration-parity.spec.ts` is the shared, never-truncated Flyway
  `seed-club-1` (two-club-fixture.ts:46), so a failed attempt's 3 created rows linger and the next attempt's
  absolute `toHaveCount(3)` (`:228`) saw 6. Fixed BOTH halves: (a) retry-isolation — an `afterAll` DELETEs
  every aircraft this group created as the managing-club admin (clean tenant for the next retry) + a
  `beforeAll` id-list reset; AND (b) the absolute count → a DELTA (`baseline + 3` + each created row visible
  by id), the residue-proof fallback. S-163 45s timeout root cause: the test does in-body fixture-STATE setup
  — `seedAircraftOwnerLink` shells out to Gradle (`gradlew seedAircraftOwnerLink`, aircraft-parity-fixture.ts:412)
  BEFORE any assertion, ~15-35s on a cold CI daemon, consuming the 45s per-test budget → vague timeout → retry
  → residue. Bumped THIS test only to `test.setTimeout(90_000)` with the measured rationale (global 45s stays
  for the other tests; per-assertion 5s expect unchanged). CI MUST CONFIRM S-163 no longer times out under
  load; else attack the Gradle seeder cost (warm daemon / pre-seed in beforeAll). Local Playwright unrunnable
  (chrome musl + needs real-idp stack) — reasoned from code + Playwright serial-retry semantics. *(seam:
  aircraft-migration-parity.spec.ts retry-isolation + S-163 timeout)*
- **PILOT flights-read authz gap — FIXED in J-3, lesson for /do-retro.** `FlightsController.list/get`
  was `@PreAuthorize(hasAnyRole('CLUB_ADMINISTRATOR','FLIGHT_OPERATOR'))` (S-159, predating the pilot
  dashboard) → a PILOT got 403 reading their OWN last flight; the pilot dashboard card hung. Fixed in
  J-3 (PILOT granted tenant-scoped read on list+get + StartStore catchError). **Not a pending rider**
  (shipped) — recorded here as the /do-retro lesson: the **mock-auth** suite's admin principal HID this
  authz gap; only the **real-idp showcase run with a real PILOT principal** surfaced it. Real-roles
  end-to-end catches authz gaps mock-auth can't. *(retro lesson, not a code rider)*
