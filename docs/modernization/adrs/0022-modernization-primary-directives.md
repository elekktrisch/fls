# 0022 — Modernization primary directives

- **Status:** Accepted
- **Date:** 2026-05-17
- **Scope:** Governs every `/modernize-*` phase, every specialist agent, every story-level decision. Trumps any conflicting guidance in skill files, agent prompts, or existing stories until they're brought into line.

## Context

After 14 stories + a S-128 rebrand, two patterns have surfaced often enough to deserve canonical status:

1. **Documentation drift outpaces working code.** Refinement sections, story bodies, ADR follow-up lists, and skill files accumulate prescription that the code never matches one-for-one. Operators end up triaging doc-vs-code drift findings instead of shipping behavior. Workflow files themselves run 200-700 lines and obscure the actual procedure inside them.
2. **The schema is collecting business rules.** V4 (S-014) shipped CHECK constraints for state-machine values (`process_state_id IN (10,20,30,99)`), invoice-completeness invariants (`Booked-requires-recipient`, `Booked-requires-delivered_on`), numeric ranges (`batch_id >= 0`, `discount_in_percent BETWEEN 0 AND 100`), and operational caps (`reservation_end <= reservation_start + INTERVAL '30 days'`). These are *domain* rules expressed at the *persistence* layer — duplicated effort, harder to test, and they encode policy in the slowest-to-change part of the stack.

The two patterns push opposite directions but share one cause: the workflow rewards thoroughness in the wrong places (more prose, more constraints) rather than thoroughness where it matters (working behavior, domain-correct code, fast feedback).

## Decision

Two directives, in order of precedence:

### Directive 1 — Working software over comprehensive documentation

A behavior-preserving rewrite ships when the new stack passes the same parity tests as the old stack. Story bodies, refinement sections, agent prompts, and skill files exist to *enable* that — not to be artifacts in their own right.

**What this means:**

- **Skills + agents stay terse.** Imperative procedure, one path per concern, no worked examples that restate the rule. Target: skill files ≤ 200 lines; agent files ≤ 100 lines. When something needs more space, it goes in an ADR or `CONVENTIONS.md`, not the procedure file.
- **Story bodies don't substitute for code.** Acceptance criteria + a one-line context paragraph is enough. Long design notes are a refinement *option*, not a required deliverable — only worth writing when the design has genuine forks. A story that ships behind a passing parity test with no design notes is a successful story.
- **Refinement specialists run when there's a fork to resolve, not on every story.** If a story is "add a column with a unit test," skip refinement entirely.
- **The PR description carries the why; the commit log carries the what.** Don't duplicate either into the story body.
- **Reviews assess working software first.** Doc drift findings are nudges unless they actively mislead a future implementer to write the wrong code. "Header says 8 sections but body has 11" is an improvement at best, not a blocker.

**Anti-patterns this directive forbids:**

- Adding new sections to a skill or agent file when an existing section can be tightened to cover the case.
- Filing a follow-up story whose acceptance criterion is "update the README" with no behavior change.
- Refinement-section drift (e.g. spec says `tsrange` but code shipped `tstzrange`) treated as a blocker when the code is correct and the deviation is documented in `## Implementation notes`.
- Long "rationale" comments in code where naming + tests already communicate intent.

### Directive 2 — Business logic in the DDD domain, not the database

The aggregate is the unit of consistency. Business rules live as methods on aggregates (e.g. `Delivery.book()`, `AircraftReservation.cancel()`) — typed, unit-testable, evolvable in source. The schema enforces only what *must* be invariant for data to be parseable.

**Stays in the schema:**

- **Primary keys** (`uuid NOT NULL PRIMARY KEY`).
- **Foreign keys** with their `ON DELETE` action — including cross-aggregate FKs. Aggregate-internal FKs use `CASCADE`; cross-aggregate use `RESTRICT` / `SET NULL` per the cross-aggregate rule.
- **NOT NULL** for columns the aggregate's invariants require to always be present (the tenant discriminator `operating_club_id`, an aggregate root's `id`). Speculative `NOT NULL` is a domain rule; only the structurally-mandatory ones survive.
- **Partial UNIQUE** indexes that enforce identity (e.g. `UNIQUE (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL`). The uniqueness invariant is structural ("two rows can't share this identifier"); the *when* it applies is filterable.
- **Indexes** for performance — never for business correctness.
- **SQL `COMMENT ON COLUMN`** for documenting cross-tenant FK semantics, sacred-cow exceptions, and other invariants the schema can't otherwise express.

