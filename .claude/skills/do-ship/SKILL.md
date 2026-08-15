---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a **manager**: decide the task list, run
each task in its own fresh worker context, run the proof-chain gate, open the PR. The operator merges.

**Manager context budget — reach §4 LEAN.** Saturating context before the gate is a process failure, not bad
luck. Hold ONLY the task checklist + one line per task. **Never read token-heavy material into the manager** —
legacy source, CI/gate logs, gradle/Playwright output, `git diff`, large files. Delegate every such read to a
subagent (`legacy-oracle`, `e2e-driver`, `gap-hunter`, a throwaway `Explore`/worker) that returns ONLY the
conclusion — root cause + `file:line` + the fix-shaped next task, ≤150 words, no pastes. About to open a log?
Dispatch instead.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md) first — both
directives govern. Schema is structural; business rules on aggregates. **Search posture:** prefer MCP over raw
grep — IntelliJ MCP for code navigation, **codebase-memory-mcp** for prior-art recall.

**Operator-facing text uses ASD-STE100** (`CLAUDE.md` §"Operator-facing language") — chat, PR bodies, commit messages, journey files, gallery captions. Active voice, ≤20-word instructions, one word one meaning, no idioms.

**Shape.** One journey at a time (vertical diffs collide), on its **own branch** `integration/J-NNN`; tasks
commit **directly** onto it, each in a **fresh-context `/do-task` worker**. On-demand: `legacy-oracle` (exact
legacy behavior), `e2e-driver` (proof chain), `gap-hunter` (attack the green at the gate).

## The done bar — a real, honest green

Done only when the journey's Playwright spec drives the **real UI** end to end and passes — clean seed first,
then real legacy data migrated into AlpenFlight (Postgres + Keycloak). Plus:

- **Migration journeys** (any mapper) need a **green real-export (fanout) run**, not just synth: synth bundles
  alias columns and never hit the producer SELECT against the real schema, so binding / column-drift / dedupe /
  FK-resolution bugs surface only at the real fanout (authored ≠ proven).
  [[project_synth_bundle_doesnt_validate_producer_select]] [[verify_infra_is_run_not_just_authored]]
- **Legacy-replacing screens** produce paired legacy↔AlpenFlight screenshots + the legacy video in the gallery,
  PR link auto-posted. `e2e-driver` owns the capture.
- **New screens are chrome-reachable:** nav/link per legacy (+ role visibility) and the proof spec ENTERS
  through it — a URL-only screen is hollow (J-7 /flightreports). `gap-hunter` blocks it.
- **The packaged artifact starts** — green tests don't prove it (J-15: five green job ITs over an app that could
  not boot). §4's real chain is the proof; `/do-task` smoke-tests it per task.
  [[project_test_classpath_hides_boot_failures]]
- **Infra / hardening / stabilization journeys** (J-26, J-29, J-30, J-31) are done only when the target workflow
  is **job-level GREEN read from its test tally** (0 real failures), NOT "the stack comes up" (J-29 called the
  nightly fully-green on stack-runs while 12 masked reds rotted). Verify the suite PASSES, not RUNS. They owe
  the same **≥1 provable screen result** as any journey — reusing an already-built screen is fine.
  [[project_nightly_e2e_dead_stack_silent_hang]]
- **The PR's OWN checks are the gate** (operator 2026-08-14): `gh pr checks --required` = pass **from the
  `pull_request` event on the merge head**, heavy jobs EXECUTED there (§5's docs-only guard). A
  `workflow_dispatch` run and a local real-chain run are diagnostics; **prose is never the gate** (J-17's
  "Verification evidence" comment certified a side-run of the J-0 baseline; the journey then sat 8 days with an
  empty gallery). A platform incident blocking the event → the journey **stays open**, and say so.

**Red is the work-list, not a wall** — but a journey never merges red, and synthetic / mocked-seam green is an
inner-loop aid, never progress. Only the real-chain green counts.

### 1 — Resolve + branch

Resolve `J-NNN` from arg or a `integration/J-NNN` branch. Bail if not `carved: true` or not `status: todo`
(in_progress → resume; done → refuse). Every `depends_on` journey must be `done` + PR `MERGED`. `/do-plan`
already pushed `integration/J-NNN` (carve + `/do-retro` riders) — **`git fetch` + checkout + pull it**, don't
re-create. Flip `status: in_progress` + `started_at`; open the GitHub issue (`J-NNN: <title>`) if `gh` + remote.

### 2 — Decide the task list (stay lean)

Refresh the graph before recall (`detect_changes`; if drifted, incremental `index_repository` — don't retain its
output). Read ONLY the journey spec + its `rolls_up` stories. **Never read legacy source into the manager** —
dispatch `legacy-oracle` (parity-sensitive screens) or a throwaway `Explore`; its distilled output is the worker
input. Write an ordered `## Tasks` checklist into the journey file — `T-NN` ids, one-line scope each, dependency
order. Default decomposition:

1. **T-01 — spec stub + scaffold the journey proof page.** The spec's structure + selectors + flow (thin
   assertions, commits the screen shape) AND the per-journey gallery page linked from the persistent index — the
   operator's only glanceable window; surface its link at the first captures.
