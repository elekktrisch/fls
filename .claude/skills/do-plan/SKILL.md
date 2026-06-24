---
name: do-plan
description: (Re-)shape the remaining backlog into vertical journey slices — one SPA screen/route each, provable by a green Playwright run. Maintains a thin value-ordered roadmap and deep-carves ONE journey JIT for /do-ship. Trigger: /do-plan [J-NNN | next].
---

# do-plan — vertical journey planning

Turn the horizontally-sliced backlog into **vertical journeys**. A journey is
one user-facing path — DB→domain→API→UI — provable by a single green Playwright
run. This skill keeps a thin roadmap of journeys and, on demand, deep-carves
the next one into a full slice the `/do-ship` skill can build.

**A journey is a Scrum sprint: ≥60% AlpenFlight feature improvement, ≤40% tech-debt/infra**
([[feedback_journey_is_a_60_40_sprint]]). Every journey leads with a real AlpenFlight feature
(the green-Playwright path above); pending riders + infra/flake/CI/proof-tooling cleanup fill the
remaining ≤40% by riding the journey's gate. **Pure tech-debt never earns its own journey** —
it delivers no AlpenFlight functionality; if a debt item is too big for one journey's 40% slot,
split it across the next 2-3 journeys' budgets. A standalone journey is *only* genuinely new
vertical AlpenFlight scope (a missing screen, or a re-carve of an oversized feature journey).

> **⏳ Debt-burndown window (updated /do-retro 2026-06-22 — FINAL LAP).** The original window
> (2026-06-14) overran its ~2-3 journeys without clearing its named riders — the journeys since were
> feature/bug-led (J-2b/J-2c/J-9b), so the inverted budget was never actually spent. Operator decision
> (2026-06-22): **the NEXT journey runs inverted (≥30% feature / ≤70% tech-debt), aimed squarely at clearing
> GALLERY-SIMPLIFY** (the operator's bookmark pain) — it still LEADS with a real feature + green-Playwright
> path. **After that one journey, revert to 60/40 and DELETE this marker;** WORKFLOW-SLIM + COMMENT-STRIP then
> ride feature journeys' ≤40% debt slots as normal (split across journeys if too big). (`_BOYSCOUT.md` details.)

**Budget for the unforeseen.** The gate always surfaces real work the carve can't see (hidden bugs, infra
surprises, parity gaps). Carve with explicit slack: a journey's task count growing from gate-revealed work is
expected + budgeted, not a re-carve trigger. Re-carve only when the journey's SHAPE is wrong, not when it
merely surfaces more tasks. (Reading the design reference up-front removes the biggest *avoidable* surprise.)

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
Per directive 1: a journey body is *just enough to ship behavior* — ACs grounded
in legacy code beat context paragraphs. Per [[feedback-derive-before-asking]]:
`AskUserQuestion` is a last resort; uncertainty becomes an `## Assumptions made`
line, not a blocking question.

