# CLAUDE.md

## First action — triage

Before reading anything else, decide which lane you're in:

| If the task is… | Go here |
| --- | --- |
| Modernization (any phase) | `docs/modernization/README.md` + invoke the matching `/modernize-*` skill. Phases: discover → vision → adrs → decompose → refine → implement → review → rework (iterate as needed) → finalize. |
| Reading / understanding legacy server (`flsserver/`) | Read `docs/legacy/server.md` first — that's the mental model. |
| Reading / understanding legacy web (`flsweb/`) | Read `docs/legacy/web.md` first — that's the mental model. |
| Working in `next/` (the rewrite) | Treat as a fresh codebase; the `docs/modernization/` artifacts (current-state, vision, ADRs, stories) are the source of truth. |
| Anything in `e2e/` | Self-contained Playwright suite; per-category projects. No legacy / next coupling required. |

If the task doesn't fit a lane, ask. Don't guess.

## Legacy is reference-only

`flsserver/` and `flsweb/` are **read-only** for our purposes:

- They are independent upstream git repositories. Their `main` branches are not ours to commit to.
- They exist here so the rewrite can compare against real behavior, real data shapes, and real edge cases.
- **The only legitimate change to legacy is fixing something obviously wrong to set a better going-in position for the rewrite.** Flag it first (in a story or conversation) — never silently edit. Drift from upstream is debt we'll pay back later.
- All new development lands in `next/` (rewrite) or `docs/modernization/` (workflow artifacts). Never in `flsserver/` or `flsweb/`.

## Repository layout (one line each)

- `flsserver/` — legacy ASP.NET Web API backend (.NET Framework 4.5, C#). **Reference only.** Mental model: `docs/legacy/server.md`.
- `flsweb/` — legacy AngularJS 1.4 SPA. **Reference only.** Mental model: `docs/legacy/web.md`.
- `next/` — the rewrite. New code goes here. Layout + decisions in `docs/modernization/adrs/`.
- `docs/modernization/` — the modernization workflow output: current-state, vision, ADRs, epics, stories. Driven by the `/modernize-*` skills.
- `docs/legacy/` — mental-model docs for the two legacy stacks. Read on demand.
- `e2e/` — Playwright suite. Per-category projects (see `e2e/README*` if present).

## Cross-cutting rules

- **Don't hardcode absolute server URLs in client code.** Same-origin assumption + dev-server proxying for `/api/*` and `/Token`.
- **Multi-tenancy is convention in legacy, structural in next.** Legacy: every query filters by `ClubId` (read `docs/legacy/server.md` §4 before adding a query). Next: `@TenantId` per ADR 0008.
- **DTOs ≠ entities.** In both stacks, DTOs at the wire and entities at the DB are separate by design. Don't leak entities through controllers.
- **Architecture diagrams and form-design PDFs are in `flsserver/doc/`.** Consult before redesigning a workflow that spans the legacy state machine, rules engine, or invoice flow.

## When in doubt

- Modernization workflow questions → `docs/modernization/README.md`
- Legacy server semantics → `docs/legacy/server.md`
- Legacy web semantics → `docs/legacy/web.md`
- ADR decisions → `docs/modernization/adrs/`
- A specific story's contract → `docs/modernization/stories/S-NNN-*.md`
