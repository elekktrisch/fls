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

## Pending (filed by form-validation parity audit 2026-06-09)

Full analysis + per-form verified gaps: `docs/modernization/form-validation-parity-audit.md`
(ultracode sweep — 12 forms, legacy-oracle → parity-review → gap-hunter verify). Operator bar:
legacy = minimum; **all** validations as-you-type (debounced ~200ms); server-on-submit stays the
safety step. **Each rider rides the next touch of its form.** The as-you-type batch (P2/P3) all
reuse the J-6b `liveFieldErrors` infra (`shared/util/form/inline-validation.ts:120`) — fold them
into one as-you-type sweep when a form-heavy journey lands. (Operator chose riders for ALL of these,
incl. the P0 safety items — 2026-06-09.)

**P0 — safety / data-loss (below the legacy bar; each needs server + store + e2e):**
- **Person edit silently DROPS all membership edits on update (data loss).** The `/edit` form
  hydrates + lets you toggle memberNumber / memberStateId / isGlider|Motor|TowPilot, Save toasts
  success, but `PersonUpdateRequest` omits them and `PUT /persons/{id}/clubs/current` (which EXISTS,
  `PersonsController.java:139-144`) is never called. Wire the membership update in `persons.store.ts`
  update (fields hydrated at `persons-edit.page.ts:387-400`, request omits at `:375-382`) + add an
  edit-path e2e asserting role/memberNumber round-trip (only `persons-add-modal.spec.ts` exists, create-only).
  *(seam: persons-edit update path + persons.store)*
- **Flight-type FlightCode duplicate → raw 500** (reproduces the legacy bug). Add an
  `@ExceptionHandler(DataIntegrityViolationException)` to `FlightTypesExceptionHandler` discriminating
  `ux_flight_type_club_code` → 409 `field=flightCode` (mirror `LocationsExceptionHandler.java:83`) +
  a `findActiveByCode` pre-check (`FlightTypesService.java:70,100,154`). **Coupled:** the store/effect
  must route the 409 on the problem-detail `field` (name vs code) — today `flight-types.store.ts:229-236`
  + `flight-types-edit.page.ts:344-350` send EVERY 409 to `flightTypeName`. *(seam: FlightTypesExceptionHandler + service + store 409 discrimination)*
- **Flight-type Instructor × Observer mutual-exclusion enforced at NO layer** (legacy `CHECK` forbids
  `(1,1)`). Add a cross-field validator (`flight-types-edit.page.ts:300-301`) **and** a domain XOR guard
  in `FlightType.updateFlags()` (`FlightType.java:158-180`) — domain is the must-have (ADR-0022 §2);
  optional DB CHECK (`V3:255-289`). *(seam: flight-type XOR — client + domain)*
