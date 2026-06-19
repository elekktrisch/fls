---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a
**manager**: decide the task list, run each task in its own fresh worker context
(no single conversation accumulates the whole journey), hold only lean summaries.
When the tasks are done, run the proof-chain gate and open the journey PR. The
operator merges.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md)
first — both directives govern. Schema is structural; business rules on aggregates.

**Search posture.** Default to MCP over raw grep: the IntelliJ MCP for code
search/navigation, **codebase-memory-mcp** for prior-art recall; fall back to
`Grep`/`Glob` only when no MCP server is connected.

## Shape

One journey at a time (vertical diffs collide), on its **own branch** `integration/J-NNN`;
tasks commit **directly** onto it, each in a **fresh-context `/do-task` worker**. On-demand:
`legacy-oracle` (exact legacy behavior), `e2e-driver` (proof chain), `gap-hunter` (attack the green at the gate).

## The done bar — a real, honest green

A journey is done only when its Playwright spec drives the **real UI** end to end and
passes — clean seed first, then real legacy data migrated into AlpenFlight (Postgres +
Keycloak). Extra done-bar requirements:

- **Migration journeys** (any carrying a mapper) need a **green real-export (fanout) run**, not just synth:
  synth bundles alias columns + never hit the producer SELECT against the real schema, so unwired bindings,
  column drift, missing dedupe, or FK-resolution bugs surface only at the real fanout (authored ≠ proven). The
  per-journey collision/orphan IT (§2) catches the common cases in `check` first.
  [[project_synth_bundle_doesnt_validate_producer_select]] [[verify_infra_is_run_not_just_authored]]
- **Legacy-replacing screens** produce paired legacy↔AlpenFlight screenshots + the legacy video in the gallery,
  PR link auto-posted — part of done. `e2e-driver` owns the capture.
- **New screens are chrome-reachable:** nav/link placed per legacy (+ role visibility), and the proof
  spec ENTERS through it — a URL-only screen is hollow (J-7 /flightreports miss). `gap-hunter` blocks it.

**Red is the work-list, not a wall.** Never done while red — a journey never merges red.
Synthetic / mocked-seam green is an inner-loop aid, **never** progress toward done — only
the real-chain green counts.

## Procedure

### 1 — Resolve + branch

Resolve `J-NNN` from arg or a `integration/J-NNN` branch. Bail if not `carved: true`
("run /do-plan J-NNN first") or not `status: todo` (in_progress → resume; done → refuse).
Every `depends_on` journey must be `done` + PR `MERGED`. `/do-plan` already pushed
`integration/J-NNN` (carve + `/do-retro` riders) — **`git fetch` + checkout + pull it**, don't
re-create. Flip `status: in_progress` + `started_at`; GitHub issue (`J-NNN: <title>`) if `gh`+remote.

### 2 — Decide the task list (stay lean)

Refresh the graph before recall (`detect_changes`; if drifted, incremental
`index_repository`). Read the journey spec + its `rolls_up` stories + the legacy
screen(s) it replaces. For parity-sensitive screens, dispatch `legacy-oracle` ONCE
(its output is a worker input). Write an ordered `## Tasks` checklist into the
journey file — `T-NN` ids, one-line scope each, dependency order. Default decomposition:

1. **T-01 — spec stub + scaffold the journey proof page.** Author the Playwright spec's
   structure + selectors + flow (thin assertions, commits the screen shape) AND scaffold the
   per-journey gallery page + link it from the persistent index — the operator's glanceable
   window must exist from task 1 and accumulate captures as screens land (standing slot).
2. **T-02 — move prior journeys to mock-IdP.** Scope the per-push gate so ONLY the
   journey-under-work runs heavy (real-idp); prior journeys run mock-IdP (full regression →
   nightly + the §4 gate). A standing slot so an unrelated heavy/flaky spec can't gate this journey.
3. **Vertical work-packages.** Migration → backend slice → frontend slice, split into tasks a
   fresh worker finishes cleanly (one entity / endpoint cluster / component) — sized per the gate.
   **Migration tasks:** when the next-schema has a UNIQUE/CASCADE the legacy lacks (`legacy-oracle` flags
   these), the task MUST ship a real-producer collision/orphan round-trip IT — so it reds in `check` (minutes), not the ~20-min fanout (J-6 23505/23503).
4. **Proof-chain contribution.** This entity's legacy seed + per-entity mapper.
5. **Final task — thicken spec** to full real assertions from the oracle.

**Pull boyscout riders.** Before finalizing, fold pending `_BOYSCOUT.md` riders touching this
journey's surface (or stale infra riders) into `T-NN`s sized per the gate; clear them as they
ship — that's how `/do-retro` fixes reach the proof loop.

