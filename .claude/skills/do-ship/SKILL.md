---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a **manager**: decide the task list, run
each task in its own fresh worker context, run the proof-chain gate, open the PR. The operator merges.

**Manager context budget — reach §4 LEAN.** Saturating context before the gate is a process failure. Hold ONLY
the task checklist + one line per task. **Never read token-heavy material into the manager** — legacy source,
CI/gate logs, gradle/Playwright output, `git diff`, large files. Delegate every such read to a subagent that
returns ONLY the conclusion — root cause + `file:line` + the fix-shaped next task, ≤150 words. About to open a
log? Dispatch instead.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md) first — both
directives govern. Schema is structural; business rules on aggregates. **Search posture:** prefer MCP over raw
grep — IntelliJ MCP for code navigation, **codebase-memory-mcp** for prior-art recall.

**Operator-facing text uses ASD-STE100** (`CLAUDE.md` §"Operator-facing language") — chat, PR bodies, commit messages, journey files, gallery captions. Active voice, ≤20-word instructions, one word one meaning, no idioms.

**Shape.** One journey at a time (vertical diffs collide), on its **own branch** `integration/J-NNN`; tasks commit
**directly** onto it, each in a **fresh-context `/do-task` worker**. On-demand: `legacy-oracle` (exact legacy
behavior), `e2e-driver` (proof chain), `gap-hunter` (attack the green MID-JOURNEY + once at the gate).

## The done bar — a real, honest green

Done only when the journey's Playwright spec drives the **real UI** end to end and passes — clean seed first,
then real legacy data migrated into AlpenFlight (Postgres + Keycloak). Plus:

- **Migration journeys** (any mapper) need a **green real-export (fanout) run**, not synth: synth bundles alias
  columns and never hit the producer SELECT, so binding / drift / dedupe / FK bugs surface only at the real
  fanout. [[project_synth_bundle_doesnt_validate_producer_select]] [[verify_infra_is_run_not_just_authored]]
- **Legacy-replacing screens** produce paired legacy↔AlpenFlight screenshots + the legacy video in the gallery.
  `e2e-driver` owns the capture.
- **New screens are chrome-reachable:** nav/link per legacy (+ role visibility) and the proof spec ENTERS
  through it — a URL-only screen is hollow (J-7 /flightreports). `gap-hunter` blocks it.
- **The packaged artifact starts** — green tests don't prove it (J-15: five green ITs over an app that could not
  boot). `/do-task` smoke-tests it per task. [[project_test_classpath_hides_boot_failures]]
- **Infra / hardening journeys** (J-26, J-29..J-31) are done only when the target workflow is **job-level GREEN
  read from its test tally** (0 real failures), NOT "the stack comes up" (J-29 called the nightly green on
  stack-runs while 12 reds rotted). They owe the same **≥1 provable screen result**; reusing a built screen is
  fine. [[project_nightly_e2e_dead_stack_silent_hang]]
- **The PR's OWN checks are the gate** (operator 2026-08-14): `gh pr checks --required` = pass **from the
  `pull_request` event on the merge head**, heavy jobs EXECUTED there (§5's docs-only guard). A
  `workflow_dispatch` run and a local real-chain run are diagnostics; **prose is never the gate** (J-17's
  "Verification evidence" comment certified a side-run of the J-0 baseline; the journey then sat 8 days with an
  empty gallery). A platform incident blocking the event → the journey **stays open**, and say so.

**Red is the work-list, not a wall** — but a journey never merges red, and mocked-seam green is an inner-loop aid,
never progress. Only the real-chain green counts.

### 1 — Resolve + branch

Resolve `J-NNN` from arg or a `integration/J-NNN` branch. Bail if not `carved: true` or not `status: todo`
(in_progress → resume; done → refuse). Every `depends_on` journey must be `done` + PR `MERGED`. `/do-plan`
already pushed `integration/J-NNN` (carve + `/do-retro` riders) — **`git fetch` + checkout + pull it**, don't
re-create. Flip `status: in_progress` + `started_at`; open the GitHub issue (`J-NNN: <title>`) if `gh` + remote.

### 2 — Decide the task list (stay lean)

Refresh the graph before recall (`detect_changes`; if drifted, incremental `index_repository`). Read ONLY the
journey spec + its `rolls_up` stories. **Never read legacy source into the manager** — dispatch `legacy-oracle`
or a throwaway `Explore`; its distilled output is the worker input. Write an ordered `## Tasks` checklist into
the journey file — `T-NN` ids, one-line scope each, dependency order. Default decomposition:

1. **T-01 — spec stub + scaffold the journey proof page.** The spec's structure + selectors + flow (thin
   assertions, commits the screen shape) AND the per-journey gallery page (there is NO index — J-11 removed it) — the
   operator's only glanceable window; surface its link at the first captures.