2. **T-02 — move prior journeys to mock-IdP.** Scope the per-push gate so ONLY the journey-under-work runs heavy
   (real-idp); prior journeys run mock-IdP (full regression → nightly + the §4 gate), so an unrelated
   heavy/flaky spec can't gate this journey.
3. **Vertical work-packages.** Migration → backend slice → frontend slice, split into tasks a fresh worker
   finishes cleanly (one entity / endpoint cluster / component), sized per the gate. **Migration tasks:** when
   the next-schema has a UNIQUE/CASCADE the legacy lacks (`legacy-oracle` flags these), the task MUST ship a
   real-producer collision/orphan round-trip IT — so it reds in `check` (minutes), not the ~20-min fanout (J-6).
4. **Proof-chain contribution.** This entity's legacy seed + per-entity mapper.
5. **Final task — thicken spec** to full real assertions from the oracle.

**Pull boyscout riders.** Fold pending `_BOYSCOUT.md` riders touching this journey's surface (or stale infra
riders) into `T-NN`s sized per the gate — that's how `/do-retro` fixes reach the proof loop. **PLUS a burndown
quota, HIGHEST-SEVERITY-FIRST** (operator 2026-08-15): fold **off-surface riders in severity order** — **S1**
security / tenancy / correctness / money > **S2** coverage gap / silent-failure risk > **S3** cosmetic / dead
code / doc (`/do-retro` tags each bullet) — as many as the journey's tech-debt share (60/40) affords; a rider
whose seam no journey happens to touch never ships otherwise. Oldest-first was the J-17 retro's rule and
demonstrably did NOT drain the file (~17 → 40 riders / 30 sections). **As each rider ships, DELETE its bullet
from `_BOYSCOUT.md`** — never mark it `✅`/struck-through and leave it; shipped work lives in git + the PR.