**Sizing gate (pre-dispatch heuristic — every task).** Each `T-NN`: **one seam** (one aggregate+repo / one
resource's endpoints / one component-route / one migration / one spec edit — *'the domain layer' is not a task,
'the Booking aggregate' is*); **≤8 files, ≤5 new**, one logical change describable without 'and', **≤3 tests at
one layer**; **self-naming** (scope line names the files / ≤2 globs find them, else carve finer). A layer with N
aggregates = **N tasks**. When unsure split — early-finish is free, overflow costs a re-plan.
The checklist is your only durable state — workers and re-runs read it.

### 3 — Manager loop (fresh worker per task)

Ensure the working tree is on `integration/J-NNN`. For each pending task **in
order**, spawn ONE fresh worker subagent (clean context — that's the whole
point) to execute `/do-task` for that task:

> Agent (general-purpose): "Execute the `/do-task` playbook
> (`.claude/skills/do-task/SKILL.md`) for task `T-NN` of journey `J-NNN` on
> branch `integration/J-NNN`. <one-line task scope>. Commit directly to the
> branch. **Commit + report your SHA and RETURN — do NOT push** (the manager owns the
> push). Return only: status (done/overflow/escalated/blocked), commit subjects, ACs touched, escalations."

Run tasks **sequentially** (shared branch + working tree — parallel would
conflict). After each worker returns: if `status: overflow`, go to § 3a; else **`git push`
the worker's commit yourself** (you own the push — the worker committed but did NOT push;
this keeps push timing deterministic and stops workers hanging on in-flight CI), tick
`T-NN` in the checklist, keep its one-line summary, discard the detail. If a worker
**escalates** (parity/legacy-bug/unmeetable-AC/contract conflict), stop the loop and
surface to the operator per § Escalation. Do not push past a red task.

**Worker deaths (session limits, J-7 ×3).** A dead/limited worker NEVER resumes — `TaskStop` it
(zombies re-arm with STALE instructions); treat its in-tree work as UNVERIFIED DRAFT for a fresh
finisher whose prompt restates the current environment rules + the full verification protocol.
Decompose toward SMALLER tasks — a death loses less (operator, J-7 retro).

Push at task boundaries; after the first locally-green backend task, open a **draft PR**
(`gh pr create --draft --base <integration-line> --head integration/J-NNN`, body `Closes #N`
+ AC checklist). Watch CI in background; a red run becomes the next task, not a blocked wait;
superseding an in-flight per-push run with the next push is fine — don't stall on it.

**On a red run, get the ACTUAL cause first** — the failing JOB's real error (response status, server
log, the violated constraint) + the local working tree (a fix may already be uncommitted there) —
BEFORE theorising from the test diff or dispatching a re-diagnosis (J-9: twice anchored wrong off the
spec diff; the real causes — a 409 constraint, a temp-dir bug — were in the job logs).

**Surface the gallery EARLY** ([[feedback_surface_proof_early_on_repeated_failure]]) — the operator's only
glanceable window for a wrong screen shape. T-01 scaffolds it; give the link at first captures. On a
repeatedly-red proof, re-deploy + surface it before retrying — suspect the screen shape, not just the test.

**Drive to the goal with tasks — never follow-ups.** A gap between the journey and its ACs
(worker- or gate-revealed) becomes **another `T-NN`** until the done bar — never a follow-up
story / new journey / "later…". Exception: work with **significant overlap with an
already-roadmapped journey** stays that journey's (note it for `/do-plan`; don't build here).

### 3a — Autonomous re-plan on overflow

A worker returns `status: overflow` + an `OVERFLOW:` note naming the seams — fired *before its
first commit*, so nothing partial is on the branch. Then (or when you spot a task failing the
sizing gate), **re-plan without the operator** (once): mark `~~T-NN~~ (split)`, insert lettered
`T-NNa, T-NNb, …` (one seam each, dependency order; re-run the sizing gate on each), re-dispatch
`T-NNa`. **Loop guard:** only an un-lettered `T-NN` auto-splits; a *lettered* overflow → **stop +
escalate** (shape wrong → likely `/do-plan` re-carve). Never re-dispatch the same id unchanged.

### 4 — Proof-chain gate

**Drive the real-idp spec green LOCALLY first, then gate on CI.** Before §4, `e2e-driver` drives the
journey's own real-idp spec to green on the LOCAL real-idp stack — never-run-step gaps surface in fast
local cycles, not one-CI-cycle-per-gap (J-9 T-22: 4 sequential gaps over 6 commits, spec first ran at
the gate). §4 CI then CONFIRMS; it isn't where you discover gaps.

