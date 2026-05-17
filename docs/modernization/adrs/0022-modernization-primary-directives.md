# 0022 — Modernization primary directives

- **Status:** Accepted
- **Date:** 2026-05-17
- **Scope:** Governs every `/modernize-*` phase, every specialist agent, every story-level decision. Trumps any conflicting guidance in skill files, agent prompts, or existing stories until they're brought into line.

## Decision

Two directives, in order of precedence:

### Directive 1 — Working software over comprehensive documentation

Our goal is working software. We want to avoid long documentation because it will quickly be outdated. The code we produce is the truth, not the docs.

### Directive 2 — Business logic in the DDD domain, not the database

The aggregate is the unit of consistency. Business rules live as methods on aggregates, unit-testable, evolvable in source. Enums are listed in Java only, and are serialized as string into the db.

**Stays in the schema:**

- **Primary keys** (`uuid NOT NULL PRIMARY KEY`).
- **Foreign keys** with their `ON DELETE` action — including cross-aggregate FKs. Aggregate-internal FKs use `CASCADE`; cross-aggregate use `RESTRICT` / `SET NULL` per the cross-aggregate rule.
- **NOT NULL** for columns the aggregate's invariants require to always be present (the tenant discriminator `operating_club_id`, an aggregate root's `id`). Speculative `NOT NULL` is a domain rule; only the structurally-mandatory ones survive.
- **Partial UNIQUE** indexes that enforce identity (e.g. `UNIQUE (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL`). The uniqueness invariant is structural ("two rows can't share this identifier"); the *when* it applies is filterable.
- **Indexes** for performance — never for business correctness.
- **SQL `COMMENT ON COLUMN`** for documenting cross-tenant FK semantics, sacred-cow exceptions, and other invariants the schema can't otherwise express.

**Moves out of the schema (into the domain):**

- **CHECK constraints encoding state-machine values, allowed ranges, or business invariants**
- **Generated columns** that compute domain values (`total_amount` as `quantity * unit_price * (100 - discount) / 100`). Calculation belongs in the aggregate.
- **Triggers** of any kind. No domain logic in the database.
- **Domain enums** as CHECK-IN-set or text-FK to a lookup table when the value set is bounded and code-driven. Use Java enums + `@Enumerated(EnumType.STRING)` per ADR 0020.

**Why:** business rules change. Domain rules in Java are deployable in a single application release; rules in the schema are migration-locked + multi-environment-coordinated. Co-locating rules with the aggregate also makes them unit-testable without a database, surfaces them in code review next to the data they govern, and removes the dual-source-of-truth problem (CHECK + domain method both encoding the same rule, drifting independently).

**Exception — invariants the legal record requires at all times, not just at booking.** Frozen invoice columns per Swiss OR Art. 957a aren't business rules; they're a regulatory guarantee about *what's recorded on disk*. The 9 `delivery.recipient_*` columns shipped in V4 are not domain logic — they're structural artifacts of the legal record. Their NOT NULL-when-Booked check is a domain rule that *also* the schema may enforce as defense-in-depth (clearly marked).

## Consequences

- Delete docs that are explaining what's already implemented in a maintainable way
- New stories follow the directives. The 4-phase modernization workflow's checkpoint between phases is "is the working code complete?" rather than "are the docs perfect?".
- ADRs added when they capture a non-obvious decision; not as a documentation deliverable per phase.
- CONVENTIONS.md grows when a pattern needs to be discoverable to future implementers; not as a place to dump everything that "should be documented."
- Solo-operator workflow benefits: fewer doc-prose passes = faster cycle time per story.
