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

## Pending (filed by /do-retro 2026-06-04, J-2 window)

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
