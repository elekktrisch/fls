---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a
**manager**: you decide the task list, then run each task in its own fresh
worker context so no single conversation accumulates the whole journey and
drifts into the dumb-zone. You hold only lean summaries; the heavy work lives
in the workers. When the tasks are done, you run the proof-chain gate and open
the journey PR. The operator merges.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md)
first — both directives govern. Schema is structural; business rules on aggregates.

**Search posture.** Default to MCP over raw grep: the IntelliJ MCP for code
search/navigation, **codebase-memory-mcp** for prior-art recall; fall back to
`Grep`/`Glob` only when no MCP server is connected.

## Shape

One journey at a time (no fleet — vertical diffs collide), on its **own integration branch**
`integration/J-NNN`; tasks commit **directly** onto it, each in a **fresh-context `/do-task` worker**. You
stay lean: decide the list, dispatch workers, collect one-line results, gate, PR. On-demand escalation tools:
`legacy-oracle` (exact legacy behavior), `e2e-driver` (proof chain), `gap-hunter` (attack the green at the gate).

## The done bar — a real, honest green

A journey is done only when its Playwright spec drives the **real UI** end to end and
passes — clean seed first, then real legacy data migrated into AlpenFlight (Postgres +
Keycloak). Two extra done-bar requirements:

- **Migration journeys** (any carrying a mapper) need a **green real-export (fanout) run**, not just synth:
  synth bundles alias columns + never hit the producer SELECT against the real schema, so unwired bindings,
  column drift, missing dedupe, or FK-resolution bugs surface only at the real fanout (authored ≠ proven). The
  per-journey collision/orphan IT (§2) catches the common cases in `check` first.
  [[project_synth_bundle_doesnt_validate_producer_select]] [[verify_infra_is_run_not_just_authored]]
- **Legacy-replacing screens** produce paired legacy↔AlpenFlight screenshots + the legacy video in the gallery,
  PR link auto-posted — part of done. `e2e-driver` owns the capture.

**Red is the work-list, not a wall.** Never done while red; the bar is self-imposed and
absolute. **A journey never merges red.** Synthetic / mocked-seam green is an inner-loop
aid, **never** progress toward done — only the real-chain green counts.

## Procedure

### 1 — Resolve + branch

Resolve `J-NNN` from arg or a `integration/J-NNN` branch. Bail if not `carved: true`
("run /do-plan J-NNN first") or not `status: todo` (in_progress → resume; done → refuse).
Every `depends_on` journey must be `done` + PR `MERGED`. `/do-plan` already created + pushed
`integration/J-NNN` (carve + `/do-retro` riders) — **`git fetch` + checkout + pull it**, don't
re-create (only create off the integration line if absent). Flip `status: in_progress` +
`started_at`; create a GitHub issue (`J-NNN: <title>`) if `gh`+remote.

### 2 — Decide the task list (stay lean)

Refresh the graph before recall: run `detect_changes` and, if drifted,
`index_repository` (incremental) — the integration line may have moved since the
last index. Read the journey spec + its `rolls_up` stories + the legacy screen(s)
it replaces. For parity-sensitive screens, dispatch `legacy-oracle` ONCE to get the
behavior oracle (its output is a worker input, not something you internalize in
detail). Then write an ordered `## Tasks` checklist into the journey file —
`T-NN` ids, one-line scope each, dependency order. Default decomposition:

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

**Pull boyscout riders.** Before finalizing, fold pending `_BOYSCOUT.md` riders that
touch this journey's surface (or stale infra/cleanup riders) into `T-NN`s sized per the
gate, and clear them from the file as they ship — that's how `/do-retro` fixes reach the
proof loop.

**Sizing gate (pre-dispatch heuristic — every task).** Each `T-NN`: **one seam** (one aggregate+repo / one
resource's endpoints / one component-route / one migration / one spec edit — *'the domain layer' is not a task,
'the Booking aggregate' is*); **≤8 files, ≤5 new**, one logical change describable without 'and', **≤3 tests at
one layer**; **self-naming** (scope line names the files / ≤2 globs find them, else carve finer). A layer with N
aggregates = **N tasks**. When unsure split — early-finish is free, overflow costs a re-plan.

This list is your only durable state — workers and re-runs read it.

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