- **Club duplicate clubKey → 409 MISLABELED as a slug error on the wrong field.** `ClubsService.persist()`
  (`:164-169`) maps *any* `DataIntegrityViolationException` → `SlugAlreadyExistsException`; distinguish
  `ux_club_key` vs `ux_club_slug` before throwing → clean clubKey 409 (also fixes the latent "any future
  t_club constraint → mislabeled slug" trap). *(seam: ClubsService DIVE discrimination)*

**P1 — client-parity regression / dead code:**
- **Profile Account `languageId` lost its required validator** (legacy `profile.html:61` had it; only the
  server `@NotNull` enforces now). Add `Validators.required` (`profile-account.tab.ts:174`). *(seam: profile-account tab)*
- ~~**Flight edit: dead `FlightValidator` + zero client validation.**~~ **SHIPPED J-26 T-13.** Client fix
  landed: `Validators.required` on flightDate + glider aircraft/pilot (tow stays unvalidated — conditional step)
  + `[required]`/`[errors]` (J-6b `liveFieldErrors`) + Save gated on a reactive `formStatus` signal.
  **FlightValidator VERDICT = KEEP** (not wired into create/update, not deleted): its rules are the nightly
  Validate-job verdict (NOT a create/update gate — legacy saves incomplete flights + flags them nightly;
  wiring would 400 partial saves the screen accepts), and it is the named still-pending dependency of **S-083**
  (DailyFlightValidationJob, "reuses it") + S-101 (validation-rejection depth), covered by FlightValidatorTest +
  FlightCompositeValidatorTest. Deleting it would discard the ported legacy rule set + force a re-port at S-083.
  *(was: flights validator wiring + flight-form client validators)*

**P2 — as-you-type sweep (mechanical, one shared infra; the systemic J-6b-bar miss):**
- The J-6b as-you-type bar is wired ONLY on reservation-edit + planning-edit. **Replace `ctl.touched ?
  ctl.errors : null` with debounced `liveFieldErrors` on every other form:** aircraft
  (`aircraft-edit.page.ts:157-194,252,333-352`), article (`articles-edit.page.ts:96-113`), club
  (`clubs-edit.page.ts:83-133`), flight-type (`flight-types-edit.page.ts:109-135`), location
  (`locations-edit.page.ts:130-206`+IOP `:265-292`), person (`persons-edit.page.ts:104-131`), planning-setup
  (`planning-setup.page.ts:130-157`), user (`users-edit.page.ts:141-175` + roles-≥1 live `:351-354`),
  profile 4 tabs (`profile-account.tab.ts:88-104`, `profile-personal.tab.ts:106-215`, `profile-pilot.tab.ts:188-190`).
  *(seam: per-form `liveFieldErrors` adoption — fold into one sweep)*

**P3 — missing `[errors]` bindings (PREREQUISITE for P2 on these fields — validator present but never renders
inline at all, even on submit; `af-form-field` defaults `errors` to null):**
- Bind `[errors]` on the `af-form-field` for the ~30 silent fields: aircraft 7 (`:206-227,315-329,369-401`),
  article articleInfo (`:123-130`), flight-type FlightCode (`:120-127`), location IOP rows (`:265-292`),
  person city/mobile/memberNumber (`:142,151,160`), user phone/remarks (`:189-200`), profile 9
  (`profile-account.tab.ts:116-123`, `profile-personal.tab.ts:116-196`). *(seam: af-form-field [errors] bindings)*

**P4 — server-roundtrip as-you-type pre-checks (submit-time 409 already CONFIRMED safe — UX only):**
- Add a non-mutating `…/validate` endpoint + debounced store rxMethod (model on reservation overlap
  `AircraftReservationsService.java:229-244`) + merge via `asyncErrors$`/`mergeFieldErrors`
  (`inline-validation.ts:56,67`) for: aircraft immatriculation, article articleNumber, location ICAO,
  user username. *(seam: per-aggregate /validate endpoint + store)*

**P5 — declined better-than-legacy / cosmetic (low):**
- Planning-setup: client `start ≤ end` + `≥1 weekday` cross-field validators + error region
  (`planning-setup.page.ts:170-191,242-254`); planning info `maxLength(4000)` client-side
  (`planning-edit.page.ts:376`). **Transloco-translate `af-field-errors`** — it renders the i18n KEY
  verbatim (`common.errors.required`), no `t()` pipe (`af-field-errors.component.ts:12-13`). DIVE→400
  handlers for reservation/planning FK→500 (phantom type/location/person ids — parity-met, lowest).
  *(seam: planning-setup validators + af-field-errors transloco + reservation/planning DIVE handlers)*

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
- ~~**Cascade-delete asserted only indirectly (gap-hunter, J-6 T-16).**~~ **Shipped J-6b T-16.** Added
  `PlanningDaysControllerIT.delete_excludesTheDaysAssignmentsFromEveryRead`: a day with 3 crew (→ 3
  `t_planning_day_assignment` rows) is soft-deleted, then the IT asserts the read layer ALREADY excludes
  them — GET → 404, the `overview/future` list omits the day, and re-creating the same (club,date,location)
  yields a FRESH day owning its own 3 assignments. Confirmed NO leak (the rider's predicted bug did not
  materialise): every read query filters `deleted_on IS NULL` at the PARENT (`findActiveById` /
  `FUTURE_SELECT`), so a soft-deleted day is never loaded and its children — which physically remain
  (`countAssignments(id)==3` post-delete, since soft-delete means the V4 `ON DELETE CASCADE` never fires) —
  are unreachable through any read path. Test-only; `./gradlew check` green.

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

## Pending (filed by /do-ship 2026-06-09, J-7 gate)

- **[NEXT-JOURNEY PRIORITY — retro 2026-06-12]** **Proof job doesn't upload per-test `test-results/**` (error-context.md + trace.zip) on failure** — a red
  real-idp/fanout spec references its `error-context.md`/`trace.zip` but only `proof-manifest.json` survives
  (the proof-gallery step overwrites the dir), so a gate red can't be diagnosed from the DOM snapshot/trace —
  forcing source+log+architecture reasoning instead (J-7 T-19 hit this on BOTH the tenant-isolation + reservation
  reds). Upload `test-results/**` as a separate failure artifact BEFORE the gallery step mutates the dir. *(seam:
  ci.yml alpenflight-proof + fanout test-results upload-artifact on failure, pre-gallery)* [[feedback_surface_proof_early_on_repeated_failure]]
- **Reservation Save enabled while form invalid (async validator race, J-7 T-20 observation).** The
  second-crew-required validator flips AFTER the aircraft picker resolves `nrOfSeats`, so `reservation-save-button`
  shows enabled (`saveDisabled:false`) for a beat while `form.invalid` is still true; clicking then early-returns
  in `onSubmit` with no user feedback beyond `markAllAsTouched`. Not happy-path-blocking once crew is supplied, but
  the button-disable binding and validator state momentarily disagree. *(seam: reservation-edit Save disable binding vs async second-crew validator)*
- **planning fixture club-B KC provisioning `beforeAll` can 45s-timeout under contention (J-7 T-20).** Did not
  reproduce on re-run (fanout `retries:1` absorbs it); if it recurs, bump that fixture's provisioning timeout
  or warm the KC admin client. *(seam: planning-migration-parity beforeAll club-B provisioning timeout)*
- **Planning `:410` edit-crew cold-`page.goto` reopen flakes on OIDC reboot/renew stall (J-7 T-21).** The
  reopen via `page.goto('/planning/{id}/edit')` hits the documented cold lazy-chunk/OIDC-renew stall
  ([[project_real_idp_goto_reboot_renew_stall]]); self-heals warm + CI `retries:1`. Switch that reopen to warm
  in-app nav to harden. *(seam: planning-migration-parity :410 reopen → warm nav)*

## Pending (filed by /do-ship 2026-06-10, J-7 gate — STRUCTURAL gallery)

- **[NEXT-JOURNEY PRIORITY — retro 2026-06-12]** **Legacy-side parity shot renders "pending" though the PNG is produced + staged (staged-≠-rendered drift, J-7 T-22).**
  The fanout legacy capture (`reporting-parity-J7.spec.ts`) PASSES and produces `legacy-flightreports-{picker,result,custom}.png`
  + `legacy-reporting-parity-J7.webm` (confirmed in the run artifact under both `/tmp/fls-e2e-results/...` and the staged
  `public/alpenflight/proof/screenshots/`), the J-7 `add_shot` calls for BOTH sides exist (fanout ~1091-1101), and the
  shots-present guard ENFORCES the legacy three (passes) — yet the DEPLOYED J-7 page renders only the **alpenflight** side of
  each of the 3 shot-pairs, legacy side = "pending". The fanout deployed LAST (07:03 vs CI 06:56), so it's NOT a deploy race —
  the fanout's OWN `generate-gallery.mjs` run didn't emit/match the legacy J-7 `screenshots.json` entries the pairing reads.
  Root cause is in the fanout `add_shot`→`screenshots.json`→generator pairing path (the guard checks PNG presence while the
  generator pairs `screenshots.json` entries — the two disagree). **STRUCTURAL fix** (the operator's recurring gallery-plumbing
  class — ride a journey's tech-debt budget, possibly the gallery re-arch): make the shots-present guard and the generator read
  the SAME source of truth so "present" == "rendered", and assert the legacy SIDE of each declared pair actually renders on the
  deployed page (extend the deployed-journey guard to check both sides of a pair, not just that the page is linked). *(seam:
  alpenflight-proof-fanout.yml add_shot json emission + generate-gallery.mjs pair render + deployed-journey guard both-sides)*
  [[feedback_surface_proof_early_on_repeated_failure]] [[feedback_proof_gallery_per_journey_one_bookmark]]

## Pending (filed by PR #215 review, 2026-06-10 — ADR 0027 JPA-first / no-JDBC)

- ~~**Convert the flight-report read path to a domain-maintained read-model**~~ — **SHIPPED 2026-06-11** (stacked
  PR #217 → integration/J-7, RM-1..RM-5: read-model + same-tx sync + rebuild at all bypass seams + rename
  propagation + JPA read path; 414-line native-SQL class deleted; register entry retired; cross-club location
  decoration recorded as intentional divergence ADR 0026 D-2). Original scope (kept for trace): Replace `JpaFlightReportRepository`'s native SQL with redundant report entities written at mutation
  time by separate aggregates via application events (same-transaction, NO db triggers), queried with plain JPA
  finds; sync integration-tested (mutate via production path → assert read-model row). Needs: backfill for
  migration-bundle-ingested flights (ingest must populate the read-model too, or a backfill job), and a design pass
  on decoration-rename propagation (immatriculation / person-name / location-name changes must update report rows).
  Retires the `flight-report-read-model` native-sql-register entry. **Pulled forward by operator decision
  (2026-06-10 PR #215 review): in work on stacked branch `integration/J-7-jpa-readmodel` (base `integration/J-7`),
  merging back into the J-7 PR — #215 stays draft until main receives no new JDBC.** *(seam: flights read path +
  migrations ingest + register)*
- **Retire the remaining main-code JDBC/native sites per-module on next touch (ADR 0027 §1).** 14 main-source files
  at filing time; structurally-pre-tenant seams stay register-listed (`UserPrincipalLookup`, `PreTenantUserLookup`,
  `ReferenceDataSeeder`, `MutationAuditEventListener` system-actor write). Convert-on-touch candidates (`MeService` DONE in RM-4, incl. its `JpaUserRepository.languageExists`
  native→JPQL boyscout): `JpaUserRepository` (remaining native), 
  `JpaPersonRepository`, `JpaClubStateRepository`, `JpaCountryRepository`, `PlanningDayPersistenceProbeImpl`,
  `AircraftReservationConflictProbeImpl`, `ShowcaseSeeder`, `LanguageCodeLookup`. *(seam: per-module infra layer)*
- **IT seeding: raw-JDBC → production-code per-touch (ADR 0027 §3).** ~85 ITs (incl. `TenantScopedRowBuilders` /
  `TwoClubFixture` consumers) seed via `JdbcTemplate`; convert each file the next time it's materially edited —
  convention, NOT a sweep story. J-7's own two ITs converted in PR #215 as the pattern reference. ADR 0021
  isolation rules unchanged. **Same per-touch convention now also covers club-id collisions:** single-schema
  external-PG runs (RM-2a) surfaced classes sharing club UUID literals by value with club-HARD-DELETING classes
  (LocationsAuthorizationIT pair fixed RM-5; 4 showcase-CLUB_2 squatters fixed RM-5; audit found latent pairs left:
  the migration round-trip family's bundle clubs `…04be`/`…0bb8` are also referenced by Audit*/Clubs* ITs — give a
  class ITS OWN club ids when touching it; production-reserved ids (ShowcaseSeeder, V-seeds) are off-limits as
  foreign fixture clubs). *(seam: server src/test, per-touch)*
- **Lifecycle-boilerplate @MappedSuperclass — revisit the declined abstraction (CPD trio, J-7 RM-2).** Five
  aggregates now share the byte-identical softDelete(userId, clock) + @DomainEvents emit-on-save one-liner
  shape (Flight, Aircraft, Person, Location, FlightType); the cpd-baseline has declined a @MappedSuperclass
  three times while the clone count grew. Next server-side journey: either extract the lifecycle base
  (soft-delete fields + saved-event hook) or write down the final verdict in an ADR so the ratchet file
  stops re-litigating it. *(seam: domain aggregates' lifecycle block + cpd-baseline.txt)*
- **Fanout has NO reporting spec over MIGRATED data (predates the read-model conversion; found at RM-5).** The
  fanout's AlpenFlight parity step runs J-0c/J-1/J-2/J-5/J-6 migration-parity specs — `/flightreports` over the
  migrated dataset has never been e2e-asserted (the J-7 reporting specs run against the CLEAN seed in ci.yml; the
  fanout only captures the LEGACY reporting side). Server-side the seam IS covered (RM-2 ingest-rebuild ITs assert
  read-model rows + decorations post-ingest). Next reporting touch: add a small AlpenFlight-side
  `reporting-migration-parity` assertion (open /flightreports as the migrated club, summary+rows non-empty,
  location names render) to the fanout spec list. Also fix the step's stale name (it predates J-5/J-6 too).
  *(seam: alpenflight-proof-fanout.yml parity-spec step + e2e/tests/real-idp)*