2. **T-02 — move prior journeys to mock-IdP.** Scope the per-push gate so ONLY the journey-under-work runs heavy
   (real-idp); prior journeys run mock-IdP (full regression → nightly + §4), so an unrelated flaky spec cannot
   gate this journey.
3. **Vertical work-packages.** Migration → backend slice → frontend slice, split into tasks a fresh worker
   finishes cleanly (one entity / endpoint cluster / component), sized per the gate. **Migration tasks:** when
   the next-schema has a UNIQUE/CASCADE the legacy lacks (`legacy-oracle` flags these), the task MUST ship a
   real-producer collision/orphan round-trip IT — so it reds in `check` (minutes), not the ~20-min fanout (J-6).
4. **Proof-chain contribution.** This entity's legacy seed + per-entity mapper.
5. **Final task — thicken spec** to full real assertions from the oracle.

**Pull boyscout riders.** Fold pending `_BOYSCOUT.md` riders touching this journey's surface into `T-NN`s — that
is how `/do-retro` fixes reach the proof loop. **PLUS a burndown quota, HIGHEST-SEVERITY-FIRST**: **S1** security
/ tenancy / correctness / money > **S2** coverage gap / silent-failure risk > **S3** cosmetic / dead code / doc,
as many as the 60/40 debt share affords; a rider whose seam no journey touches never ships otherwise. Neither
oldest-first nor severity-first drained the file (~17 → 45), so the operator (2026-08-19) put S1+S2 burndown on a
dedicated hardening journey. **As each rider ships, DELETE its bullet** — never `✅`/struck-through.

