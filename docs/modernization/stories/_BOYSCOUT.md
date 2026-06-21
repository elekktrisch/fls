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

## Pending (filed by /do-retro J-27 window, 2026-06-20)

- ✅ **[#229 — New flight form broken] — SHIPPED in J-2b.** Not a save bug: today-default list visibility +
  Option B post-save jump, valid-on-load via `revalidateTree()` + the `liveFieldErrors` construction-snapshot
  fix, legacy-parity Save-gating, filter-aware empty state.

## Pending (filed by /do-ship J-27 gate, 2026-06-20)

- ✅ **[FLIGHT-FIDELITY — verify §5 / HB-3256 flight migration] — SHIPPED in J-2b (T-06).** Mapper round-trips
  flight-type (scalar FK) + crew (one `FlightCrew` row/role); `delivery-creation-test-parity` green for the right
  reason. §5-absent = fixture IF-guard data-condition; HB-3407/30 = transient fanout-snapshot misread. Fast
  round-trip ITs added in `migration-bundle:check`; real fanout-parity green on the J-2b branch.
- **[SUITE-ISOLATION — operator principle 2026-06-19].** Non-migration parity specs should set up their own data;
  migration specs run FIRST and rely ONLY on legacy seed data (assert what it genuinely produces, never gerrymander
  the seed). J-27 applied this to `:577`; the broader suite restructure (audit the other parity specs for hand-crafted
  `_test-fixture.sql` dependencies) rides a future test-architecture slot. *(seam: e2e/tests/real-idp parity specs + `_test-fixture.sql` §4/§5 hand-crafted rows)*

## Pending (filed by /do-ship J-2b gate, 2026-06-20)

- **[FRAME-ANCESTORS-HEADER] `frame-ancestors` removed from the `index.html` meta CSP** (browser-ignored when
  delivered via `<meta>` — emits a console error every page load, caught by J-2c's §4 zero-console-error guard).
  Real clickjacking protection (`frame-ancestors` / `X-Frame-Options`) must be a **response header** at the
  S-041 reverse proxy / static host, not a meta tag. *(seam: S-041 production CSP response header)*
- ✅ **[AEROTOW-SELECT-FLAKE] — SHIPPED in J-2c (T-05).** The clean-seed AEROTOW flow (and the sibling motor
  flow) in `flight-migration-parity.spec.ts` now pass the `'J2 Airfield'` search term to the
  `flight-edit-startLocation` select (mirroring `createFullyPopulatedGliderFlight`), so the virtualised option
  renders deterministically instead of flaking under RAM pressure.

## Pending (filed by /do-retro 2026-06-14, J-7/J-26/J-8 window — operator debt-burndown)

These four are the ≤70% burndown spike (`/do-plan` marker). Each rides a journey LEADING with a real
feature; clear them over the next ~2-3 journeys, then revert the budget to ≤40%.

- **[GALLERY-SIMPLIFY] One gallery page, current journey only.** Collapse the proof gallery to a single
  stable-bookmark page rendering ONLY the in-flight journey — paired legacy↔AlpenFlight list+form +
  pass video + migration round-trip. **Delete:** the all-journeys index (`generate-previews-index.mjs`),
  per-merged-journey history pages, the per-push/fanout/legacy-parity sub-path split, the per-journey
  staging blocks + multi-context `producedBy` shots logic. Keep one deploy + the deployed-link-check
  (with CDN-propagation slack). Merged journeys' proof lives in their PRs (history is in git). **This
  SUPERSEDES every prior gallery rider** (the 4→1 / 2→1 collapses, the proof-index-regression patches,
  the structural post-deploy guard, the paired-shots-per-push, the shots-present guard — all fold here).
  *(seam: `generate-gallery.mjs` + delete `generate-previews-index.mjs` + `proof-gallery-links.spec.ts`
  + `expected-shots.json` + the gallery deploy/staging steps across `ci.yml` + `alpenflight-proof-fanout.yml`)*
  [[feedback_proof_gallery_per_journey_one_bookmark]] [[feedback_surface_proof_early_on_repeated_failure]]
- **[WORKFLOW-SLIM] Cut the YAML ~4.5k→~2k + speed the gate (keep the 5-min mock-suite ceiling).** Extract
  the repeated per-journey blocks into composite actions (`.github/actions/`); shard the cross-journey
  real-idp at the §4 gate (keep the coverage — operator decision — just parallelize); **quarantine the 3
  KC-26 specs** so their retries stop blowing the 15-min step timeout; the GALLERY-SIMPLIFY cut removes the
  staging blocks. **Folds the J-8 proof-harness-transient rider** (the `[deployed-journey]` 60s gh-pages wall
  + the KC-26 step-timeout). **Root cause already found (J-9 carve, 2026-06-14):** Playwright `workers` is a
  TOP-LEVEL option, so the per-project `workers: 4` in `playwright.config.ts` is a **silent no-op** (same
  trap as the removed per-project `maxFailures`) — the mock suite ran 2 workers and timed out at the 5-min
  cap since J-26. Stopgap PR #222 forces `--workers=4` on the chromium CLI invocation (keeps the ceiling).
  **VERIFIED 2026-06-14: parallelism ALONE is insufficient** — a dispatch with `--workers=4` ran 4 workers
  but STILL timed out at 5 min (157 tests on a 4-core runner). ~~So WORKFLOW-SLIM must **SHARD the mock suite
  into parallel sub-5-min CI jobs** (a `--shard=i/n` matrix + `reporter: blob` + a `merge-reports` deploy job
  — each shard keeps the 5-min ceiling)~~ **SHARDING SHIPPED J-27 T-12** (operator pulled it forward 2026-06-20):
  `ci.yml` `alpenflight-mock-e2e` is a `--shard=i/4` matrix (`reporter: blob`, `--workers=4`) + an authoritative
  `alpenflight-mock-e2e-merge` job (`merge-reports` + all-shards-ran guard) that `required` now needs; `workers`
  was already top-level (J-9 #222), real-idp invocations all carry `--workers=1`. **One-time branch-protection
  rename needed:** required check `alpenflight mock-auth e2e (Run Playwright)` → `alpenflight mock-auth e2e (merge
  shards)`. STILL PENDING for J-11: composite-action extraction, the YAML cut, the real-idp shard, the KC-26
  quarantine, AND **HELPER-PRUNE** the redundant specs. The 12-min control run proved the suite is green-but-slow
  (157 passed). The operator's bar: **5 min is the ceiling — shard/prune/parallelize, never buy wall time.**
  *(seam: `ci.yml` 2472L + `alpenflight-proof-fanout.yml` 1586L + `alpenflight-e2e-real-idp.yml` + `alpenflight-e2e.yml` + `playwright.config.ts` workers + new composites)*
- **[COMMENT-STRIP] Self-explanatory code, why-only comments.** Remove all what/narration/history/
  task-attribution comments (`T-NN:`/`J-NNN`/"legacy stored…"/"this masks the race…"/non-load-bearing
  migration `COMMENT` narration) per the new bar; keep a rare load-bearing *why*, preferred as a named
  symbol / test name / ADR ref. **Per-touch** (the files the next journeys edit); server main is ~28%
  comment lines, so a bounded focused sweep can take a burndown journey's slot. The do-* skills now
  enforce this going-forward. *(seam: per-touch across `alpenflight/{server,web,migration-bundle,migration-tool}`
  + e2e specs + the workflow YAML)* [[feedback_self_explanatory_no_history_comments]]
  **Per-touch progress:** the J-10 delivery/accounting/migration surface is stripped (the `accounting/domain`
  + `accounting/application` + `accounting/web` Delivery files, the Delivery test-support + ITs, the J-10-added
  `MapperLegacyBindings` / `MapperBindingContractTest` / `MapperLegacyBindingsTest` lines). STILL PENDING (its
  own burndown slot — too big for a per-touch fold): the pre-existing cross-journey narration carried in
  `MapperLegacyBindings.java`, `app.routes.ts`, `nav-sections.ts`, and the real-idp `_helpers/fan-out-parity-fixture.ts`.
- **[HISTORY→GIT] Journey/story files contract-only.** Prune journey files to frontmatter + ACs + the
  task checklist + load-bearing decisions + a short Outcome — drop the per-task implementation prose
  (J-7 bloated to 719 lines) + any "Original (for trace)" blocks; that history is in git/commit messages.
  Per-touch (the in-flight + next journeys; don't churn merged ones). The do-* skills now enforce it.
  *(seam: `docs/modernization/stories/*.md` per-touch)* [[feedback_self_explanatory_no_history_comments]]
- **[HELPER-PRUNE] Drop 3 redundant `@helper` e2e cases.** `alpenflight/web/e2e/tests/forms/validation-hardening.spec.ts`
  carries 3 `@helper`-tagged logic/error cases whose cheaper backend twins exist + own the logic:
  dup-FlightCode 409 (`covered-by: FlightTypeDuplicateCodeIT`), dup-clubKey (`ClubsControllerIT`),
  Instructor×Observer XOR (`FlightTypeDomainTest`) — all three classes verified present. Delete those 3
  e2e cases (keep the wiring/happy-path cases); the IT/domain tests cover the logic far cheaper. `/do-ship`
  re-confirms each backend test green before deleting. *(seam: validation-hardening.spec.ts @helper cases)*

## Pending (filed by /do-ship 2026-06-13, J-8 gate)

- **[PROOF-HARNESS TRANSIENTS] two non-blocking run-level reds the J-8 gate surfaced (proof infra, not vertical).** (a) The fanout `[deployed-journey]` link-check (`proof-gallery-links.spec.ts:683`) has a **60s Playwright test timeout** that races gh-pages CDN propagation — the post-deploy check started ~24s after the git-push and timed out before the page propagated (every asset was live moments later, verified by curl). Bump that test's timeout above its internal 60s poll budget (or add a propagation pre-wait). (b) The full real-idp regression hits the workflow's **15-min step timeout** because the 3 KC-26 specs' retries exhaust the wall (`token-lifecycle` is the last file, so nothing J-8-relevant was truncated). Raise the step timeout OR quarantine the 3 KC-26 specs so their retries stop consuming the budget. Both are harness hardening; neither is a J-8 behavior red. *(seam: proof-gallery-links.spec.ts test timeout + alpenflight-e2e-real-idp.yml step timeout / KC-26 quarantine)* [[false_green_derive_fallback]]
- **[TEST-ORPHAN] `alpenflight/web/e2e/tests/nav-bar.spec.ts` is uncollected by every Playwright project** (it sits at `tests/nav-bar.spec.ts` while chromium `testMatch` requires a subdirectory `tests/!(real-idp|profile)/**/*.spec.ts`) — pre-existing since S-097, surfaced by T-22a. Its `/clubs`-top-level + responsive + lang-picker assertions stay valid under the masterdata grouping but never run. Move it into a collected subdir (e.g. `tests/nav/`) on the next web touch. *(seam: e2e nav-bar.spec.ts relocation)*

## Pending (filed by /do-ship 2026-06-13, J-26 gate)

- ~~**[MAINTAINABILITY-TOOLING] Add Qodana (whole-program Java unused-code detection) — operator-approved 2026-06-13.**~~ **SHIPPED J-8 T-15** (report-only): `qodana.yaml` (jvm-community, Spring/JPA-aware profile, server-main scope) + a dedicated `.github/workflows/qodana.yml` `qodana-scan` job (`continue-on-error`, NOT in `required`, `--baseline qodana.sarif.json`) + a `parseQodana` panel row in the gallery maintainability section. **One-time follow-up STILL PENDING:** the committed `qodana.sarif.json` is a PLACEHOLDER empty baseline (the local Docker run OOM-killed on the LXC box); the first CI `qodana-scan` run establishes the real baseline → download its `qodana-sarif-<run_id>` artifact + commit it over the placeholder. *(rides the next journey that touches CI / a maintainability slot)* [[reference_fallow_maintainability_analyzer]]

- **[KC-26 UPGRADE DRIFT] 3 cross-journey real-idp nightly reds (pre-existing, surfaced when J-26 T-03 re-enabled the 12-day-dead nightly).** NOT J-26's vertical (validation/JDBC) — KC-26-upgrade reconciliation that needs iterative live-stack debugging; J-26's OWN real-idp proof is green. T-30a/d authored first fixes that the gate proved insufficient: (1) `login.spec.ts:92` `?ui_locales=fr` → `<html lang="fr">` still renders `en` (KC 26 honors the param differently / login-theme or realm i18n-resolver — needs live-KC iteration, possibly a realm/theme config change); (2) `register.spec.ts:49` KC→Mailpit verify-mail never arrives (T-30d added a fail-loud SMTP preflight + 45s timeout — next dispatch's preflight output pinpoints DNS/SMTP vs KC-not-sending); (3) `token-lifecycle.spec.ts:47` silent-refresh still red after the wait-hardening (likely real KC-26 refresh-grant/SSO behavior, not timing). Each fix → ~25-min nightly dispatch → observe → repeat. Ride the next journey's gate (or a focused KC-26-reconciliation slice). *(seam: realm-export.json i18n/SMTP + login/token real-idp specs + KC 26 OIDC behavior)* [[project_real_idp_real_roles_catches_authz_gaps]]

## Pending (filed by form-validation parity audit 2026-06-09)

Full analysis + per-form verified gaps: `docs/modernization/form-validation-parity-audit.md`
(ultracode sweep — 12 forms, legacy-oracle → parity-review → gap-hunter verify). Operator bar:
legacy = minimum; **all** validations as-you-type (debounced ~200ms); server-on-submit stays the
safety step. **Each rider rides the next touch of its form.** The as-you-type batch (P2/P3) all
reuse the J-6b `liveFieldErrors` infra (`shared/util/form/inline-validation.ts:120`) — fold them
into one as-you-type sweep when a form-heavy journey lands. (Operator chose riders for ALL of these,
incl. the P0 safety items — 2026-06-09.)

**P0 — safety / data-loss (below the legacy bar; each needs server + store + e2e):**
- ~~**Person edit silently DROPS all membership edits on update (data loss).**~~ **SHIPPED J-26 T-04.**
  `persons.store.ts` update takes an optional `membership: PersonClubRequest` and runs the two PUTs
  sequentially (person → `clubs/current`); `person.updated` fires only after BOTH succeed (a failed
  membership half surfaces in `saveError`, no false-success nav). The edit page echoes the
  non-form-exposed flags (full-replace parity). Mock spec `persons/persons-edit-membership.spec.ts`
  (captures both PUT payloads + UI round-trip) + the real-chain twin `real-idp/hardening-J26.spec.ts`
  (T-27: re-open over the real backend). *(was: persons-edit update path + persons.store)*
- ~~**Flight-type FlightCode duplicate → raw 500**~~ **SHIPPED J-26 T-05.** Service pre-check
  (`findActiveByCode`, create + update, self-excluded) throws `DuplicateFlightTypeCodeException` → 409
  `field=flightCode`; the `FlightTypesExceptionHandler` DIVE catch is the `ux_flight_type_club_code` race
  net (other violations stay 500). Store `errorPatch` routes the 409 by problem-detail `field` (name vs
  code no longer collapse onto flightTypeName); the Code field got its `[errors]` binding. IT + mock
  case + the real-chain twin (T-27). *(was: FlightTypesExceptionHandler + service + store 409 discrimination)*
- ~~**Flight-type Instructor × Observer mutual-exclusion enforced at NO layer**~~ **SHIPPED J-26 T-06.**
  Domain XOR guard in `FlightType.updateFlags` (the must-have, ADR-0022 §2; create covered via `register`)
  → `InstructorObserverExclusionException` → 400 `field=isObserverPilotOrInstructorRequired`; client
  `instructorObserverExclusiveValidator` group validator + roles-section inline alert. Domain unit tests
  + client vitest + XOR e2e case. NO DB CHECK (ADR 0022 §2). *(was: flight-type XOR — client + domain)*
- ~~**Club duplicate clubKey → 409 MISLABELED as a slug error on the wrong field.**~~ **SHIPPED J-26 T-07.**
  `ClubsService.persist()` discriminates the violated constraint (`ux_club_key` → `ClubKeyAlreadyExistsException`,
  `ux_club_slug` → `SlugAlreadyExists`, unrecognized → rethrow); `ClubsExceptionHandler` gained the DIVE race
  net for the commit-time flush path (clubKey → 409 `field=clubKey`). Store routes by problem-detail `field`;
  the edit page marks the OFFENDING control. IT (red-first showed a raw 500, not the mislabel) + mock case.
  *(was: ClubsService DIVE discrimination)*

**P1 — client-parity regression / dead code:**
- ~~**Profile Account `languageId` lost its required validator**~~ **SHIPPED J-26 T-08.** Restored
  `Validators.required` on `languageId` + the touched-gated `[errors]` binding + `[allowClear]` on the
  select + Save gated `form.invalid ||`; red-first class-instantiation vitest. Profile-account languageId
  clear→error→Save-disabled→re-pick-recovers e2e case. *(was: profile-account tab)*
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
- ~~The J-6b as-you-type bar is wired ONLY on reservation-edit + planning-edit. Replace `ctl.touched ?
  ctl.errors : null` with debounced `liveFieldErrors` on every other form.~~ **SHIPPED J-26 T-10/T-11/T-12.**
  All forms converted P2+P3 across three sweeps — T-10 aircraft/article/club, T-11 flight-type/location
  (incl. IOP rows)/person, T-12 planning-setup/user (+ roles-≥1 live)/profile 4 tabs. Server-duplicate 409s
  reroute through the `liveFieldErrors` async slot (`asyncErrors$`) so the debounced stream surfaces them
  (a `setErrors` carries no `valueChanges`). Representative trio asserted (aircraft/person/flight-type) +
  §8 component unit specs per sweep. *(was: per-form `liveFieldErrors` adoption — fold into one sweep)*

**P3 — missing `[errors]` bindings (PREREQUISITE for P2 on these fields — validator present but never renders
inline at all, even on submit; `af-form-field` defaults `errors` to null):**
- ~~Bind `[errors]` on the `af-form-field` for the ~30 silent fields.~~ **SHIPPED J-26 T-10/T-11/T-12** (folded
  into the same three as-you-type sweeps): aircraft 7, article articleInfo, flight-type FlightCode, location
  IOP rows, person city/mobile/memberNumber, user phone/remarks, profile 9 — all newly bound + live.
  *(was: af-form-field [errors] bindings)*

**P4 — server-roundtrip as-you-type pre-checks (submit-time 409 already CONFIRMED safe — UX only):**
- Add a non-mutating `…/validate` endpoint + debounced store rxMethod (model on reservation overlap
  `AircraftReservationsService.java:229-244`) + merge via `asyncErrors$`/`mergeFieldErrors`
  (`inline-validation.ts:56,67`) for: aircraft immatriculation, article articleNumber, location ICAO,
  user username. *(seam: per-aggregate /validate endpoint + store)*

**P5 — declined better-than-legacy / cosmetic (low):**
- Planning-setup: client `start ≤ end` + `≥1 weekday` cross-field validators + error region
  (`planning-setup.page.ts:170-191,242-254`); planning info `maxLength(4000)` client-side
  (`planning-edit.page.ts:376`). ~~**Transloco-translate `af-field-errors`** — it renders the i18n KEY
  verbatim (`common.errors.required`), no `t()` pipe.~~ **SHIPPED J-26 T-08** (`af-field-errors` renders
  its mapped keys through an unscoped `*transloco="let t"`; the previously-NONEXISTENT `common.errors.*`
  block — all 8 canonical keys — added to all four locales, red-first vitest resolving every
  `errorTranslationKey` against every locale tree). DIVE→400 handlers for reservation/planning FK→500
  (phantom type/location/person ids — parity-met, lowest, STILL PENDING).
  *(remaining seam: planning-setup validators + reservation/planning DIVE handlers)*

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
  pages) ride their own next-touch journey, not J-5. **SHIPPED (the J-26 hotspots) J-26 T-22/T-23:** the shared
  `shared/util/form/error-patch.ts` `classifyApiError` (ordered `SaveErrorRule[]`) + the `withOptionals` form↔request
  collapse absorbed aircraft/users/persons `errorPatch` + form-mapping (CRAP 160→13.8, 156→30, 132→30, …) and the
  flights `applyLastContextThenPrefs` (CRAP 210→13.8) + `finalSubmit` (240→56). STILL PENDING (their own next-touch
  journey): `flights/list/flights-list.page.ts` (24cyc, untouched), flights `store.errorPatch` (deliberately
  unconverted — it's a 412/409 optimistic-lock state machine, not a kind-table). *(seam: `*-edit.page.ts`
  form-mapping helper extraction + the per-feature store `errorPatch`)*
  ~~Minor, same budget: drop the **3 unused files + 6 unused deps + 1 unresolved import** fallow lists.~~ **SHIPPED
  J-26 T-24** (removed the dead `scripts/migrate-translations/` module + 5 redundant `@angular-eslint/*` devDeps;
  3 fallow false-positives suppressed in `.fallowrc.json` so the snapshot is honest; fallow dead-code 12→1).

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
  **PARTIAL (J-8 T-06/T-07):** the new accounting + accounting-reference endpoints all carry explicit
  `@Operation(operationId=…)` (named methods in the regenerated client). The **project-wide** pass over the
  remaining legacy `getN` endpoints is STILL PENDING — deliberately deferred from J-8 (risky whole-client
  re-name churn; do it in isolation on a future web journey, not bolted onto an already-large feature journey).

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

- ~~**[NEXT-JOURNEY PRIORITY — retro 2026-06-12]** **Proof job doesn't upload per-test `test-results/**` (error-context.md + trace.zip) on failure**~~ **SHIPPED J-26 T-25.**
  Added an `actions/upload-artifact@v4` step gated on `if: failure()`, placed immediately AFTER each real-idp
  Playwright `pw` step and BEFORE the staging/gallery/shots-present-guard steps that consume or mutate
  `test-results/` (the guard re-invokes Playwright, which cleans the output dir → wipes the per-test
  `error-context.md`/`trace.zip` the pre-existing always()-upload would otherwise have lost). Landed in all three
  same-shape ci.yml jobs — `alpenflight-proof`, the J-3 `dashboard-proof`, and the REQUIRED J-4 `profile-proof` —
  plus the `alpenflight-proof-fanout.yml` AlpenFlight parity step. Path `alpenflight/web/test-results/**`
  (empirically verified: no `outputDir` in the Playwright config; per-test dirs land there), `retention-days: 7`,
  distinct artifact name per job. actionlint green. *(was: ci.yml alpenflight-proof + fanout test-results
  upload-artifact on failure, pre-gallery)* [[feedback_surface_proof_early_on_repeated_failure]]
- ~~**Reservation Save enabled while form invalid (async validator race, J-7 T-20 observation).**~~ **SHIPPED J-26 T-09.**
  The Save `[disabled]` now binds a `saveDisabled` computed off a `formStatus` signal
  (`toSignal(form.statusChanges)`) via the pure `saveDisabledFor(status, …)` — Save enabled ONLY when status is
  `'VALID'` (so `'PENDING'`/`'INVALID'` keep it disabled), replacing the non-reactive `form.invalid` getter the
  OnPush template only re-read on a CD tick. The conditional second-crew validator effect now emits so the inline
  error + form status re-derive in lock-step; a dead-end `onSubmit` surfaces field errors. Red-first 7-case unit
  test on `saveDisabledFor` + reservation save-gating e2e case. *(was: reservation-edit Save disable binding vs async second-crew validator)*
- **planning fixture club-B KC provisioning `beforeAll` can 45s-timeout under contention (J-7 T-20).** Did not
  reproduce on re-run (fanout `retries:1` absorbs it); if it recurs, bump that fixture's provisioning timeout
  or warm the KC admin client. *(seam: planning-migration-parity beforeAll club-B provisioning timeout)*
- **Planning `:410` edit-crew cold-`page.goto` reopen flakes on OIDC reboot/renew stall (J-7 T-21).** The
  reopen via `page.goto('/planning/{id}/edit')` hits the documented cold lazy-chunk/OIDC-renew stall
  ([[project_real_idp_goto_reboot_renew_stall]]); self-heals warm + CI `retries:1`. Switch that reopen to warm
  in-app nav to harden. *(seam: planning-migration-parity :410 reopen → warm nav)*

## Pending (filed by /do-ship 2026-06-10, J-7 gate — STRUCTURAL gallery)

- ~~**[NEXT-JOURNEY PRIORITY — retro 2026-06-12]** **Legacy-side parity shot renders "pending" though the PNG is produced + staged (staged-≠-rendered drift, J-7 T-22).**~~
  **SHIPPED J-26 T-26.** Made staged==rendered STRUCTURAL: (1) new exported `renderedShotKeys(shots, journey)` in
  `generate-gallery.mjs` is the SINGLE source of truth — it projects exactly the `extractScreenshots` shots the generator
  RENDERS to the `<side>:<view>` keys; the `[shots-present]` guard's `loadStagedShotKeys` was rewritten to read through
  `extractScreenshots` → `renderedShotKeys` (the generator's OWN render source), not an independent PNG glob, so "present"
  (guard) is DEFINITIONALLY the generator's "rendered" set — one function, two callers. (2) the `[deployed-journey]` guard
  (`tryAssertJourneyComplete`) now parses the deployed page's per-figure render keys (off each `<img alt="<side> <view>">`) and,
  for every `expected` pair in `expected-shots.json`, asserts BOTH sides actually render on the deployed page — a declared pair
  rendering only one side REDS (was: ≥1 asset + assets-200, which a half-pair passed trivially). Per-context tolerance mirrors
  `[shots-present]` (`GALLERY_PROOF_CONTEXT` passed into BOTH deployed-journey steps: ci.yml=proof, fanout=fanout) so a side
  `producedBy` the OTHER context is tolerated-absent on this deploy; `pending`/un-captured shots are never asserted (the
  all-pending early-journey case stays green). Proof: 3 new generator unit tests reproduce the drift on a fixture (a one-sided
  J-7 stage) and prove `renderedShotKeys` == the page's rendered keys; `pnpm test:scripts` 56 green; browserless link-check +
  `[shots-present]` (J-1) green; actionlint clean on both workflows. *(was seam: alpenflight-proof-fanout.yml add_shot json
  emission + generate-gallery.mjs pair render + deployed-journey guard both-sides)*
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
  `ReferenceDataSeeder`, `MutationAuditEventListener` system-actor write). **SHIPPED J-26 T-14/T-15/T-16:**
  `LanguageCodeLookup` → RM-4 `Language` JPA repo (T-14); `JpaClubStateRepository` + `JpaCountryRepository`
  native→JPQL via the V40 ICU-collation column move (T-15); `PlanningDayPersistenceProbeImpl` → the
  `ReservationCountPort` (`@NamedInterface`, T-16). `MeService` was DONE in RM-4 (incl. its
  `JpaUserRepository.languageExists` native→JPQL boyscout). Register re-affirm pass (T-17) recorded the
  conflict-probe keep-GiST decision + all 5 escape hatches re-affirmed. STILL PENDING convert-on-touch:
  `JpaUserRepository` (remaining native), `JpaPersonRepository` (cross-tenant check),
  `AircraftReservationConflictProbeImpl` (KEEP-GiST recorded T-17), `ShowcaseSeeder`. *(seam: per-module infra layer)*
- **IT seeding: raw-JDBC → production-code per-touch (ADR 0027 §3).** ~85 ITs (incl. `TenantScopedRowBuilders` /
  `TwoClubFixture` consumers) seed via `JdbcTemplate`; convert each file the next time it's materially edited —
  convention, NOT a sweep story. J-7's own two ITs converted in PR #215 as the pattern reference. ADR 0021
  isolation rules unchanged. **Same per-touch convention now also covers club-id collisions:** single-schema
  external-PG runs (RM-2a) surfaced classes sharing club UUID literals by value with club-HARD-DELETING classes
  (LocationsAuthorizationIT pair fixed RM-5; 4 showcase-CLUB_2 squatters fixed RM-5; audit found latent pairs left:
  the migration round-trip family's bundle clubs `…04be`/`…0bb8` are also referenced by Audit*/Clubs* ITs — give a
  class ITS OWN club ids when touching it; production-reserved ids (ShowcaseSeeder, V-seeds) are off-limits as
  foreign fixture clubs). *(seam: server src/test, per-touch)*
- **~~Lifecycle-boilerplate @MappedSuperclass — revisit the declined abstraction (CPD trio, J-7 RM-2).~~**
  RESOLVED J-26 T-21 (ADR 0028). The "five aggregates" framing was loose: the empirical CPD clone was the
  56-token triplet across Aircraft / FlightType / Location only (Flight + Person have divergent softDelete
  signatures, never cloned). Verdict = EXTRACT the boundary-clean half: soft-delete state +
  softDelete(userId, clock) + isDeleted() moved to the new @MappedSuperclass
  `platform.persistence.SoftDeletableAggregate` (the three extend it); the @DomainEvents saved-event hook
  STAYS per-aggregate (lifting it couples the shared kernel to each module's *Saved event — Modulith/ADR 0023
  boundary violation). cpd-baseline ratcheted 4883→4827; ADR 0028 is the standing reference so the ratchet
  stops re-litigating. *(seam: domain aggregates' lifecycle block + cpd-baseline.txt — done)*
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
  done — TwoClubFixture's club seed (T-19), and the 5 Sweep factories + `SweepFixtureContext.jdbc()` (T-20,
  converted to production save paths). The standing remainder is the **per-IT** seeders: of the **84** server
  test files touching `JdbcTemplate` (verified by grep, not estimated), **44 SEED (raw `INSERT`)** and **40 use
  JDBC purely for assertion (`SELECT`) / hard teardown (`DELETE`)**. The 40 assert/teardown files are NOT the
  §3 anti-pattern (no domain-invariant bypass — a hard `DELETE` that bypasses soft-delete is legitimate test
  infra) and should stay JDBC. The ~44 seed files convert **one file at a time, on its next material edit** —
  ADR 0027 §3's own rule is per-touch, NOT a sweep story (an 84-file sweep is explicitly forbidden). *(seam:
  `server/src/test`, per-touch)*
- **Pinned-id aggregate seeds legitimately stay JDBC (the @GeneratedValue + Hibernate-7-overwrite wall).** When an
  IT must seed an aggregate at an **externally-pinned** id (a consumer asserts on that exact id), production save
  paths cannot deliver it: aggregate roots use `@GeneratedValue(UUID)` and Hibernate 7's `UuidGenerator`
  (a `BeforeExecutionGenerator`) OVERWRITES any reflection-set id at insert — so save/persist/stateless-insert
  all mint a fresh id (the T-19 wall). Such pinned-id seeds keep their raw `INSERT` (documented inline), citing
  the `tenancy-showcase-seed-deterministic-ids` native-sql-register precedent. The convertible case — the parent's
  id is minted by the seed and merely passed to the child (no external pin) — is the clean win T-20 executed
  across all 5 Sweep factories. *(seam: `server/src/test`, per-touch)*

## Pending (J-9-filed, UPDATED by J-10 2026-06-15 — the fanout now runs end to end)

J-10 fixed the fanout's legacy builds (J-9) + the 409, so the real-bundle parity specs run end to end for
the first time — revealing that **the merged migration journeys' migrated done-bars were hollow** (the
fanout silently skipped on their branches per the J-9-retro finding). On `integration/J-10` the fanout now
runs 39 passed / 3 failed; the 3 are pre-existing migrated-FIDELITY gaps on already-merged journeys, NOT
J-10 (J-10's Delivery migration is deferred to J-10b).

- ~~**Migration-bundle-ingest 409.**~~ **FIXED (J-10 T-07):** `ensureSharedMigrationBundle`/`ingestBundle`
  poll the deployment to `COMPLETED` + reuse `existingDeploymentId` on a 409 — no ingest-409 cascade.

**⚠ BLOCKS the next MIGRATION journey** (hard fanout gate, J-9 retro). Fold into the next migration journey
(J-10b / J-11 / J-1 / J-21, whichever ships first):
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

These confirm the J-9-retro lesson at scale (migrated done-bars never enforced on-branch). Worth a focused
migration-fidelity pass — surface to the operator at `/do-plan` / the next `/do-retro`.