Push at task boundaries; after the first locally-green backend task, open a
**draft PR** (`gh pr create --draft --base <integration-line> --head
integration/J-NNN`, body `Closes #N` + AC checklist). Watch CI in background;
a red CI run becomes the next task, not a blocked wait. Superseding an in-flight per-push run with
the next push is fine (scoped/fast; the new run re-validates) — don't stall on it.

**Surface the gallery EARLY, not at the gate** ([[feedback_surface_proof_early_on_repeated_failure]]) — it's
the operator's only glanceable window for a wrong screen shape before it costs reopens. T-01 scaffolds it;
give the link once the spec produces its first captures; refresh as tasks land. On a repeatedly-red proof,
re-deploy + surface the current gallery before retrying, and suspect the screen shape, not just the test.

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

When every task is ticked, `e2e-driver` runs the gate: the full chain — legacy seed → migrate →
Keycloak → real Playwright — both fidelities green, **video retained on pass**. It produces the
done-bar demonstrability (above): the legacy video + paired screenshots + auto-posted PR gallery
link (greenfield is AlpenFlight-only), and — for a **migration journey** — a **green real-export
`fanout` run** (not just synth). CI: `alpenflight-proof` (required, synth) + the `fanout` run. For
**Journey-0** the gate work *is* the tasks: stand up the thinnest whole chain.

**Dev-time proof = THIS journey only; full green only at the gate** ([[feedback_dev_time_test_strategy]]).
Per push runs only the journey's OWN spec(s) — real-idp + mock-e2e scoped to it (T-02 set this up); prior
journeys run mock-IdP. Full cross-journey real-idp regression runs nightly + once at the §4 gate (**nothing
skipped**). **Gallery deploy survives a red case**: capture each screenshot as its container renders, before
deep assertions; gate deploy on `!cancelled()`. Refresh the journey proof page continuously during dev.

**Gallery model — ONE source per journey** (J-6 T-17). The per-journey page is complete every push:
it pairs the **legacy reference screenshots captured ONCE and committed** (legacy is frozen — captured
at T-01/T-13 under `e2e/legacy-reference/<feature>/`, never reaped) against the **fresh AlpenFlight
captures** + videos. No per-push-vs-fanout page split, no freshest-wins tie-break; the fanout owns the
migration round-trip proof, separate from the visual pairing. **Verify the DEPLOYED artifact, not the
unit/spec pass** (a green generator test while the deployed page was wrong recurred ~4× in J-6): curl
the deployed bookmark + page + every asset (200) before claiming it works — the structural post-deploy
guard (rider) enforces this so it can't drift again.

**Mock governance.** Happy + key-error run fully real. Any mocked seam (edge/error only) carries
an inline `@mocked: <seam> — <reason>` tag + a PR **"Mocked seams"** list + **one operator signoff**
at the gate. Spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + the Mocked-seams
list; undeclared mocks, stubs, un-wired layers, tenancy leaks → **chain is red** → new tasks, return
to step 3. Honor the wallclock budget — surface sharding/snapshot-reuse over silent re-runs.

### 5 — Document + green PR

Prune the journey body to load-bearing decisions (code is the source of truth now — delete
file trees, signatures, resolved threads; keep contracts, parity exclusions, the task
checklist). Flip `status: done` + `done_at`; mark `rolls_up` stories `rolled_up_into: J-NNN`.
`gh pr ready`. Give the operator the **proof-gallery link** (in the gallery, not SendUserFile'd
into chat — [[feedback_proof_in_gallery_not_chat]]) + the PR link + Mocked-seams list.
**Stop — the operator merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity
assertion only passes by changing behavior; this journey breaks another's green;
a `depends_on` artifact is missing despite the dep being done; ported legacy
code has an apparent bug (never silently fix); an AC is unmeetable; or
`gap-hunter` flags a blocker needing a contract/ADR/sacred-cow change. Default
next: `/do-retro` captures the lesson; `/do-plan` re-carves if the journey shape
was wrong.

## Quality bar

- One journey per invocation; `carved: false` is a hard bail. Every task runs in a fresh worker context.
- Green bar = the real full-chain run; declared+signed mocks only, undeclared mock = red.
- Schema structural, business rules on aggregates (ADR 0022 §2). Tasks commit to `integration/J-NNN`;
  the **manager pushes**; never merge red; one PR per journey. Prune before done; cite file:line/PR#/J-ID.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey `status: done`, real chain green, proof in the gallery, one PR ready on
`integration/J-NNN`. Operator merges; then `/do-plan next`.