**Search posture.** Default to MCP servers over raw grep: use the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`) for code/backlog search and the **codebase-memory-mcp** for
recalling prior decisions. Fall back to `Grep`/`Glob` only when no MCP server is
connected.

## Why this exists

The old `/modernize-decompose` sliced by layer, so nothing was provable until a
whole layer landed (~20 stories) and integration gaps surfaced as late rework.
Journeys slice the other way: each one is thin but *whole*, proven green before
the next starts. The existing `docs/modernization/stories/` (113 `todo`) are
the raw material; the 47 in `implemented/` are untouched history.

## Two modes

### Mode A — roadmap (no arg, or `--roadmap`)

One light pass. Dispatch `slice-carver` to propose the journey grouping:
which `todo` stories roll up into each screen-journey, what each journey's
Playwright spec must assert, where headless work attaches, the value+dependency
order, and which journey is **Journey-0** (the thinnest one that drags the full
proof chain into existence — pick an already-built low-risk screen).

Write the result as the journey roadmap — this **replaces `_ORDER.md`'s body**:
a sequenced list of journey titles + one-line screen map + `depends_on`. Do NOT
write full journey bodies here (they go stale). Roll-up stories stay where they
are; the roadmap references them by ID.

Surface to the operator: the sequence, the Journey-0 pick, any `escalate: true`
headless-homing decisions, and the list of horizontal stories now superseded
(esp. the migration lump — S-016/S-139/S-141/S-109 dissolve into per-journey
mappers + Journey-0). Operator adjudicates grouping/order; this is the one
review checkpoint.

### Mode B — carve one journey (`/do-plan J-NNN` or `/do-plan next`)

Deep-carve a single journey JIT, just before `/do-ship` needs it. `next` =
the first roadmap journey whose `depends_on` are all done.

1. Pull the journey's roll-up stories + their refinement; read the legacy
   screen(s) it replaces. **Read the design reference** `docs/modernization/design-reference/screens-<feature>.jsx`
   (the ADR-0024 pixel oracle) for this screen — bake its STRUCTURE into the ACs + "Spec
   must assert" so the screen is built to the design the FIRST time (avoids building one shape then
   redesigning to another). If no reference screen exists, say so explicitly in the journey file.
   Also scan `docs/modernization/stories/_BOYSCOUT.md` for
   pending riders that touch this journey's surface — note them in the journey file
   so `/do-ship` folds them into the task list (they ride forward, not as own stories).
2. For the load-bearing behavior the implementer can't derive from code alone,
   you MAY dispatch `legacy-oracle` now — but it's cheap to defer to ship time.
   Carve captures *shape + contract*; the oracle captures *exact behavior*.
3. Write the journey file (format below) with `status: todo`, `carved: true`;
   stamp `rolled_up_into: J-NNN` on each story it absorbs.
4. **Land the carve on `integration/J-NNN` and push** — so `/do-ship` resumes it,
   not re-creates it. Pick the base deliberately:
   - `git fetch origin` first. **Never carve off a stale/merged local branch** (the
     branch you're sitting on may have merged + been deleted on the remote).
   - Base on the **current integration line**: latest `origin/main` — *unless* an
     unmerged `/do-retro` just produced `_BOYSCOUT.md` + suite edits, in which case base
     on **that retro branch** so the riders + tuned skills ride this same journey (they
     merge with it — the fix-forward path). `git checkout -b integration/J-NNN <base>`.
   - **Squash-merge guard** ([[project_do_plan_carve_base_after_squash_merge]]): if the prior journey
     already **squash-merged** to `main`, the `/do-retro` branch is now divergent history (it carries the
     prior journey's pre-squash commits). Do NOT branch off it — base on `origin/main` and **cherry-pick the
     retro's net commit** on top (`git cherry-pick <retro-sha>`). Branching off the stale retro branch drags
     the squashed commits back in (J-6: a clean 11-file diff but 83 junk commits). Verify: `git diff
     origin/main <retro-sha>^ --stat` is empty → the squash == the retro's parent, so the cherry-pick is clean.
   - Commit the journey file + `rolled_up_into` stamps (carrying the retro commit if it's
     the base), then `git push -u origin integration/J-NNN`. Print the branch name.

Do **not** decompose into tasks here — that's `/do-ship`'s job at ship time
(with fresh full context on the current code), and each task runs in its own
clean-context `/do-task` worker on `integration/J-NNN` (now already created + pushed).
`/do-plan` stops at the journey file + its spec contract on that branch.

## Journey definition

- **Granularity:** **at least one** SPA screen/route driven end to end — a visible result the green
  Playwright run can show — not a single atomic action. A coherent multi-screen feature MAY ride one
  journey (don't over-split a feature just to hit one screen); the operator may also choose to ship the
  dominant screen first and defer siblings to a follow-up journey (operator 2026-06-23,
  [[feedback_journey_min_one_screen_not_exactly_one]]). Maps to a feature folder per
  `alpenflight/web/CLAUDE.md` §2.
- **Headless work** never gets its own journey. It's pulled in by the screen
  that uses it, in this order: real product screen → admin screen →
  test-env-only admin/test affordance → else propose options + escalate.
- **Migration** is per-journey: name the legacy entity/table the journey
  migrates; greenfield/freemium journeys mark it N/A.
- **A migration journey owes its FK-dependency entities.** Before setting `depends_on`, check the
  migrated entity's new-schema FKs: any `NOT NULL`/`RESTRICT` FK to an entity migrated by ANOTHER
  (later/unbuilt) journey makes THAT journey a `depends_on` — else the binding's FK-target closure forces
  a worker to bind the other journey's entity unscoped, regressing the whole fanout (J-10: `DeliveryItem.
  article_id` → ARTICLE, J-11's entity → the Delivery migration had to defer to J-10b after J-11). If the
  dependency journey isn't built yet, carve the migration half as its own later journey.
  **Indirect tenancy counts as a dependency too** (J-9b): an entity carrying no `club_id` whose tenant-scoping
  read *pivots through* another entity (`PersonFlightTimeCredit` scoped via `Person→PersonClub`) owes that
  pivot entity's migration as a `depends_on` — even with no direct NOT-NULL FK column — else the migrated rows
  are invisible to every `@TenantId`-filtered query. Check the repo's tenant-scoping query, not just the FK
  columns. (J-9b found PERSON_CLUB had never been migrated — a latent J-4 gap — only at the gate.)

## Journey file — `docs/modernization/stories/J-NNN-<slug>.md`

```yaml
---
id: J-NNN
title: <screen/route name>
epic: E-NN            # the feature area it belongs to
status: todo
journey0: false       # true only for the chain-bootstrap journey
carved: false         # flips true in Mode B
depends_on: [J-NNN, ...]
rolls_up: [S-NNN, ...]  # horizontal stories absorbed (refinement preserved)
acceptance:           # what the green Playwright run asserts
  - <one-line testable condition, [happy]/[key-error]/[edge] tagged>
