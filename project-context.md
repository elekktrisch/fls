# Project context — AlpenFlight

Standing facts for every agent that works in this repository. BMad skills load this file
automatically through a `**/project-context.md` glob.

**The split, so nothing is written twice.** `CLAUDE.md` owns the **rules** — Claude Code loads it
into every session automatically, which makes it the only channel a rule can rely on. This file owns
the **facts, the pitfalls, and the decisions** — BMad skills load it as standing context, and
recording an agent's repeated mistake as a pitfall line is this file's documented job. Neither file
restates the other. Each points instead.

Written 2026-08-29, after `bmad-architecture`.

## What this project is

AlpenFlight replaces the legacy Flight Logging System for Swiss glider clubs. It is a rewrite that
must reproduce a club's invoices **to the cent**, and it must be faster than the legacy flight form
measured in clicks and keystrokes. Those two capabilities decide whether the product succeeds.

One person builds, hosts, and supports it. Income covers hosting only. Every decision that adds
recurring manual work is the wrong decision.

## Authority order

Read down this list. A document lower down never overrides one above it.

1. `CLAUDE.md` — the rules and the triage table.
2. `_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md`
   — AD-1 to AD-21, the build gates, the depth trigger. **How** the system is built.
3. `_bmad-output/planning-artifacts/prds/prd-fls-2026-08-25/prd.md` — FR-1 to FR-89 plus FR-54a,
   NFR-1 to NFR-13. **What** the system does.
4. `.../prds/prd-fls-2026-08-25/domain-model.md` — the ubiquitous language. **A synonym is a defect.**
5. `.../ux-designs/ux-fls-2026-08-24/EXPERIENCE.md` and `DESIGN.md` — behaviour and visual identity.
6. `docs/modernization/` — facts about the legacy system.

Every `.memlog.md` beside a planning artifact holds the reasons. Read the memlog before you
re-open a decision.

## The traps

**`e2e/` is not our test suite.** It drives the **legacy** app at `localhost:3000`. It is a
behaviour **oracle** — read it to learn what the legacy does. AlpenFlight ships its **own**
end-to-end suite inside `alpenflight/`. Never point `e2e/` at the new app. See AD-21.

**The legacy suite proves a feature exists, not that it behaves.** That is risk RK-2. No epic claims
parity on it as it stands. For exact behaviour, dispatch the `legacy-oracle` agent.

**`docs/attempt-1/` is history, not authority.** Its 30 ADRs record a first rewrite that was stopped.
The spine supersedes ADR-0023 in particular: attempt 1 applied hexagonal layering to all ~30
aggregates, and the ceremony was the reason it felt slow. Never cite an attempt-1 ADR as a decision.

**`flsserver/` and `flsweb/` are read-only.** They are separate upstream repositories. New code goes
in `alpenflight/`; planning artifacts go in `_bmad-output/`.

**In `legacy-migration-plan.md`, the "Destination" and "Semantics" columns are rebuild-1 decisions.**
Treat those two columns as archive. Every other column is a fact about the legacy schema.

**The rules are not here.** `CLAUDE.md` owns them — the three primary directives, ASD-STE100, and the
triage table. Read it. This file never restates a rule, so the two can never disagree.

## The stack

Java 25 LTS, Spring Boot 4.1.1, PostgreSQL 18.6, Angular 22.0.1, REST with OpenAPI. Gradle
multi-module. Flyway. One container image, Docker Compose supported, Kubernetes permitted and never
required. Remaining dependency versions are pinned at repository creation.

## The five decisions that shape everything

1. **Vertical slices at two depths.** Thin by default — four files, no business rule. Deep only on a
   trigger: a state machine, an invariant across records, money, or exact legacy behaviour. About
   6–8 deep slices against ~45 thin ones. AD-1.
2. **Tenant isolation lives in the database.** PostgreSQL row-level security is the floor; the
   repository layer is ergonomics only. A query that forgets the filter returns **zero rows**. AD-2.
3. **There are no shared writable entities.** Every table is system reference, club-scoped, or a
   cross-club link. A table declaring no kind fails the build. AD-3.
4. **Three module tiers.** Core and open modules are Apache-2.0 and ship in both editions; pro
   modules are closed and SaaS only. The boundary is proven by a standalone build in CI, never by a
   repository split. AD-12.
5. **Money is `BigDecimal` and `numeric`, always.** No binary floating-point type in any accounting
   path, DTO, or payload. AD-14.

## Sizing, so nobody over-engineers

The largest club: ~8,000 flight records a year, 10 years of history, ~1.5 million rows, 1–2 GB, 200
records on the best Saturday, fewer than 25 concurrent writers. **AlpenFlight is a single-node
workload.** Nothing here justifies a message broker, a read replica, a cache tier, or a shard key.

## Known gaps

- The **fundamental-field set** that escalates a conflict is not yet closed. It needs a
  `legacy-oracle` read against the 88 legacy conditional directives, before the flight-form epic.
- The **BAZL register terms** and the **OGN device database terms** are unconfirmed. Roman decided
  they do not block implementation. AD-5 already drops every personal column, which leaves facts
  about aircraft.
- **`AGENTS.md` does not exist yet.** Run `bmad-project-context` against `alpenflight/` once that
  directory has code.
