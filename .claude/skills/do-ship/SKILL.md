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

**Operator-facing text uses ASD-STE100** (`CLAUDE.md` §"Operator-facing language") — chat, PR bodies, commit
messages, journey files, gallery captions. Active voice, ≤20-word instructions, one word one meaning, no idioms.

**Shape.** One journey at a time (vertical diffs collide), on its **own branch** `integration/J-NNN`; tasks commit
**directly** onto it, each in a **fresh-context `/do-task` worker**. On-demand: `legacy-oracle` (exact legacy
behavior), `e2e-driver` (proof chain), `gap-hunter` (attack the green MID-JOURNEY + once at the gate).

## The done bar — a real, honest green

Done only when the journey's Playwright spec drives the **real UI** end to end and passes — clean seed first,
then real legacy data migrated into AlpenFlight (Postgres + Keycloak). Plus:

- **Migration journeys** (any mapper) need a **green real-export (fanout) run**, not synth. Synth bundles alias
  columns and never hit the producer SELECT, so binding / drift / dedupe / FK bugs surface only at the real
  fanout. [[project_synth_bundle_doesnt_validate_producer_select]] [[verify_infra_is_run_not_just_authored]]
- **Legacy-replacing screens** produce paired legacy↔AlpenFlight screenshots + the legacy video in the gallery.
  `e2e-driver` owns the capture.
- **New screens are chrome-reachable:** nav/link per legacy (+ role visibility) and the proof spec ENTERS
  through it. A URL-only screen is hollow. `gap-hunter` blocks it.
- **The packaged artifact starts.** Green tests do not prove it. `/do-task` smoke-tests it per task.
  [[project_test_classpath_hides_boot_failures]]
- **Infra / hardening journeys** are done only when the target workflow is **job-level GREEN read from its test
  tally** (0 real failures), never "the stack comes up". They owe the same **≥1 provable screen result**;
  reusing a built screen is fine. [[project_nightly_e2e_dead_stack_silent_hang]]
- **The PR's OWN checks are the gate** (operator 2026-08-14): `gh pr checks --required` must pass **from the
  `pull_request` event on the merge head**, with heavy jobs EXECUTED there (§5's docs-only guard). A
  `workflow_dispatch` run and a local real-chain run are diagnostics. **Prose is never the gate.** A platform
  incident blocking the event → the journey **stays open**, and say so.

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
   assertions, commits the screen shape) AND the per-journey gallery page (there is NO index) — the operator's
   only glanceable window; surface its link at the first captures.
2. **T-02 — move prior journeys to mock-IdP.** Scope the per-push gate so ONLY the journey-under-work runs heavy
   (real-idp); prior journeys run mock-IdP (full regression → nightly + §4), so an unrelated flaky spec cannot
   gate this journey.
3. **Vertical work-packages.** Migration → backend slice → frontend slice, split into tasks a fresh worker
   finishes cleanly (one entity / endpoint cluster / component), sized per the gate. **Migration tasks:** when
   the next-schema has a UNIQUE/CASCADE the legacy lacks (`legacy-oracle` flags these), the task MUST ship a
   real-producer collision/orphan round-trip IT, so it reds in `check` (minutes) rather than in the ~20-min fanout.
4. **Proof-chain contribution.** This entity's legacy seed + per-entity mapper.
5. **Final task — thicken spec** to full real assertions from the oracle.

**Agent budget — count it BEFORE task 1** (operator 2026-08-23). A `/do-task` worker costs about **12 agents**,
not one, because it dispatches its own helpers. A session caps at 200 agents, so it finishes about **16 tasks**.
When the task list exceeds that, **stop and escalate to the operator** — offer to re-scope, not to start. State
the budget and the task count in the journey file with the checklist.

**Pull the riders addressed to THIS journey.** `/do-plan`'s carve already moved every `RIDES: J-NNN` rider from
`_BOYSCOUT.md` into the journey file (operator 2026-08-23). Fold those into `T-NN`s. Burn
**HIGHEST-SEVERITY-FIRST**: **S1** security / tenancy / correctness / money > **S2** coverage gap /
silent-failure risk > **S3** cosmetic / dead code / doc. **Every S1 ships**; take the S2s whose seam this journey
already touches. **As each rider ships, DELETE its bullet** — never `✅`/struck-through. **Any NEW rider carries
`RIDES: J-NNN`** (or `RIDES: next` when no journey owns the seam), so it routes home instead of silting up the
flat file.

**A rider's SYMPTOM is evidence; its CAUSE is a guess.** Open every burndown task by confirming or refuting the
stated cause against the tree, and say which (operator 2026-08-21). **About half of stated causes are wrong.**
Every catch comes from someone who measures, so keep the discipline. Treat a green as a hypothesis too: a rider
can look fixed because the test stopped asserting it, or because the case is `test.skip`ped.

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
unmeetable-AC / contract conflict) stops the loop → § Escalation. Never push past a red task.

