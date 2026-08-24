# CLAUDE.md

## Status — rebuild 2

The first rewrite attempt (`alpenflight/`) is deleted on this branch. It shipped 33 journeys
before the operator stopped it. Its planning artifacts, skills, agents, and CI are archived under
[`docs/attempt-1/`](docs/attempt-1/). Read them for history. Do not treat them as decisions.

Rebuild 2 starts from the legacy reverse-engineering only. The **BMad Method** drives it. The
`/do-*` journey suite is retired. The product name stays **AlpenFlight**, and the new code goes
back into `alpenflight/`.

## Primary directives — PROVISIONAL

These two rules carried rebuild 1. They are **not yet ratified for rebuild 2**. The architecture
step (`bmad-architecture`) must accept or reject each one. Until then, follow them.

1. **Working software over comprehensive documentation.** Skills, agent prompts, story bodies, and
   review prose exist to enable shipping behavior — not as deliverables. Doc drift is a nudge
   unless it actively misleads.
2. **Business logic in the domain, not the database.** The schema enforces only structural
   invariants (primary keys, foreign keys, structural NOT NULL, identity-bearing partial UNIQUE,
   performance indexes). State machines, ranges, calculations, and business rules go in the
   application domain.

## Operator-facing language — ASD-STE100

Write every text the operator reads in **ASD-STE100 Simplified Technical English**. This is an
aviation project. The operator uses the aerospace standard for the same reason the industry does:
one meaning per word, and no ambiguity.

**Where the rule applies:** chat replies, PR titles and bodies, commit messages, PRD and story
files, architecture prose you author, and report output.

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

Before you read anything else, decide which lane you are in:

| If the task is… | Go here |
| --- | --- |
| Planning the rewrite | Invoke the matching BMad skill. Order: `bmad-review` / `bmad-spec` (verify and lock the WHAT) → `bmad-prd` → `bmad-architecture` → `bmad-create-epics-and-stories` → `bmad-sprint-planning` → `bmad-build`. Output lands in `_bmad-output/`. Run `bmad-help` when you are unsure of the position. |
| Building the rewrite | `bmad-build`. New code goes in `alpenflight/`. |
| Extracting exact legacy behavior for one feature | Dispatch the `legacy-oracle` agent. It returns a testable behavior oracle. |
| Reading or understanding legacy server (`flsserver/`) | Read `docs/legacy/server.md` first — that is the mental model. |
| Reading or understanding legacy web (`flsweb/`) | Read `docs/legacy/web.md` first — that is the mental model. |
| Anything in `e2e/` | Self-contained Playwright suite against the **legacy** app at `localhost:3000`. Start its infrastructure with `bash e2e/scripts/dev-up.sh`. |
| Understanding what rebuild 1 decided | `docs/attempt-1/`. History, not authority. |

If the task does not fit a lane, ask. Do not guess.

## Legacy is reference-only

`flsserver/` and `flsweb/` are **read-only** for our purposes:

- They are independent upstream git repositories. Their `main` branches are not ours to commit to.
- They exist here so the rewrite can compare against real behavior, real data shapes, and real edge
  cases.
- **The only legitimate change to legacy is a fix for something obviously wrong, to set a better
  going-in position for the rewrite.** Flag it first — never silently edit. Drift from upstream is
  debt we pay back later.
- All new development lands in `alpenflight/` (rewrite) or `_bmad-output/` (planning artifacts).
  Never in `flsserver/` or `flsweb/`.

## Repository layout (one line each)

- `flsserver/` — legacy ASP.NET Web API backend (.NET Framework 4.5, C#). **Reference only.** Mental model: `docs/legacy/server.md`.
- `flsweb/` — legacy AngularJS 1.4 SPA. **Reference only.** Mental model: `docs/legacy/web.md`.
- `alpenflight/` — the rewrite. **Empty on this branch.** Rebuild 2 recreates it.
- `docs/legacy/` — mental-model documents for the two legacy stacks. Read on demand.
- `docs/modernization/` — the surviving legacy reverse-engineering. See below.
- `docs/attempt-1/` — everything rebuild 1 produced: 30 ADRs, 14 epics, 221 story files, the retired `/do-*` suite, and its CI. Archive.
- `_bmad/` — BMad Method installation. Installer-managed.
- `_bmad-output/` — BMad planning and implementation artifacts. Rebuild 2 writes here.
- `e2e/` — Playwright suite (43 specs, 12 categories) that drives the **legacy** app. The most reliable behavior oracle at breadth.

## The surviving reverse-engineering

`docs/modernization/` now holds only facts about the legacy system. Every one of these feeds
rebuild 2:

| File | What it gives you |
| --- | --- |
| `01-current-state.md` | The feature inventory: feature → code path → persona → e2e spec, plus 13 risk hotspots (R1–R13). **Load-bearing.** Every epic derives from it. |
| `00-seed.md` | The sacred cows and strategic anchors. |
| `legacy-migration-plan.md` | Every legacy table and EF entity — 59 tables, 56 entity classes, exhaustive. The "Destination" and "Semantics" columns are rebuild-1 decisions; treat those two columns as archive. |
| `legacy-tables/` | Per-table legacy schema detail. |
| `form-validation-parity-audit.md` | The legacy validation rules, field by field. |

## Cross-cutting rules

- **Multi-tenancy is convention in legacy.** Every legacy query filters by `ClubId`. Read
  `docs/legacy/server.md` §4 before you add a query. Nothing structural enforces it — risk hotspot
  R1, and the largest correctness risk in the rewrite. The new architecture must decide the
  mechanism.
- **DTOs are not entities.** In legacy, DTOs at the wire and entities at the database are separate
  by design. Do not leak entities through controllers.
- **Do not hardcode absolute server URLs in client code.** Assume same origin, and proxy in the dev
  server.
- **Architecture diagrams and form-design PDFs are in `flsserver/doc/`.** Consult them before you
  redesign a workflow that spans the legacy state machine, rules engine, or invoice flow.
- **There is no CI on this branch.** Rebuild 1's 11 workflows are archived. The new stack brings its
  own.

## When in doubt

- Where am I in the workflow → run `bmad-help`
- Legacy server semantics → `docs/legacy/server.md`
- Legacy web semantics → `docs/legacy/web.md`
- What the system does today → `docs/modernization/01-current-state.md`
- What rebuild 1 decided and why → `docs/attempt-1/`
