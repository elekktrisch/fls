# CLAUDE.md

## Primary directives

[ADR 0022](docs/modernization/adrs/0022-modernization-primary-directives.md). Trumps every other rule in this file or any skill / agent file when they conflict.

1. **Working software over comprehensive documentation.** Skills, agent prompts, story bodies, and review prose exist to enable shipping behavior — not as deliverables. Doc drift is a nudge unless it actively misleads. Skill files target ≤ 300 lines, agent files ≤ 150.
2. **Business logic in the DDD domain, not the database.** The schema enforces only structural invariants (PKs, FKs, structural NOT NULL, identity-bearing partial UNIQUE, performance indexes). State machines, ranges, calculations, and business rules go on aggregates as Java methods.

## Operator-facing language — ASD-STE100

Write every text the operator reads in **ASD-STE100 Simplified Technical English**. This is an
aviation project. The operator uses the aerospace standard for the same reason the industry does:
one meaning per word, and no ambiguity.

**Where the rule applies:** chat replies, PR titles and bodies, commit messages, journey and story
files, ADR prose you author, proof-gallery captions, and report output.

**Where it does not apply:** code identifiers, quoted log or command output, legacy source, and
documents that already shipped. Do not rewrite shipped text to comply.

**The rules:**

1. Use the active voice. Write "the gate rejects the push", not "the push is rejected".
2. Keep sentences short. Use a maximum of 20 words for an instruction, 25 for a description.
3. Give one instruction in one sentence. Start an instruction with the verb.
4. Use one word for one meaning. Do not use a synonym for a term you used before. If you write
   "gate", never write "check" or "guard" for the same thing.
5. Use articles. Write "the manager pushes the commit", not "manager pushes commit".
6. Use simple tenses only: present, past, future.
7. Do not use idioms, slang, metaphors, or humour. "The gate is red" is correct. "The gate blew up"
   is not.
8. Do not use a noun cluster of more than three words.
9. Use a maximum of 6 sentences in an instruction paragraph, and 10 in a description paragraph.
10. Write a warning or a caution before the step it applies to, never after.

**Honest limit:** full compliance needs the ASD-STE100 approved-word dictionary of approximately 900
words. Apply the rules above and the one-word-one-meaning principle. Do not claim full dictionary
compliance.

## First action — triage

Before reading anything else, decide which lane you're in:

| If the task is… | Go here |
| --- | --- |
| Modernization (planning or shipping a rewrite journey) | `docs/modernization/README.md` + invoke the matching `/do-*` skill. Flow: `/do-plan` (carve/roadmap journeys) → `/do-ship J-NNN` (drive one journey to a green PR; dispatches `/do-task` workers) → `/do-retro` (improve the suite from what shipping taught). The older `/modernize-*` phase skills are retired. |
| Reading / understanding legacy server (`flsserver/`) | Read `docs/legacy/server.md` first — that's the mental model. |
| Reading / understanding legacy web (`flsweb/`) | Read `docs/legacy/web.md` first — that's the mental model. |
| Working in `alpenflight/` (the rewrite) | Treat as a fresh codebase; the `docs/modernization/` artifacts (current-state, vision, ADRs, stories) are the source of truth. |
| Anything in `e2e/` | Self-contained Playwright suite; per-category projects. No legacy / next coupling required. |

If the task doesn't fit a lane, ask. Don't guess.

## Legacy is reference-only

`flsserver/` and `flsweb/` are **read-only** for our purposes:

- They are independent upstream git repositories. Their `main` branches are not ours to commit to.
- They exist here so the rewrite can compare against real behavior, real data shapes, and real edge cases.
- **The only legitimate change to legacy is fixing something obviously wrong to set a better going-in position for the rewrite.** Flag it first (in a story or conversation) — never silently edit. Drift from upstream is debt we'll pay back later.
- All new development lands in `alpenflight/` (rewrite) or `docs/modernization/` (workflow artifacts). Never in `flsserver/` or `flsweb/`.

## Repository layout (one line each)

- `flsserver/` — legacy ASP.NET Web API backend (.NET Framework 4.5, C#). **Reference only.** Mental model: `docs/legacy/server.md`.
- `flsweb/` — legacy AngularJS 1.4 SPA. **Reference only.** Mental model: `docs/legacy/web.md`.
- `alpenflight/` — the rewrite (AlpenFlight). New code goes here. Layout + decisions in `docs/modernization/adrs/`.
- `docs/modernization/` — the modernization workflow output: current-state, vision, ADRs, epics, stories/journeys. Driven by the `/do-*` skills.
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