screen: <SPA route>   # replacing legacy <screen/path>
headless_pulled_in: <capability → screen/affordance, or none>
migration: <legacy entity/table, or "N/A — greenfield">
parity_test: <e2e spec path>
adr_refs: [...]
---

## Context
Why this journey exists + the user-facing value. One paragraph.

## Spec must assert
The happy path + key error cases the Playwright spec proves, grounded in legacy
behavior (cite file:line where parity matters). This is the contract.

## Notes
Headless homing decision, migration shape, sacred cows, assumptions. When hinting
likely task seams (non-binding, for `/do-ship`), name them at **seam granularity** —
one aggregate / one component / one resource's endpoints per hint, never 'the
backend' or 'the domain layer' — so ship-time decomposition starts already-sized.
```

## Marking superseded stories

When a journey fully absorbs a horizontal story, stamp the story
`rolled_up_into: J-NNN` in its frontmatter (don't delete — `/do-retro` prunes
later). Leave `implemented/` alone.

## Quality bar

- Every journey is provable by one green Playwright run. If you can't name what
  the spec asserts, the journey isn't carved.
- Every journey delivers at least one screen/route (a visible, provable result). A coherent
  multi-screen feature may stay one journey; split only when the screens are genuinely independent
  features (or the operator wants the dominant screen shipped first).
- Headless work always has a screen home or an escalation — never a layer-slice.
- Journey-0 exists and is the thinnest chain-bootstrap, before any feature journey.
- `AskUserQuestion` count ≈ 0. Roadmap order, grouping, parity placement: pick + record.

## When done

- **Mode A:** print the sequenced roadmap, Journey-0 pick, escalations,
  superseded-story list, `## Assumptions made`. Tell operator: review, then
  `/do-ship J-NNN`.
- **Mode B:** print the carved journey's ACs + spec contract + migration shape +
  the **pushed `integration/J-NNN` branch** (and its base — main, or a ridden retro).
  Tell operator: `/do-ship J-NNN` (it resumes that branch).

## Not in scope

Building anything (that's `/do-ship`), refining `implemented/` stories, touching
the 47 done. Suite/ADR/backlog *learning* changes are `/do-retro`'s. (Creating +
pushing the empty `integration/J-NNN` with just the carve is placement, not building.)