**Open the draft PR BEFORE the first push — it is the only CI trigger.** `ci.yml` runs `push` on `main` ONLY;
`integration/**` reaches CI through `pull_request`. A push with no PR open runs **nothing**, and the branch looks
quiet rather than untested. `gh pr create --draft --base <integration-line> --head integration/J-NNN`, body
`Closes #N` + AC checklist. Watch CI by **conclusion only** (`gh run view` — never `--log` into the manager); a
red run becomes the next task (diagnosis delegated, below), not a blocked wait.

**Pushing cancels the in-flight fan-out.** Each push starts a new run and cancels the previous one in the same
concurrency group. Only the FINAL sha must be green, so this is free mid-journey — but **stop pushing once you
are verifying the gate**, and batch the last commits.

**Worker deaths.** A dead worker NEVER resumes — `TaskStop` it (zombies re-arm with STALE instructions); its
in-tree work is UNVERIFIED DRAFT for a fresh finisher whose prompt restates the environment rules + the
verification protocol. Decompose toward SMALLER tasks — a death loses less.

**Fan-out over many units (a sweep, N shards): dispatch workers DIRECTLY — never a coordinator layer.** A
coordinator holds the whole batch in one session, so ONE death loses all of it. Each worker **writes its report
as soon as its edits are done** — that artifact, not the diff, makes the work keepable. On a death **keep only
work with a completed report**. Dispatch in waves of **~4**.

**A worker's local check can be weaker than the gate — run the REAL build at every batch boundary.** A hand-rolled
`javac` check disables the annotation processors, so NullAway/ErrorProne cannot fire.

**On a red run, DELEGATE the diagnosis — never pull run logs into the manager.** Dispatch `e2e-driver` or a
throwaway triage worker; it returns ONLY {failing job, root cause `file:line`, is a fix already in the tree?, the
fix-shaped next task}. Anchor on that cause, never the test diff. **Your own reading of a log is a HYPOTHESIS —
and so is a WORKER'S OWN escalation**, even one that measured the code: it can name the wrong layer, or rest on a
false premise. **Verify an escalation before you escalate it to the operator.** Hand your reading over labelled
as a hypothesis, with the competing causes, and require the worker to say which the evidence supports.
[[feedback_manager_log_diagnosis_is_a_hypothesis]] **Before calling a gate-red a flake, confirm the job is GREEN
on `main`** — green on `main` + red on-branch is JOURNEY-CAUSED. **For a migration-fidelity red, MINE the run's
artifacts for the ACTUAL migrated values** (`gh run download`), never a derived expected value.

**The batch boundary is a PUSH plus a JOB-LEVEL CI read — never "all shards green"** (operator 2026-08-21).
Per-task workers verify FOCUSED tests (the right commit bar); cross-cutting regressions surface only in the full
suite — `cpdRatchet`, a shared spec ANOTHER journey asserts, a `main`-push-only workflow, a shared web unit spec
with an exact-set assertion. A full local `./gradlew check` OOMs on a 2-core box, so every worker shards and
reports the shards it ran, which is TRUE per shard and silent about the gaps. So at each batch boundary: push,
read the CI result **job-level**, and continue only then. Read the STEP list on a fast run — a docs-only head
skips the heavy lane and reports green over the skips. Once per batch: not per-task (too slow), not only at §4
(each miss costs a ~25-min real-idp cycle).

**When a task changes a SHARED surface** (a guard, the post-signup landing, an auth/tenant resolver, a spec
contract others assert), add a task to grep + update the cross-journey consumers up front.

**A quarantine tag or a `test.skip` reason is a HYPOTHESIS nobody re-tests** — the tag makes the red look like
someone else's problem, and it routinely hides our own bug, sometimes a production one. Reproduce before you
believe it, suspect the test helper first, and demand a reproduction from any rider blaming an external
component. [[feedback_quarantine_diagnosis_is_a_hypothesis]]

**Task growth is expected, not a failure** (operator 2026-08-19). The carve budgets slack because the gate
surfaces what no carve can see. Gate-surfaced work that BLOCKS this journey's gate is fixed in-journey; anything
that does not block it is a rider.

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
not one CI cycle per gap. §4 CONFIRMS; it is not where you discover gaps. **Real-idp RUNS locally**
([[project_real_idp_runs_locally]], `e2e/README.md`): `dev-up-full.sh` (KC + Mailpit) + `./gradlew bootRun`
(backend on the **LAN PG** — source `~/.bashrc` `DATASOURCE_*`, NEVER a Docker/compose PG) + `pnpm e2e:real-idp`.
Going CI-only needs a stated reason, and a stale "cannot run it here" belief costs whole gate cycles. Do NOT
`ALPENFLIGHT_TEST_FORCE_DOCKER` a local PG ([[feedback_no_local_postgres_for_tests]]).

