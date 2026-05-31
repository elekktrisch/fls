---
name: do-plan
description: (Re-)shape the remaining backlog into vertical journey slices — one SPA screen/route each, provable by a green Playwright run. Maintains a thin value-ordered roadmap and deep-carves ONE journey JIT for /do-ship. Trigger: /do-plan [J-NNN | next].
---

# do-plan — vertical journey planning

Turn the horizontally-sliced backlog into **vertical journeys**. A journey is
one user-facing path — DB→domain→API→UI — provable by a single green Playwright
run. This skill keeps a thin roadmap of journeys and, on demand, deep-carves
the next one into a full slice the `/do-ship` skill can build.

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
   screen(s) it replaces.
2. For the load-bearing behavior the implementer can't derive from code alone,
   you MAY dispatch `legacy-oracle` now — but it's cheap to defer to ship time.
   Carve captures *shape + contract*; the oracle captures *exact behavior*.
3. Write the journey file (format below) with `status: todo`, `carved: true`.

## Journey definition

- **Granularity:** one SPA screen/route, full CRUD, driven end to end. Maps to a
  feature folder per `alpenflight/web/CLAUDE.md` §2. Not a multi-screen business
  process; not a single atomic action.
- **Headless work** never gets its own journey. It's pulled in by the screen
  that uses it, in this order: real product screen → admin screen →
  test-env-only admin/test affordance → else propose options + escalate.
- **Migration** is per-journey: name the legacy entity/table the journey
  migrates; greenfield/freemium journeys mark it N/A.

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
Headless homing decision, migration shape, sacred cows, assumptions.
```

## Marking superseded stories

When a journey fully absorbs a horizontal story, stamp the story
`rolled_up_into: J-NNN` in its frontmatter (don't delete — `/do-retro` prunes
later). Leave `implemented/` alone.

## Quality bar

- Every journey is provable by one green Playwright run. If you can't name what
  the spec asserts, the journey isn't carved.
- Every journey maps to exactly one screen/route. Multi-screen = split.
- Headless work always has a screen home or an escalation — never a layer-slice.
- Journey-0 exists and is the thinnest chain-bootstrap, before any feature journey.
- `AskUserQuestion` count ≈ 0. Roadmap order, grouping, parity placement: pick + record.

## When done

- **Mode A:** print the sequenced roadmap, Journey-0 pick, escalations,
  superseded-story list, `## Assumptions made`. Tell operator: review, then
  `/do-ship J-NNN`.
- **Mode B:** print the carved journey's ACs + spec contract + migration shape.
  Tell operator: `/do-ship J-NNN`.

## Not in scope

Building anything (that's `/do-ship`), refining `implemented/` stories, touching
the 47 done. Suite/ADR/backlog *learning* changes are `/do-retro`'s.