**Sizing gate (pre-dispatch, every task).** Each `T-NN`: **one seam** (one aggregate+repo / one resource's
endpoints / one component-route / one migration / one spec edit — *'the domain layer' is not a task, 'the
Booking aggregate' is*); **≤8 files, ≤5 new**, one logical change describable without 'and', **≤3 tests at one
layer**; **self-naming** (the scope line names the files / ≤2 globs find them, else carve finer). A layer with N
aggregates = **N tasks**. When unsure split — early-finish is free, overflow costs a re-plan. The checklist is
your only durable state.

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

**Worker deaths (session limits, J-7 ×3).** A dead/limited worker NEVER resumes — `TaskStop` it (zombies re-arm
with STALE instructions); its in-tree work is UNVERIFIED DRAFT for a fresh finisher whose prompt restates the
environment rules + the full verification protocol. Decompose toward SMALLER tasks — a death loses less.

**Fan-out over many units (a sweep, N shards): the manager dispatches workers DIRECTLY — never a coordinator
layer.** A coordinator holds the whole batch in one session, so ONE death loses all of it (J-31: a coordinator
plus its 6 judges died together with **zero shard reports written**, so the partial edits in the tree were
unaccountable and had to be discarded — any coverage number reported would have been invented). So: each worker
**writes its accounting artifact (its report file) as soon as its edits are done** — that artifact, not the
diff, is what makes the work keepable; on a death **keep only work with a completed report, discard the rest**
(redone work beats unaccountable coverage); dispatch in waves of **~4**, not 8.

**A worker's local verification can be weaker than the real gate — the manager runs the REAL build at every
batch boundary.** J-31's judges verified with `javac -proc:none`, which disables annotation processors, so
NullAway/ErrorProne were exactly what their check could not see; a "behaviour-neutral" rename produced 4 build
errors only the manager's Gradle build caught. A cheap worker check never substitutes for the gate's build.

**On a red run, DELEGATE the diagnosis — never pull run logs into the manager.** Dispatch `e2e-driver` (or a
throwaway triage worker) to read the failing JOB log + server log + the working tree and return ONLY {failing
job, root cause: status / constraint / `file:line`, is a fix already uncommitted in the tree?, the fix-shaped
next task}. Anchor the next task on that cause, never on the test diff (J-9 twice anchored wrong off the spec
diff; the real causes were in the job logs). **Before calling a gate-red a pre-existing flake, confirm the job
is GREEN on `main`** — green on `main` + red on-branch is JOURNEY-CAUSED, never a flake (J-12a). **For a
migration-fidelity red, have the triage MINE the run's traces/artifacts for the ACTUAL migrated values** (`gh
run download`) — never a derived expected value; the real gate refutes those (J-27). Fidelity reds **cluster**.

**Run a full-repo `./gradlew check` + the full mock-e2e suite at the BACKEND-batch boundary, AND the full `pnpm
test` at the FRONTEND-batch boundary, BEFORE §4.** Per-task workers verify FOCUSED tests (the right commit bar);
cross-cutting regressions surface only in the full suite: `cpdRatchet`, a shared spec ANOTHER journey asserts, a
`main`-push-only workflow, a shared web unit spec (J-13's new nav entry vs `nav-sections.spec.ts`'s exact-set
assertion). Once per batch — not per-task (too slow), not only at §4 (each miss costs a ~25-min real-idp cycle).
**When a task changes a SHARED surface** (a guard, the post-signup landing, an auth/tenant resolver, a spec
contract others assert), add a task to grep + update the cross-journey consumers up front — J-12a ate three gate
cycles here.

**Drive to the goal with tasks — never follow-ups.** A gap between the journey and its ACs (worker- or
gate-revealed) becomes **another `T-NN`** until the done bar — never a follow-up story / new journey / "later…".
Exception: work with **significant overlap with an already-roadmapped journey** stays that journey's (note it
for `/do-plan`; don't build here).

### 3a — Autonomous re-plan on overflow

A worker returns `status: overflow` + an `OVERFLOW:` note naming the seams — fired *before its first commit*, so
nothing partial is on the branch. Then (or when you spot a task failing the sizing gate), **re-plan without the
operator** (once): mark `~~T-NN~~ (split)`, insert lettered `T-NNa, T-NNb, …` (one seam each, dependency order;
re-run the sizing gate on each), re-dispatch `T-NNa`. **Loop guard:** only an un-lettered `T-NN` auto-splits; a
*lettered* overflow → **stop + escalate** (shape wrong → likely `/do-plan` re-carve). Never re-dispatch an id
unchanged.

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

**Verify the gate JOB-level, on the FINAL sha.** A run-level green can be hollow: path-filtered `detect changes`
skips every job on a workflow/docs-only delta (`required` green over NOTHING, J-7); cancel-on-push can kill the
only real run; and the **fanout silently no-ops on integration branches** when its legacy C#/Mono build fails on
a cold cache (warm-cache `main` masks it — J-8/J-9 never ran their migrated done-bar on-branch,
[[project_fanout_legacy_build_cold_nuget]]). Confirm the jobs EXECUTED (not skipped, not build-failed) for the
sha you ship — **for a migration journey `fan-out parity` is a HARD merge gate**: dispatch it on the branch +
job-verify green, and again after any cancel/re-push. Executed ≠ proving THIS journey: confirm the proof job
logged `running per-push proof spec for J-NNN` with `baseline=false`, and the deployed bookmark's `<h1>` reads
the journey id — a baseline resolve (spec still all-`fixme`) renders an "unknown" page, not a done journey.
[[project_false_green_derive_fallback]]

**A gate must cover its own inputs.** When you add or change a gate, verify its path filter covers **every input
it validates**, and prefer an always-run check in a graph-root job with no `if:`/`needs:`, ahead of any path
filter (J-31 put `comment-strip --check` in the `changes` job for exactly this). J-31 found `extract.yml`
path-filtered to `alpenflight/database/extract/**` while `tenant-rules.yaml` sat one directory up: it never
triggered its own gate, so that test stayed red ~3 months, the last green `main` run predating the break.

**Dev-time proof = THIS journey only; full green only at the gate** ([[feedback_dev_time_test_strategy]]). Per
push runs only the journey's OWN spec(s) (T-02 set this up); prior journeys run mock-IdP; the full cross-journey
real-idp regression runs nightly + once at the §4 gate (**nothing skipped**), **sharded + KC-26 specs
quarantined** so it doesn't blow the step timeout — it must stay fast while keeping cross-journey coverage.
**Gallery deploy survives a red case**: capture before deep assertions; gate deploy on `!cancelled()`.

**Gallery model — ONE page, the CURRENT journey only** ([[feedback_proof_gallery_per_journey_one_bookmark]]):
paired legacy↔AlpenFlight shots + pass video + migration round-trip; no all-journeys index / history pages /
sub-path split (merged proof lives in PRs). **Verify the DEPLOYED artifact, not the spec pass** (recurred ~4× in
J-6): curl the bookmark + every asset (200); a caption claims only what the spec asserts (§5). On a
repeatedly-red proof, re-deploy + surface the gallery to the operator BEFORE retrying — suspect the screen
shape, not just the test ([[feedback_surface_proof_early_on_repeated_failure]]). **The in-flight window is the
per-PR PREVIEW bookmark** (`…/alpenflight/proof-preview/<branch>/`, deployed on the `pull_request` event) — give
the operator THAT link. The canonical `…/alpenflight/proof/` deploys only on `main` pushes, where the derive
resolves to the J-0 baseline → it always reads "proof — unknown"; expected, NOT a clobbered gallery (J-12b).
`workflow_dispatch` deploys nothing.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries an inline
`@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff** at the gate. Spawn
`gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + that list; undeclared mocks, stubs, un-wired
layers, tenancy leaks, **gerrymandered inner-loop fixtures** (a mock value the real backend never returns — J-13
`httpStatus:200` on a success row vs real null → green mock, red gate; [[feedback_honest_inner_loop_fixtures]])
→ **chain is red** → new tasks, return to step 3. Honor the wallclock budget (shard / reuse snapshots).

### 5 — Document + green PR

Prune the journey body to load-bearing decisions ONLY (code + **git history** are the source of truth — delete
file trees, signatures, resolved threads, **and the per-task implementation notes**; keep the frontmatter, ACs,
contracts, parity exclusions, the task checklist, a short Outcome). The journey file is a contract, not a
changelog (J-7 bloated to 719 lines of per-task prose). Flip `status: done` + `done_at`; mark each `rolls_up`
story `rolled_up_into: J-NNN` **AND flip its `status: todo → done`** (stamping the pointer alone left 24 shipped
stories lying as `todo` through J-11; operator 2026-06-24); a story split across journeys (`rolled_up_into: [J-a,
J-b]`) flips only once the LAST merges. **Retire the shipped journey from the forward backlog** (operator
2026-06-25): `_ORDER.md` is FORWARD-ONLY — remove **ALL** of its forward refs, the roadmap-table row AND its `##
Per-journey Playwright contract` one-liner (`grep "J-NNN" _ORDER.md` → only a historical coverage-map ref may
remain; J-13 removed only the row and stranded its contract line); append `- J-NNN — <title> — #PR`
(newest-first) to `_SHIPPED.md`; `git mv` the journey file to `stories/implemented/`. `ci.yml`'s proof-spec +
mock-filter derives search BOTH dirs — a journey file resolving in NEITHER is a hard CI fail, not a baseline
fall-back (J-17). [[project_false_green_derive_fallback]]
**Docs-only head guard** (J-27, J-12b; operator 2026-06-25 keeps the dev-time `docs_only` skip — fix it
procedurally, not in ci.yml). A docs-only head makes `detect changes` skip the heavy lane and `required` greens
over the skips — which is why the done bar demands EXECUTED jobs, not a `pass` rollup. Mergeable needs: (1) the
heavy lane job-level GREEN on the last CODE-bearing commit (§4); (2) the finalization commit **truly docs-only**
(`git diff --stat <proof-head>..HEAD`); (3) migration journeys — `fan-out parity` EXECUTED green on that head.
Then tell the operator the docs-head skips are expected.

**Troubleshooting mode — iterate on GitHub, not on this box** (operator 2026-08-02). When diagnosis needs
repeated heavy runs, push a temporary workflow running only the job under diagnosis, so it proceeds IN PARALLEL
with local coding instead of monopolising a 2-core box (J-15: Gradle alongside Playwright → 13 phantom e2e
failures + a 3× slowdown). Fail-closed: the mode is a committed marker (`.ci-troubleshooting`, repo root) making
`ci.yml` skip the heavy lane AND `required` hard-FAIL, so the PR cannot go green while it exists; the `required`
job re-reads the marker itself, so its red survives any rewiring of the job graph. Before handover: delete the
marker, **fold every test the scratch runs introduced into the normal CI**, confirm the heavy lane then ran
job-level green.

**Reconcile the PR's OWN checklist** — the **AC checklist** is a DIFFERENT list from the journey file's task
checklist, and ticking one is not ticking the other (J-15 reported "all tasks done" on 13/13 ticked tasks while
the PR still showed 7 unticked ACs). **Tick an AC only against a NAMED assertion** — the spec/IT plus the
assertion in it that proves the AC. Where the gate proved it more cheaply than the AC words it — at IT level
rather than through the screen, by a weaker assertion, or not at all — **qualify it ON THE LINE** (PR #249 is the
worked example) and file the gap as a rider. An unqualified tick on a cheaper test than the AC names is a false
done-bar. **A gallery / `proofVideo` caption is held to the same bar**: it may claim only what the spec asserts
(J-31 shipped a `[money-proof]` caption advertising balance arithmetic over an inequality assertion, on an
accounting surface). `gh pr ready`. Give the operator the **proof-gallery link** (in the gallery, not
SendUserFile'd into chat — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked-seams list.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity assertion only passes by
changing behavior; this journey breaks another's green; a `depends_on` artifact is missing despite the dep being
done; ported legacy code has an apparent bug (never silently fix); an AC is unmeetable; a FK-closure forces
binding ANOTHER journey's migration entity (don't bind it — the carve missed a dep, re-scope/defer, J-10
ARTICLE→J-11); or `gap-hunter` flags a blocker needing a contract/ADR/sacred-cow change. Default next:
`/do-retro` captures the lesson; `/do-plan` re-carves if the shape was wrong.

**A "parity exclusion" must be cosmetic or proven-unreachable** (J-9b credit): legitimate ONLY if cosmetic OR
the worker cites the data/config making it unreachable. A *reachable* behavioral divergence — especially on a
money/safety surface (billing, accounting, hours/limits) — is a suspected ported-legacy bug → **escalate it,
never bury it** in a parity-exclusion line or a `_BOYSCOUT.md` rider (J-9b nearly shipped a reachable
over-credit as a `[CREDIT-MIN-TIER]` rider; the operator caught it at review). Fix-or-keep is the operator's
call, recorded in ADR 0026 if it's an intentional divergence.

## Quality bar

- One journey per invocation; `carved: false` is a hard bail; every task runs in a fresh worker context. Green
  bar = the real full-chain run; declared+signed mocks only, undeclared mock = red.
- Schema structural, business rules on aggregates (ADR 0022 §2). Tasks commit to `integration/J-NNN`; the
  **manager pushes**; never merge red; one PR per journey. Prune before done; cite file:line/PR#/J-ID.
- **No comments — put the understanding in the name.** Not "why-only": zero human-written prose in code, specs
  or YAML. A comment you want to write is a symbol/test/constant needing a longer name, or an
  ADR/`docs/modernization/` entry; long names are fine. Survivors: tool-parsed directives (`eslint-disable*`,
  `@ts-*`, `prettier-ignore`, `noinspection`, `language=`, `@formatter:off/on`, shebangs) and `// ext:` at an
  externally-owned boundary *where no machine-enforced pin exists*. History → the commit message. See `/comment-strip`.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey `status: done`, real chain green, proof in the gallery, one PR ready. Operator merges → `/do-plan next`.