When every task is ticked + the spec is locally green, `e2e-driver` runs the CI gate: the full chain (legacy seed
→ migrate → Keycloak → real Playwright, both fidelities green, video on pass) + — for a **migration journey (any
mapper)** — a **`fan-out parity` JOB green on the FINAL sha** (not just synth). CI: `alpenflight-proof`
(required, synth) + the `fanout` run.

**Verify the gate JOB-level, on the FINAL sha.** A run-level green can be hollow: `detect changes` skips every
job on a workflow/docs-only delta (`required` green over NOTHING); cancel-on-push can kill the only real run;
the fanout silently no-ops on integration branches when its legacy build fails cold
([[project_fanout_legacy_build_cold_nuget]]). Confirm the jobs EXECUTED for the sha you ship — **for a migration
journey `fan-out parity` is a HARD merge gate**. When the head is docs/CI-only the lane correctly skips, so
**name the last CODE-bearing sha and verify the lane there**; say in the PR which sha proves what. Executed ≠
proving THIS journey: confirm the proof job logged `baseline=false` and the bookmark's `<h1>` reads the journey
id. **A warm cache proves nothing about a cold-cache fix** — bust the cache (`gh cache delete`) and re-run. **A
gate whose evidence needs a human trigger is not a gate** (operator 2026-08-21): if you are dispatching a
workflow by hand to feed a required check, that is the defect — fix the trigger.
[[project_false_green_derive_fallback]]

**A gate must PROVE A RED PER INPUT CLASS.** Authors test the shape they pictured; the miss is always the one
they didn't. Before a guard is done: **enumerate** the classes it claims (file types, dirs, quote styles, call
shapes); **plant a violation in EACH and score the OLD implementation** to prove the class was uncovered; put it
in a graph-root job with no `paths:`/`if:`/`needs:`; state the residual limit in the FAILURE MESSAGE; and
**withdraw a class you cannot actually score** — an unproven class is a hole, not a guard.
**Then verify one level OUT — is the guard WIRED, and does its lane RUN?** A guard can carry its annotation and
execute nowhere; a whole module's tests can sit in no CI lane at all, scoring nothing. Check the module's tests
are in a lane, not just that the test passes locally. [[feedback_gate_must_prove_a_red_per_input_class]]

**Dev-time proof = THIS journey only; full green only at the gate** ([[feedback_dev_time_test_strategy]]). Per
push runs only the journey's OWN spec(s) (T-02); prior journeys run mock-IdP; the full cross-journey real-idp
regression runs nightly + once at the §4 gate (**nothing skipped**), sharded so it stays fast. **Gallery deploy
survives a red case**: capture before deep assertions; gate deploy on `!cancelled()`.

**Gallery model — ONE page, the CURRENT journey only** ([[feedback_proof_gallery_per_journey_one_bookmark]]):
paired legacy↔AlpenFlight shots + pass video + migration round-trip; no all-journeys index / history pages /
sub-path split (merged proof lives in PRs). **Verify the DEPLOYED artifact, not the spec pass**: curl the
bookmark + every asset (200); a caption claims only what the spec asserts (§5). On a repeatedly-red proof,
re-deploy + surface the gallery BEFORE retrying — suspect the screen shape, not just the test
([[feedback_surface_proof_early_on_repeated_failure]]). **The in-flight window is the per-PR PREVIEW bookmark**
(`…/alpenflight/proof-preview/<branch>/`, `pull_request` event) — give the operator THAT link. The canonical
`…/alpenflight/proof/` deploys only on `main`, where the derive resolves to the J-0 baseline and reads
"proof — unknown"; expected, not clobbered. `workflow_dispatch` deploys nothing. **A Pages BUILD can error while
the push succeeds**, serving a stale gallery — read the Pages builds API, not just the bookmark.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries an inline
`@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff** at the gate.

**Run `gap-hunter` MID-JOURNEY, not only here** (operator 2026-08-19): once the screens exist and BEFORE the
rider burndown, one round against the diff so far — blockers surface while context is cheap and the fix is one
task. Hollow-green blockers (a URL-only screen, an unreachable branch) are visible the moment those screens land.
Then ONE confirming round here: spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + that list;
undeclared mocks, stubs, un-wired layers, tenancy leaks, **gerrymandered inner-loop fixtures** (a mock value the
real backend never returns → green mock, red gate; [[feedback_honest_inner_loop_fixtures]]) → **chain is red** →
new tasks, return to step 3. Honor the wallclock budget (shard / reuse snapshots).

### 5 — Document + green PR

Prune the journey body to load-bearing decisions ONLY — delete file trees, signatures, resolved threads and the
per-task notes; keep frontmatter, ACs, contracts, parity exclusions, the task checklist, a short Outcome. It is a
contract, not a changelog. Flip `status: done` + `done_at`; mark each `rolls_up` story `rolled_up_into: J-NNN`
**AND flip its `status: todo → done`** (operator 2026-06-24 — stamping the pointer alone leaves shipped stories
lying as `todo`); a story split across journeys (`rolled_up_into: [J-a, J-b]`) flips only once the LAST merges.
**Retire the shipped journey from the forward backlog** (operator 2026-06-25): `_ORDER.md` is FORWARD-ONLY —
remove **ALL** of its forward refs, the roadmap-table row AND its `## Per-journey Playwright contract` one-liner
(`grep "J-NNN" _ORDER.md` → only a historical coverage-map ref may remain); append `- J-NNN — <title> — #PR`
(newest-first) to `_SHIPPED.md`; `git mv` the journey file to `stories/implemented/`. `ci.yml`'s proof-spec +
mock-filter derives search BOTH dirs — a journey file resolving in NEITHER is a hard CI fail, not a baseline
fall-back. [[project_false_green_derive_fallback]]

