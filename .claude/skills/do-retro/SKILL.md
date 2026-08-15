---
name: do-retro
description: Improve the suite from what shipping just taught you — tune the do-* skills/agents, propose ADR amendments, re-shape the backlog, do memory hygiene, file infra/efficiency journeys. A Scrum-style ceremony: reconstructs lessons from git + PR threads, then grills the operator. Purely manual. Trigger: /do-retro.
---

# do-retro — the suite's immune system

After a batch of journeys ship, digest what they taught and feed it back into
the tooling, the architecture, the backlog, and memory. This is the only
do-* skill that changes the suite itself.

**Scope boundary — workflow + stories only, never project code.** `/do-retro`
edits the do-* skills/agents, curates memory, and *files* stories +
ADR-amendment proposals. It does **not** touch application code, tests, specs,
or migrations — anything that needs a project-code change is **filed as a story**
for `/do-ship` to execute, never done here.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
Per directive 1: skills/agents exist to ship behavior — improve them when they
got in the way, not to polish prose.

**Search posture.** Default to MCP servers over raw grep: use the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`) for code/PR archaeology and the **codebase-memory-mcp** for
recalling and updating durable lessons. Fall back to `Grep`/`Glob` only when no
MCP server is connected.

## Cadence

**Purely manual, no breadcrumbs.** `/do-ship` writes no retro log — it stays
lean. When invoked, `/do-retro` reconstructs the lessons itself from git history
(commit messages, the shape of the diffs, fix-CI churn), merged PR threads
(review comments, escalations, gap-hunter findings), and the journeys that
landed since the last retro. Run it deliberately — end of day, after a batch,
before a release — not on a loop.

**It's a ceremony, not a solo pass.** Treat `/do-retro` as a **Scrum-style
retrospective**: the git/PR reconstruction is your *prep*; the live grill of the
operator (see "Ceremony" below) is the ceremony itself — where the operator
contributes the intent, priorities, and judgment calls you can't recover from
history. Reconstruct first so you arrive with concrete signals; then interview.

## What it digests

Scan the journeys shipped since the last retro. For each, look for the
**friction signals**:

- Operator interventions / escalations (a journey that needed a human call).
- `gap-hunter` catches (a hollow-green that slipped to the gate).
- Flaky or repeatedly-red full-chain runs (proof-chain fragility).
- Mocked-seam signoffs that recurred (a real seam the chain can't yet exercise).
- Wallclock-budget breaches (a slow loop nobody fixed).
- Re-carves (`/do-plan` had to re-shape a journey mid-flight → the carve
  heuristic was wrong).
- Task-boundary misses (a `/do-task` worker escalated, drifted past its one
  task, or a task was too big for a clean context → `/do-ship`'s task-list
  decomposition needs tuning).

## Ceremony: grill the operator

Once the friction signals are reconstructed, **interview the operator** to surface the
intent, priorities, and judgment calls that aren't in the history — then let their
answers shape what you fix, file, and defer. This is the retro's human half.

Method (embedded from `grill-me`):

- Ask **one question at a time** and wait for the answer — never batch.
- For **each** question, give your **recommended answer** + the trade-off, so the
  operator reacts to a concrete proposal, not a blank prompt.
- If a question is answerable from the codebase / git / PRs, **go find the answer
  instead of asking** — spend the operator's attention only on genuine judgment calls.
- Walk the decision tree: resolve upstream calls (e.g. testing posture, an open
  architecture decision) before the downstream ones they constrain.
- Drive each branch to a **shared, recorded conclusion** — that conclusion is the
  input to the fix/file/defer decisions below.

Frame the questions from the reconstructed signals + any open decisions a journey
left (a deferred design call, a recurring mocked seam, a re-carve). Aim for the few
that change what you do next; stop when the backlog + suite edits are unambiguous.

**Non-interactive run** (headless / `/loop` / scheduled): skip the grill, proceed from
reconstruction alone, and note in the report that the ceremony was skipped (no operator
present) — the lessons are then reconstruction-only.

## What it's allowed to change

1. **Tune the suite itself.** Edit `do-plan` / `do-ship` / `do-task` / `do-retro`
   SKILL.md and the four agents (`legacy-oracle`, `slice-carver`, `gap-hunter`,
   `e2e-driver`) where a recurring friction shows the instructions were wrong or
   thin. Keep skill files ≤ 300 lines, agents ≤ 150. Smallest edit that removes
   the friction.
2. **Propose ADR amendments.** When a journey revealed an architecture decision
   was wrong/incomplete, draft the amendment to `docs/modernization/adrs/` for
   operator approval — **propose, don't auto-apply** load-bearing ADR changes.
3. **Re-shape the backlog — boyscout-riders by default, never tiny stories.**
   Mechanical or bounded work (a bug fix, a one-line regex, a doc reconciliation, a
   guard test, deleting files — *however many*) does **NOT** get its own story/journey.
   Record it as a **boyscout rider** in `docs/modernization/stories/_BOYSCOUT.md` (one
   bullet: what + which seam + why), to be folded into the **next journey** that runs
   the gate — so the fix flows through the do-* workflow and produces a gate + gallery
   proof the operator can see. A tiny standalone story bypasses that proof loop and is
   an anti-pattern. **File a standalone journey ONLY for genuinely new vertical feature
   scope** (a missing screen, a re-carve of an oversized journey) — that, and only that,
   feeds `/do-plan`. Infra/efficiency work (proof-chain speedups, flake fixes, CI
   hardening) is a rider too unless it's a vertical slice in its own right.
   **The retro TRIAGES `_BOYSCOUT.md`** (operator 2026-08-15 — surface-only folding let it grow ~17 → 40
   riders / 30 sections): tag every rider's severity inline right after its `**[NAME]**` — `[S1]` security /
   tenancy / correctness / money, `[S2]` coverage gap / silent-failure risk, `[S3]` cosmetic / dead code /
   doc — which is the order `/do-ship` burns them down in; and DELETE riders that are genuinely won't-fix or
   superseded, stating the reason in the commit message. **Never delete a security-relevant rider.**
   *Shipped*-rider deletion stays `/do-ship`'s job (delete-on-ship): a surviving `✅`/`SHIPPED`/struck-through
   bullet is a `/do-ship` discipline slip — delete the straggler AND note it as a friction signal. Shipped
   work lives in git + the PR, never a kept-for-trace bullet.
4. **Memory hygiene** (via `codebase-memory-mcp` + `.claude/memory/`):
   - **Capture** durable, non-obvious lessons (feedback/project facts).
   - **Update** stale memories whose facts the recent work changed.
   - **Compact** redundant/overlapping memories into one.
   Follow the memory-file convention (frontmatter + `MEMORY.md` pointer).
5. **Record a helper-e2e pruning rider.** Scan (read-only) e2e specs tagged
   `@helper` with a `covered-by: <IntegrationTest>` pointer; for each, verify the
   named integration/unit test **exists and passes**. Record the now-redundant helpers
   as a **boyscout rider** in `_BOYSCOUT.md` (the deletion is project code → rides the
   next journey via `/do-ship`). Keeps the expensive e2e suite to wiring + happy paths
   as cheaper tests take over the logic/error cases. Never delete a spec here; never
   list un-tagged or wiring/happy-path specs.

## Retiring superseded tooling

The do-* suite once coexisted with the legacy `modernize-*` skills + 12 agents.
They were **retired in J-3 T-15** (the sunset rider): once do-* was proven across
J-0/J-0b/J-0c/J-1/J-2/J-3 — including the non-migration feature journeys — the
superseded `modernize-*` skills/agents were deleted and the `rolled_up_into:`
horizontal stories pruned. The 47 `implemented/` stories and their docs stay as
history. The lesson generalizes: when a do-* coexists with older tooling it
supersedes, prove it on 2-3 real journeys, then record the cleanup as a
**boyscout rider** (mechanical deletion, however many files — not its own journey)
that rides the next journey via `/do-ship`. Don't delete on day one.

## Procedure

1. Reconstruct the since-last-retro journey set (git log on `implemented/`,
   merged PRs). Note the date window in the report.
2. Extract friction signals per journey (above).
3. **Grill the operator** (the ceremony, above) on the signals + open decisions —
   one question at a time, a recommendation each; record the conclusions. Skip only
   on a non-interactive run.
4. For each recurring signal + grill conclusion, decide the smallest fix and which
   lever it belongs to (suite edit / ADR amendment / **boyscout rider** / new-scope
   journey / memory).
5. Apply suite edits + memory hygiene directly. **Draft** ADR amendments. Record
   fixes/deletions/docs as **boyscout riders** in `_BOYSCOUT.md`; file a standalone
   journey only for genuinely new vertical scope (don't implement either).
6. If a do-* skill has superseded older tooling and proven out on 2-3 journeys,
   record the retirement as a boyscout rider (see "Retiring superseded tooling").
7. **Land the output on a clean branch that rides the next journey.** `git fetch`
   first; commit the suite edits + `_BOYSCOUT.md` on a **fresh branch off the latest
   `origin/main`** — never onto a stale/merged branch you happen to be sitting on
   (prune dangling local pointers: `git branch -D` the merged `integration/*`). This
   branch becomes the **base the next `/do-plan` carve builds `integration/J-NNN` on**,
   so the riders + tuned skills flow onto that journey and merge with it (fix-forward —
   suite improvements ship through the same proof loop). Memory files live outside the
   repo (no commit). Don't squash-merge; the operator reviews the diff.
8. Report (below).

## Quality bar

- Every suite edit traces to a concrete friction signal — no speculative tuning.
- ADR amendments are proposed, never auto-applied.
- Backlog changes are filed as journeys, not implemented here.
- Memory edits follow the convention; redundant memories are merged, not piled.
- Skill ≤ 300 lines, agent ≤ 150 lines after edits.

## When done

Print: the journey window digested; friction signals found; the grill conclusions
(or that the ceremony was skipped — non-interactive); suite edits made (file +
one-line why each); the `_BOYSCOUT.md` triage (rider count before/after, the S1 list,
what was deleted + why); ADR amendments drafted (operator decides); backlog / infra /
efficiency journeys filed; memory captured/updated/compacted; whether any
superseded-tooling retirement rider was filed and why.

## Not in scope

Editing project code — application code, tests, specs, or migrations (filed as
stories for `/do-ship`); implementing the journeys it files (that's `/do-ship`);
carving the roadmap (that's `/do-plan`); merging PRs; auto-applying ADR changes.