When every task is ticked + the spec is locally green, `e2e-driver` runs the CI gate: the full chain
(legacy seed → migrate → Keycloak → real Playwright, both fidelities green, video on pass) + — for a
**migration journey (any mapper)** — a **`fan-out parity` JOB green on the FINAL sha** (not just synth).
CI: `alpenflight-proof` (required, synth) + the `fanout` run.

**Verify the gate JOB-level, on the FINAL sha.** A run-level green can be hollow: path-filtered
`detect changes` skips every job on a workflow/docs-only delta (`required` green over NOTHING, J-7);
cancel-on-push can kill the only real run; and the **fanout silently no-ops on integration branches**
when its legacy C#/Mono build fails on a cold cache (warm-cache `main` masks it — J-8/J-9 never ran
their migrated done-bar on-branch, [[project_fanout_legacy_build_cold_nuget]]). Confirm the jobs
EXECUTED (not skipped, not build-failed) for the sha you ship — **for a migration journey the `fan-out
parity` job is a HARD merge gate**: dispatch the fanout on the branch + job-verify it green, never
merge on a skipped/build-failed fanout. After any cancel/re-push, dispatch + job-verify a fresh run.
[[false_green_derive_fallback]]

**Dev-time proof = THIS journey only; full green only at the gate** ([[feedback_dev_time_test_strategy]]).
Per push runs only the journey's OWN spec(s) (T-02 set this up); prior journeys run mock-IdP; the full
cross-journey real-idp regression runs nightly + once at the §4 gate (**nothing skipped**), **sharded +
KC-26 specs quarantined** so it doesn't blow the step timeout (the gate keeps cross-journey coverage —
a journey can't merge if it broke a prior one — but must stay fast). **Gallery deploy survives a red
case**: capture before deep assertions; gate deploy on `!cancelled()`.

**Gallery model — ONE page, the CURRENT journey only** ([[feedback_proof_gallery_per_journey_one_bookmark]]).
The stable bookmark renders ONLY the in-flight journey (paired legacy↔AlpenFlight shots + pass video +
migration round-trip); no all-journeys index / history pages / sub-path split (merged proof lives in PRs).
**Verify the DEPLOYED artifact, not the spec pass** (recurred ~4× in J-6): curl the bookmark + every asset
(200). `e2e-driver` owns the deploy detail; GALLERY-SIMPLIFY collapses the old plumbing to this.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries
an inline `@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff**
at the gate. Spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + the Mocked-seams
list; undeclared mocks, stubs, un-wired layers, tenancy leaks → **chain is red** → new tasks, return
to step 3. Honor the wallclock budget — surface sharding/snapshot-reuse over silent re-runs.

### 5 — Document + green PR

Prune the journey body to load-bearing decisions ONLY (code + **git history** are the source of
truth now — delete file trees, signatures, resolved threads, **and the per-task implementation
notes**; keep the frontmatter, ACs, contracts, parity exclusions, the task checklist, a short
Outcome). The journey file is a contract, not a changelog — task history lives in commit messages,
not the body (J-7 bloated to 719 lines of per-task prose; don't). Flip `status: done` + `done_at`;
mark `rolls_up` stories `rolled_up_into: J-NNN`.
`gh pr ready`. Give the operator the **proof-gallery link** (in the gallery, not SendUserFile'd
into chat — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked-seams list.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity
assertion only passes by changing behavior; this journey breaks another's green;
a `depends_on` artifact is missing despite the dep being done; ported legacy code
has an apparent bug (never silently fix); an AC is unmeetable; or `gap-hunter`
flags a blocker needing a contract/ADR/sacred-cow change. Default next:
`/do-retro` captures the lesson; `/do-plan` re-carves if the shape was wrong.

## Quality bar

- One journey per invocation; `carved: false` is a hard bail. Every task runs in a fresh worker context.
- Green bar = the real full-chain run; declared+signed mocks only, undeclared mock = red.
- Schema structural, business rules on aggregates (ADR 0022 §2). Tasks commit to `integration/J-NNN`;
  the **manager pushes**; never merge red; one PR per journey. Prune before done; cite file:line/PR#/J-ID.
- **Self-explanatory code, why-only comments.** No what/narration/history/task-attribution comments
  in code, specs, or YAML (no `T-NN:`/`J-NNN`/"legacy stored…"/"this masks the race…"); a rare short
  *why* only when genuinely non-derivable, preferred as a named symbol / test name / ADR ref. History
  belongs in the commit message + git, never in code or the journey body.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey `status: done`, real chain green, proof in the gallery, one PR ready on
`integration/J-NNN`. Operator merges; then `/do-plan next`.