**Docs-only head guard** (the operator keeps the dev-time `docs_only` skip — fix it procedurally, not in
ci.yml). A docs-only head makes `detect changes` skip the heavy lane and `required` green over the skips.
Mergeable needs: (1) the heavy lane job-level GREEN on the last CODE-bearing commit (§4); (2) the finalization
commit **truly docs-only** (`git diff --stat`); (3) migration journeys — `fan-out parity` green on that head.
Tell the operator the docs-head skips are expected, and which sha carries the proof.

**Troubleshooting mode — iterate on GitHub, not on this box** (operator 2026-08-02). When diagnosis needs repeated
heavy runs, push a temporary workflow running only the job under diagnosis, so it runs IN PARALLEL with local
coding. Never run Gradle beside Playwright on a 2-core box. Fail-closed: a committed `.ci-troubleshooting` marker
makes `ci.yml` skip the heavy lane AND `required` hard-FAIL, and `required` re-reads the marker itself so its red
survives a job-graph rewiring. Before handover: delete the marker, **fold every test the scratch runs added into
normal CI**, confirm the heavy lane ran green.

**Reconcile the PR's OWN checklist** — the **AC checklist** is a DIFFERENT list from the task checklist, and
"all tasks done" says nothing about unticked ACs. **Tick an AC only against a NAMED assertion.** Where the gate
proved it more cheaply than the AC words it — at IT level, by a weaker assertion, or not at all — **qualify it ON
THE LINE** (PR #249, #251, #256 are the worked examples) and file the gap as a rider. An unqualified tick on a
cheaper test is a false done-bar. **Captions are held to the same bar.** `gh pr ready`. Give the operator the
**gallery link** (not SendUserFile'd — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked seams.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity assertion only passes by
changing behavior; this journey breaks another's green; a `depends_on` artifact is missing despite the dep being
done; ported legacy code has an apparent bug (never silently fix); an AC is unmeetable; a FK-closure forces
binding ANOTHER journey's migration entity (don't bind it — the carve missed a dep, re-scope/defer); or
`gap-hunter` flags a blocker needing a contract/ADR/sacred-cow change. **Verify the escalation's premise first** —
a question built on a refuted claim wastes the operator's decision. Default next: `/do-retro` captures the
lesson; `/do-plan` re-carves if the shape was wrong.

**A "parity exclusion" must be cosmetic or proven-unreachable**: legitimate ONLY if cosmetic OR the worker cites
the data/config making it unreachable. A *reachable* divergence — especially on a money/safety surface — is a
suspected ported-legacy bug → **escalate, never bury it** in a rider. **Verify legacy in the SERVER, not the UI
label** — a button caption is not the behaviour.

## Quality bar

- One journey per invocation; `carved: false` is a hard bail; every task runs in a fresh worker context. Green bar
  = the real full-chain run; declared+signed mocks only, undeclared mock = red.
- Schema structural, business rules on aggregates (ADR 0022 §2). Tasks commit to `integration/J-NNN`; the
  **manager pushes**; never merge red; one PR per journey. Prune before done; cite file:line/PR#/J-ID.
- **No comments — put the understanding in the name** (survivors + full rule: `CLAUDE.md`). A comment you want to
  write is a symbol/test/constant needing a longer name. History → the commit message.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey `status: done`, real chain green, proof in the gallery, one PR ready. Operator merges → `/do-plan next`.
