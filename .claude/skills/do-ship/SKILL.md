---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a
**manager**: decide the task list, run each task in its own fresh worker context,
then run the proof-chain gate and open the journey PR. The operator merges.

**Manager context budget — reach §4 review LEAN.** Saturating context before the gate is a
process failure, not bad luck. Hold ONLY the task checklist + one line per task. The manager
**never reads token-heavy material into its own context** — legacy source, CI/gate logs,
gradle/Playwright output, `git diff`, large files. Delegate every such read to a subagent
(`legacy-oracle`, `e2e-driver`, `gap-hunter`, or a throwaway `Explore`/worker) that returns ONLY
the distilled conclusion — root cause + `file:line` + the fix-shaped next task, ≤150 words, no
pastes. About to open a log or a legacy file? Dispatch instead.

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
`index_repository` — don't retain its output). Read ONLY the journey spec + its `rolls_up`
stories (small docs). **Never read legacy source into the manager** — dispatch `legacy-oracle`
(parity-sensitive screens) or a throwaway `Explore` for the legacy screen(s); its distilled
output is the worker input. Write an ordered `## Tasks` checklist into the
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
journey's surface (or stale infra riders) into `T-NN`s sized per the gate — that's how `/do-retro`
fixes reach the proof loop. **As each rider ships, DELETE its bullet from `_BOYSCOUT.md`** (delete the
line — never mark it `✅`/struck-through and leave it; shipped work lives in git + the PR). The file
holds only pending work and must shrink, not grow.

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
> push). Return only (≤150 words, `file:line` not pastes — no diffs, no logs, no file dumps):
> status (done/overflow/escalated/blocked), commit subjects, ACs touched, escalations."

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
+ AC checklist). Watch CI in background by **conclusion only** (`gh run view` status — never `--log` into the
manager); a red run becomes the next task (diagnosis delegated, below), not a blocked wait;
superseding an in-flight per-push run with the next push is fine — don't stall on it.

**On a red run, DELEGATE the diagnosis — never pull run logs into the manager.** Dispatch
`e2e-driver` (or a throwaway triage worker) to read the failing JOB log + server log + the local
working tree and return ONLY {failing job, root cause: status / constraint / `file:line`, is a fix
already uncommitted in the tree?, the fix-shaped next task}. Anchor the next task on that returned
cause, never on the test diff (J-9: twice anchored wrong off the spec diff; the real causes — a 409
constraint, a temp-dir bug — were in the job logs, not the diff). **Before calling a gate-red a
pre-existing flake, confirm the job is GREEN on `main`** — a job green on `main` but red on the branch
is JOURNEY-CAUSED, never a flake (J-12a: the dashboard proof was mis-triaged as a cold-`goto` flake;
it was green on `main`, red on-branch = a regression the journey introduced). **For a migration-fidelity red, have the
triage MINE the run's traces/artifacts for the ACTUAL migrated values** (`gh run download`) — never assert an
ANALYTICAL/derived expected value; those get refuted by the real gate (J-27: shadowing / article-1060 /
recipient-FK all wrong). Fidelity reds **cluster** — expect a chain (fix → re-mine → next), budget for it.

**Run a full-repo `./gradlew check` + the full mock-e2e suite at the backend-batch boundary, AND the full
`pnpm test` (web unit suite) at the FRONTEND-batch boundary, BEFORE §4.** Per-task workers verify FOCUSED
tests (fast — the right commit bar); but cross-cutting regressions only surface in the full suite: the
`cpdRatchet`, a shared spec ANOTHER journey asserts (a changed landing/guard reds `signup.spec.ts` / the
dashboard proof), a `main`-push-only workflow, or a shared web unit spec (J-13: a new nav entry red-ed
`nav-sections.spec.ts`'s exact-set assertion — found via a CI web-build red, not the local `pnpm test`
that would have caught it free). Run each full check ONCE after its batch lands — not per-task (too slow),
not only at §4 (each miss costs a ~25-min real-idp cycle). **When a task changes a SHARED surface** (a guard, the post-signup landing, an
auth/tenant resolver, a spec contract other journeys assert), add a task to grep + update the
cross-journey consumers up front. J-12a ate three separate gate cycles on cpd + a stale signup
assertion + a `/start`-guard dashboard regression that one batch-boundary check would have caught.

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