**Sizing gate (pre-dispatch, every task).** Each `T-NN`: **one seam** (one aggregate+repo / one resource's
endpoints / one component-route / one migration / one spec edit — *'the domain layer' is not a task, 'the Booking
aggregate' is*); **≤8 files, ≤5 new**, one change describable without 'and', **≤3 tests at one layer**;
**self-naming** (≤2 globs find the files, else carve finer). A layer with N aggregates = **N tasks**. When unsure
split. The checklist is your only durable state.

### 3 — Manager loop (fresh worker per task)

Ensure the tree is on `integration/J-NNN`. For each pending task **in order**, spawn ONE fresh worker subagent
(clean context — that's the point) to execute `/do-task`:

> Agent (general-purpose): "Execute the `/do-task` playbook (`.claude/skills/do-task/SKILL.md`) for task `T-NN`
> of journey `J-NNN` on branch `integration/J-NNN`. <one-line task scope>. Commit directly to the branch.
> **Commit + report your SHA and RETURN — do NOT push** (the manager owns the push). Return only (≤150 words,
> `file:line` not pastes — no diffs, logs or file dumps): status (done/overflow/escalated/blocked), commit
> subjects, ACs touched, escalations."

Run tasks **sequentially** (shared branch + tree). After each worker returns: `status: overflow` → §3a; else
**`git push` the worker's commit yourself** (deterministic push timing; workers never hang on in-flight CI),
tick `T-NN`, keep its one-line summary, discard the detail. An **escalation** (parity / legacy-bug /
unmeetable-AC / contract conflict) stops the loop → § Escalation. Never push past a red task. After the first
locally-green backend task open a **draft PR** (`gh pr create --draft --base <integration-line> --head
integration/J-NNN`, body `Closes #N` + AC checklist). Watch CI in background by **conclusion only** (`gh run
view` — never `--log` into the manager); a red run becomes the next task (diagnosis delegated, below), not a
blocked wait; superseding an in-flight per-push run is fine.

**Worker deaths (session limits, J-7 ×3).** A dead worker NEVER resumes — `TaskStop` it (zombies re-arm with
STALE instructions); its in-tree work is UNVERIFIED DRAFT for a fresh finisher whose prompt restates the
environment rules + the verification protocol. Decompose toward SMALLER tasks — a death loses less.

**Fan-out over many units (a sweep, N shards): dispatch workers DIRECTLY — never a coordinator layer.** A
coordinator holds the whole batch in one session, so ONE death loses all of it (J-31: a coordinator plus its 6
judges died with **zero shard reports written**, so the tree's partial edits were unaccountable and discarded).
Each worker **writes its report as soon as its edits are done** — that artifact, not the diff, makes the work
keepable; on a death **keep only work with a completed report**; dispatch in waves of **~4**.

**A worker's local check can be weaker than the gate — run the REAL build at every batch boundary.** J-31's
judges used `javac -proc:none`, which disables the annotation processors, so NullAway/ErrorProne were exactly
what their check could not see; a "behaviour-neutral" rename produced 4 errors only Gradle caught.

**On a red run, DELEGATE the diagnosis — never pull run logs into the manager.** Dispatch `e2e-driver` or a
throwaway triage worker; it returns ONLY {failing job, root cause `file:line`, is a fix already in the tree?, the
fix-shaped next task}. Anchor on that cause, never the test diff (J-9 anchored wrong twice). **Your own reading
of a log is a HYPOTHESIS** — hand it over labelled as one, with the competing causes, and require the worker to
say which the evidence supports. J-19's manager was wrong three times (a no-op phantomjs fix, an invented legacy
divergence, a 17-vs-3 blast radius) and each time the worker who RAN something was right.
[[feedback_manager_log_diagnosis_is_a_hypothesis]] **Before calling a gate-red a flake, confirm the job is GREEN
on `main`** — green on `main` + red on-branch is JOURNEY-CAUSED (J-12a). **For a migration-fidelity red, MINE
the run's artifacts for the ACTUAL migrated values** (`gh run download`), never a derived expected value (J-27).

**Run a full-repo `./gradlew check` + the full mock-e2e suite at the BACKEND-batch boundary, AND the full `pnpm
test` at the FRONTEND-batch boundary, BEFORE §4.** Per-task workers verify FOCUSED tests (the right commit bar);
cross-cutting regressions surface only in the full suite: `cpdRatchet`, a shared spec ANOTHER journey asserts, a
`main`-push-only workflow, a shared web unit spec (J-13's new nav entry vs `nav-sections.spec.ts`'s exact-set
assertion). Once per batch — not per-task (too slow), not only at §4 (each miss costs a ~25-min real-idp cycle).
**When a task changes a SHARED surface** (a guard, the post-signup landing, an auth/tenant resolver, a spec
contract others assert), add a task to grep + update the cross-journey consumers up front — J-12a ate three gate
cycles here.

**A quarantine tag's recorded cause is a HYPOTHESIS.** Nobody re-tests it, because the tag makes the red look
like someone else's problem. J-19 chased `[KC-26 UPGRADE DRIFT]`, which blamed three quarantined tests on a
Keycloak upgrade: all three were OUR bugs (an unfilled `#username`, a missing PKCE parameter, a token lifespan
shorter than the SPA renew window), the tag also hid an assertion naming a landing path J-12a had changed, and
the third concealed a PRODUCTION bug — every silent renew threw the operator to `/start`. Reproduce before you
believe the rider, suspect the test helper first, and demand a reproduction from any rider blaming an external
component. [[feedback_quarantine_diagnosis_is_a_hypothesis]]

**Task growth is expected, not a failure** (operator 2026-08-19). The carve budgets slack because the gate
surfaces what no carve can see; J-19 ran 27 tasks against 15 carved and that was correct. Gate-surfaced work
that BLOCKS this journey's gate is fixed in-journey; anything that does not block it is a rider.

**Drive to the goal with tasks — never follow-ups.** A gap between the journey and its ACs (worker- or
gate-revealed) becomes **another `T-NN`** until the done bar — never a follow-up story / new journey / "later…".
Exception: work with **significant overlap with an already-roadmapped journey** stays that journey's (note it
for `/do-plan`; don't build here).

### 3a — Autonomous re-plan on overflow

A worker returns `status: overflow` + an `OVERFLOW:` note naming the seams — fired *before its first commit*, so
nothing partial is on the branch. Then (or when a task fails the sizing gate), **re-plan without the operator**
(once): mark `~~T-NN~~ (split)`, insert lettered `T-NNa, T-NNb, …` (one seam each; re-run the sizing gate on
each), re-dispatch `T-NNa`. **Loop guard:** only an un-lettered `T-NN` auto-splits; a *lettered* overflow →
**stop + escalate**. Never re-dispatch an id unchanged.

### 4 — Proof-chain gate

**Drive the real-idp spec green LOCALLY first (DEFAULT), then gate on CI** — gaps surface in fast local cycles,
not one CI cycle per gap; §4 CONFIRMS, it isn't where you discover gaps. **Real-idp RUNS locally**
([[project_real_idp_runs_locally]], `e2e/README.md`): `dev-up-full.sh` (KC + Mailpit) + `./gradlew bootRun`
(backend on the **LAN PG** — source `~/.bashrc` `DATASOURCE_*`, NEVER a Docker/compose PG) + `pnpm e2e:real-idp`.
Going CI-only needs a stated reason (J-9 T-22: 4 gaps/6 commits; J-13: ~5 gate cycles off a stale "OOM →
CI-only" belief). Do NOT `ALPENFLIGHT_TEST_FORCE_DOCKER` a local PG ([[feedback_no_local_postgres_for_tests]]).

When every task is ticked + the spec is locally green, `e2e-driver` runs the CI gate: the full chain (legacy seed
→ migrate → Keycloak → real Playwright, both fidelities green, video on pass) + — for a **migration journey (any
mapper)** — a **`fan-out parity` JOB green on the FINAL sha** (not just synth). CI: `alpenflight-proof`
(required, synth) + the `fanout` run.

**Verify the gate JOB-level, on the FINAL sha.** A run-level green can be hollow: `detect changes` skips every
job on a workflow/docs-only delta (`required` green over NOTHING, J-7); cancel-on-push can kill the only real
run; the fanout silently no-ops on integration branches when its legacy build fails cold
([[project_fanout_legacy_build_cold_nuget]]). Confirm the jobs EXECUTED for the sha you ship — **for a migration
journey `fan-out parity` is a HARD merge gate**. When the head is docs/CI-only the lane correctly skips, so
**name the last CODE-bearing sha and verify the lane there** (J-19 hunted back three commits for it); say in the
PR which sha proves what. Executed ≠ proving THIS journey: confirm the proof job logged `baseline=false` and the
bookmark's `<h1>` reads the journey id. **A warm cache proves nothing about a cold-cache fix** — bust the cache
(`gh cache delete`) and re-run. [[project_false_green_derive_fallback]]

**A gate must PROVE A RED PER INPUT CLASS.** J-19 shipped four guards and every one missed a class inside its
own stated scope: the date guard skipped `_helpers/*.ts` + the root `e2e/` tree, its own fix then skipped
**backtick** literals (the form its failure message recommended), the port guard ran in a `paths:`-filtered
workflow covering 2 of the 11 sites it checked, and the size guard's selftest passed while the function counted
`.git` (the fixture had none). Authors test the shape they pictured; the miss is always the one they didn't.
So before a guard is done: **enumerate** the classes it claims (file types, dirs, quote styles, verbs, call
shapes); **plant a violation in EACH and score the OLD implementation** to prove the class was uncovered; put it
in a graph-root job with no `paths:`/`if:`/`needs:` (J-31's `comment-strip --check` is the pattern — `extract.yml`
was filtered away from `tenant-rules.yaml` and stayed red ~3 months); make the selftest fixture carry the
adversarial shape; and state the residual limit in the FAILURE MESSAGE, where a blocked developer reads it.
[[feedback_gate_must_prove_a_red_per_input_class]]

**Dev-time proof = THIS journey only; full green only at the gate** ([[feedback_dev_time_test_strategy]]). Per
push runs only the journey's OWN spec(s) (T-02); prior journeys run mock-IdP; the full cross-journey real-idp
regression runs nightly + once at the §4 gate (**nothing skipped**), sharded so it stays fast. **Gallery deploy
survives a red case**: capture before deep assertions; gate deploy on `!cancelled()`.

**Gallery model — ONE page, the CURRENT journey only** ([[feedback_proof_gallery_per_journey_one_bookmark]]):
paired legacy↔AlpenFlight shots + pass video + migration round-trip; no all-journeys index / history pages /
sub-path split (merged proof lives in PRs). **Verify the DEPLOYED artifact, not the spec pass** (recurred ~4× in
J-6): curl the bookmark + every asset (200); a caption claims only what the spec asserts (§5). On a
repeatedly-red proof, re-deploy + surface the gallery BEFORE retrying — suspect the screen shape, not just the
test ([[feedback_surface_proof_early_on_repeated_failure]]). **The in-flight window is the per-PR PREVIEW
bookmark** (`…/alpenflight/proof-preview/<branch>/`, `pull_request` event) — give the operator THAT link. The
canonical `…/alpenflight/proof/` deploys only on `main`, where the derive resolves to the J-0 baseline and reads
"proof — unknown"; expected, not clobbered (J-12b). `workflow_dispatch` deploys nothing. **A Pages BUILD can
error while the push succeeds** (J-19: the site hit 89% of the 1 GB cap), serving a stale gallery — read the
Pages builds API, not just the bookmark.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries an inline
`@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff** at the gate. Spawn
**Run `gap-hunter` MID-JOURNEY, not only here** (operator 2026-08-19): once the screens exist and BEFORE the
rider burndown, one round against the diff so far — blockers surface while context is cheap and the fix is one
task. J-19 ran three gate-time rounds and each found blockers, including the round auditing the previous round;
both its hollow-green blockers (URL-only `/lostpassword`, unreachable `/confirm` branch) were visible the moment
those screens landed. Then ONE confirming round here. Spawn
`gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + that list; undeclared mocks, stubs, un-wired
layers, tenancy leaks, **gerrymandered inner-loop fixtures** (a mock value the real backend never returns — J-13
`httpStatus:200` on a success row vs real null → green mock, red gate; [[feedback_honest_inner_loop_fixtures]])
→ **chain is red** → new tasks, return to step 3. Honor the wallclock budget (shard / reuse snapshots).

### 5 — Document + green PR

Prune the journey body to load-bearing decisions ONLY — delete file trees, signatures, resolved threads and the
per-task notes; keep frontmatter, ACs, contracts, parity exclusions, the task checklist, a short Outcome. It is a
contract, not a changelog (J-7 hit 719 lines; J-19 pruned 717 → 141). Flip `status: done` + `done_at`; mark each `rolls_up`
story `rolled_up_into: J-NNN` **AND flip its `status: todo → done`** (stamping the pointer alone left 24 shipped
stories lying as `todo` through J-11; operator 2026-06-24); a story split across journeys (`rolled_up_into: [J-a,
J-b]`) flips only once the LAST merges. **Retire the shipped journey from the forward backlog** (operator
2026-06-25): `_ORDER.md` is FORWARD-ONLY — remove **ALL** of its forward refs, the roadmap-table row AND its `##
Per-journey Playwright contract` one-liner (`grep "J-NNN" _ORDER.md` → only a historical coverage-map ref may
remain; J-13 removed only the row and stranded its contract line); append `- J-NNN — <title> — #PR`
(newest-first) to `_SHIPPED.md`; `git mv` the journey file to `stories/implemented/`. `ci.yml`'s proof-spec +
mock-filter derives search BOTH dirs — a journey file resolving in NEITHER is a hard CI fail, not a baseline
fall-back (J-17). [[project_false_green_derive_fallback]]
**Docs-only head guard** (J-27, J-12b; the operator keeps the dev-time `docs_only` skip — fix it procedurally,
not in ci.yml). A docs-only head makes `detect changes` skip the heavy lane and `required` green over the skips.
Mergeable needs: (1) the heavy lane job-level GREEN on the last CODE-bearing commit (§4); (2) the finalization
commit **truly docs-only** (`git diff --stat`); (3) migration journeys — `fan-out parity` green on that head.
Tell the operator the docs-head skips are expected, and which sha carries the proof.

**Troubleshooting mode — iterate on GitHub, not on this box** (operator 2026-08-02). When diagnosis needs repeated
heavy runs, push a temporary workflow running only the job under diagnosis, so it runs IN PARALLEL with local
coding instead of monopolising a 2-core box (J-15: Gradle beside Playwright → 13 phantom failures + 3× slowdown).
Fail-closed: a committed marker (`.ci-troubleshooting`) makes `ci.yml` skip the heavy lane AND `required`
hard-FAIL; `required` re-reads the marker itself, so its red survives any job-graph rewiring. Before handover:
delete the marker, **fold every test the scratch runs added into normal CI**, confirm the heavy lane ran green.

**Reconcile the PR's OWN checklist** — the **AC checklist** is a DIFFERENT list from the task checklist (J-15
reported "all tasks done" on 13/13 tasks while the PR showed 7 unticked ACs). **Tick an AC only against a NAMED
assertion.** Where the gate proved it more cheaply than the AC words it — at IT level, by a weaker assertion, or
not at all — **qualify it ON THE LINE** (PR #249, #251 are the worked examples) and file the gap as a rider. An
unqualified tick on a cheaper test is a false done-bar. **Captions are held to the same bar** (J-31 advertised
balance arithmetic over an inequality assertion, on an accounting surface). `gh pr ready`. Give the operator the
**gallery link** (not SendUserFile'd — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked seams.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity assertion only passes by
changing behavior; this journey breaks another's green; a `depends_on` artifact is missing despite the dep being
done; ported legacy code has an apparent bug (never silently fix); an AC is unmeetable; a FK-closure forces
binding ANOTHER journey's migration entity (don't bind it — the carve missed a dep, re-scope/defer, J-10
ARTICLE→J-11); or `gap-hunter` flags a blocker needing a contract/ADR/sacred-cow change. Default next:
`/do-retro` captures the lesson; `/do-plan` re-carves if the shape was wrong.

**A "parity exclusion" must be cosmetic or proven-unreachable** (J-9b): legitimate ONLY if cosmetic OR the worker
cites the data/config making it unreachable. A *reachable* divergence — especially on a money/safety surface — is
a suspected ported-legacy bug → **escalate, never bury it** in a rider (J-9b nearly shipped a reachable
over-credit; the operator caught it at review). **Verify the legacy behaviour in the SERVER, not the UI label** —
J-19's carve read `GENERATE_NEW_PASSWORD` off a button and invented an ADR-0026 divergence that did not exist.

## Quality bar

- One journey per invocation; `carved: false` is a hard bail; every task runs in a fresh worker context. Green bar
  = the real full-chain run; declared+signed mocks only, undeclared mock = red.
- Schema structural, business rules on aggregates (ADR 0022 §2). Tasks commit to `integration/J-NNN`; the
  **manager pushes**; never merge red; one PR per journey. Prune before done; cite file:line/PR#/J-ID.
- **No comments — put the understanding in the name.** Zero human-written prose in code, specs or YAML. A comment
  you want to write is a symbol/test/constant needing a longer name, or a `docs/modernization/` entry. Survivors:
  tool-parsed directives (`eslint-disable*`, `@ts-*`, `prettier-ignore`, `noinspection`, `language=`,
  `@formatter:off/on`, shebangs) and `// ext:` at an externally-owned boundary. History → the commit message.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey `status: done`, real chain green, proof in the gallery, one PR ready. Operator merges → `/do-plan next`.