**Moves out of the schema (into the domain):**

- **CHECK constraints encoding state-machine values, allowed ranges, or business invariants** — `process_state_id IN (10,20,30,99)`, `quantity >= 0`, `discount BETWEEN 0 AND 100`, `Booked-requires-recipient`, sanity caps like 30-day maxima. These belong on the aggregate as methods + value-object constructors. Use `@PreUpdate` / `@PrePersist` Hibernate hooks or aggregate-method guards instead.
- **Generated columns** that compute domain values (`total_amount` as `quantity * unit_price * (100 - discount) / 100`). Calculation belongs in the aggregate.
- **Triggers** of any kind. No domain logic in the database.
- **Domain enums** as CHECK-IN-set or text-FK to a lookup table when the value set is bounded and code-driven. Use Java enums + `@Enumerated(EnumType.STRING)` per ADR 0020.

**Why:** business rules change. Domain rules in Java are deployable in a single application release; rules in the schema are migration-locked + multi-environment-coordinated. Co-locating rules with the aggregate also makes them unit-testable without a database, surfaces them in code review next to the data they govern, and removes the dual-source-of-truth problem (CHECK + domain method both encoding the same rule, drifting independently).

**Exception — invariants the legal record requires at all times, not just at booking.** Frozen invoice columns per Swiss OR Art. 957a aren't business rules; they're a regulatory guarantee about *what's recorded on disk*. The 9 `delivery.recipient_*` columns shipped in V4 are not domain logic — they're structural artifacts of the legal record. Their NOT NULL-when-Booked check is a domain rule that *also* the schema may enforce as defense-in-depth (clearly marked).

## Consequences

### Immediate

- **Skill files trim to target.** Long examples + repeated rationale move to this ADR or to `CONVENTIONS.md` examples.
- **Agent prompts trim to target.** Cite this ADR + `00-seed.md` + the relevant existing CONVENTIONS section; don't restate.
- **V4 (S-014) carries Directive-2 violations.** They were valid under the prior workflow + don't break anything live (V4 hasn't shipped to a production environment). A follow-up story (S-132) drops the business-logic CHECKs + generated `total_amount` and re-homes the rules on the aggregate at S-022/S-064. The schema-introspection tests delete with them.
- **`/modernize-implement` Step 6.7 self-review checks Directive 2.** Any new CHECK constraint, generated column, or trigger raises a blocker unless the diff carries an inline rationale explaining why the schema (not the domain) owns the rule.
- **`/modernize-review` reviewers downgrade documentation-drift findings** to improvements / nudges unless the drift would mislead a future implementer to write incorrect code.

### Forward

- New stories follow the directives. The 4-phase modernization workflow's checkpoint between phases is "is the working code complete?" rather than "are the docs perfect?".
- ADRs added when they capture a non-obvious decision; not as a documentation deliverable per phase.
- CONVENTIONS.md grows when a pattern needs to be discoverable to future implementers; not as a place to dump everything that "should be documented."
- Solo-operator workflow benefits: fewer doc-prose passes = faster cycle time per story.

### Risks

- **Schema-side defense-in-depth gets weaker.** A `process_state_id IN (10,20,30,99)` CHECK catches a buggy `process_state_id = 0` write before it lands; without it, the bad value writes successfully and surfaces later. Mitigation: comprehensive unit + integration tests on the aggregate methods are the new safety net; the test suite must catch what the CHECK used to.
- **Migrations could ship logic-laden code by accident.** Adding "just one CHECK" is tempting. Mitigation: `forbidden-migration-patterns.txt` adds a deny pattern for `CHECK \(.*\) `* in the next sweep (with a documented allow-list for the structural exceptions); reviewer prompt asks "does this CHECK encode a business rule?".

## Follow-ups

- **S-132** — drop business-logic CHECKs + generated `total_amount` from V4; ship V5 migration; aggregate-method enforcement lands at S-022/S-064.
- **Skill + agent trim** — this PR.
- **Vision doc update** — this PR; vision §1 references this ADR as primary directive.
- **CONVENTIONS.md update** — this PR; add a "Domain vs schema" section under the index-shape rules from S-014's review.