**Drive the real-idp spec green LOCALLY first (DEFAULT), then gate on CI.** Before §4, `e2e-driver` drives
the journey's own real-idp spec to green on the LOCAL real-idp stack — never-run-step gaps surface in fast
local cycles, not one-CI-cycle-per-gap. §4 CI then CONFIRMS; it isn't where you discover gaps. **Real-idp
RUNS locally** — `bash alpenflight/ops/dev-up-full.sh` (KC + Mailpit) + `cd alpenflight/server && ./gradlew
bootRun` (backend on the **LAN PG** — source `~/.bashrc` `DATASOURCE_*`, NEVER a Docker/compose PG) + `cd
alpenflight/web && pnpm e2e:real-idp` ([[project_real_idp_runs_locally]], `e2e/README.md`). Skipping local
is escapable to CI-only ONLY when local is genuinely blocked, with a stated reason — it is NOT the default:
J-9 T-22 (4 gaps/6 commits) and J-13 (~5 gate cycles, spec first ran at the gate off a stale "OOM → CI-only"
belief) both burned the gate for want of a local loop. Do NOT `ALPENFLIGHT_TEST_FORCE_DOCKER` a local PG —
a CREATEROLE-needing IT skips-with-fail-loud locally + runs for real in CI container mode
([[feedback_no_local_postgres_for_tests]]).

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
**The in-flight window is the per-PR PREVIEW bookmark** (`…/alpenflight/proof-preview/<branch>/`, deployed on
the `pull_request` event) — give the operator THAT link. The canonical `…/alpenflight/proof/` deploys only on
`main` pushes, where the derive resolves to the J-0 baseline → it always reads "proof — unknown"; that's
expected, NOT a clobbered gallery (J-12b wasted an investigation here). `workflow_dispatch` runs deploy nothing.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries
an inline `@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff**
at the gate. Spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + the Mocked-seams
list; undeclared mocks, stubs, un-wired layers, tenancy leaks, **gerrymandered inner-loop fixtures**
(a mock value the real backend never returns — J-13 `httpStatus:200` on a success row vs real null →
green mock, red gate; [[feedback_honest_inner_loop_fixtures]]) → **chain is red** → new tasks, return
to step 3. Honor the wallclock budget — surface sharding/snapshot-reuse over silent re-runs.

### 5 — Document + green PR

Prune the journey body to load-bearing decisions ONLY (code + **git history** are the source of
truth now — delete file trees, signatures, resolved threads, **and the per-task implementation
notes**; keep the frontmatter, ACs, contracts, parity exclusions, the task checklist, a short
Outcome). The journey file is a contract, not a changelog — task history lives in commit messages,
not the body (J-7 bloated to 719 lines of per-task prose; don't). Flip `status: done` + `done_at`;
mark each `rolls_up` story `rolled_up_into: J-NNN` **AND flip its `status: todo → done`** (a shipped
story must not read `todo` — stamping the pointer alone left 24 shipped stories lying as `todo` through
J-11; operator 2026-06-24). If a story is split across journeys (`rolled_up_into: [J-a, J-b]`), flip it
to `done` only once the LAST of them merges; until then it stays `todo`.
**Retire the shipped journey from the forward backlog** (operator 2026-06-25): `_ORDER.md` is FORWARD-ONLY
— in the finalization commit, **remove this journey's row** from the `_ORDER.md` roadmap table, **append a
one-line entry** to `docs/modernization/stories/_SHIPPED.md` (`- J-NNN — <title> — #PR`, newest-first), and
**`git mv` the journey file to `docs/modernization/stories/implemented/`** (mirroring done `S-NNN` stories).
The journey's `parity_test`/contract still resolve from `implemented/`; the move is the LAST commit (docs-only
head, proof already green on the code head), so the in-flight derive — which only reads the ACTIVE journey from
`stories/` — is unaffected.
**Docs-only head guard** (J-27, J-12b): this finalization is usually a DOCS-only commit → it becomes the PR
head → `detect changes` gates the heavy lane on a per-push `docs_only` flag → build/e2e/proof all **skip** on
that head, and the `required` aggregate returns **success over the skipped deps**. So `gh pr checks --required`
= `pass` is **NOT proof** — it greens over skips; a side `gh workflow run ci.yml` (`workflow_dispatch`) makes a
*separate* run green but does NOT change the PR's own (skipped) checks, so it's not the gate either (operator
2026-06-25 chose to keep the dev-time `docs_only` skip — fix it procedurally here, not in ci.yml). To declare
the PR honestly mergeable: (1) the heavy lane already ran **job-level GREEN on the last CODE-bearing commit**
(the proof head — that's §4); (2) confirm the finalization commit is **truly docs-only** (`git diff --stat
<proof-head>..HEAD` touches only `docs/`/journey/story files) so the head-skip is legitimate (nothing untested
rode in on it); (3) for a **migration** journey, the `fan-out parity` job must have executed green on the proof
head (a docs head skips it — never let a skipped fanout read as a pass). Verify by reading the run's JOBS
(executed/green), never the `required` rollup. A docs-head's own skipped checks are then expected + safe — say
so to the operator rather than papering over them with a confusing side-run. [[project_false_green_derive_fallback]]
`gh pr ready`. Give the operator the **proof-gallery link** (in the gallery, not SendUserFile'd
into chat — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked-seams list.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity
assertion only passes by changing behavior; this journey breaks another's green;
a `depends_on` artifact is missing despite the dep being done; ported legacy code
has an apparent bug (never silently fix); an AC is unmeetable; a FK-closure forces
binding ANOTHER journey's migration entity (don't bind it — the carve missed a dep,
re-scope/defer the migration, J-10 ARTICLE→J-11); or `gap-hunter` flags a blocker
needing a contract/ADR/sacred-cow change. Default next: `/do-retro` captures the
lesson; `/do-plan` re-carves if the shape was wrong.

**A "parity exclusion" must be cosmetic or proven-unreachable** (J-9b credit). A deliberate
not-matching-legacy behavior is a legitimate parity exclusion / forward rider ONLY if it's
cosmetic OR the worker cites the data/config that makes it unreachable. A *reachable* behavioral
divergence — especially on a money/safety surface (billing, accounting, hours/limits) — is a
suspected ported-legacy bug → **escalate it, never bury it** in a parity-exclusion line or a
`_BOYSCOUT.md` rider. (J-9b nearly shipped a reachable over-credit / negative-invoice-line defect
as a `[CREDIT-MIN-TIER]` rider; the operator caught it at review. Fix-or-keep is the operator's
call, recorded in ADR 0026 if it's an intentional divergence.)

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
