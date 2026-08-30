# 0027 — JPA-first persistence, domain-maintained read-models, JDBC as registered exception

- **Status:** Accepted
- **Date:** 2026-06-10
- **Scope:** All AlpenFlight server code — main sources AND tests. Operator
  directive from the J-7 PR review (#215): "avoid jdbc … applies in general
  to the whole codebase."

## Context

J-7 shipped the first reporting read path as hand-built native SQL
(`JpaFlightReportRepository`, register entry `flight-report-read-model`) —
justified locally by cross-tenant decoration joins that JPQL's `@TenantId`
filter would hide. The operator's PR-review verdict: that local justification
doesn't scale into a pattern. Native SQL / `JdbcTemplate`:

- bypasses `@TenantId` — every new native query re-opens the
  [R1](../01-current-state.md#r1--multi-tenancy-enforced-by-convention) risk
  [ADR 0008](0008-multi-tenancy-mechanism.md) exists to close, and each one
  needs a [native-sql register](../../../alpenflight/database/native-sql-register.md)
  entry + review;
- bypasses the aggregates — schema knowledge and business rules leak into SQL
  strings (the PR #215 magic-UUID-literal finding), against
  [ADR 0022](0022-modernization-primary-directives.md) directive 2 and
  [ADR 0018](0018-domain-model-ddd-aggregates.md);
- in tests, JDBC-seeded rows skip the production write path entirely. Today
  that hides invariant violations; the moment redundant read-models exist
  (below), JDBC-seeded data **silently never reaches them** and the test
  asserts against a fixture artifact, not production behavior.

At decision time: 14 main-source files and ~85 integration tests touch
`JdbcTemplate` / `createNativeQuery`.

## Decision

1. **JPA-first, register as shrinking exception list.** All reads and writes
   go through JPA entities / aggregates + repositories. New native SQL or
   `JdbcTemplate` against a tenant-scoped table is allowed only with a
   native-sql-register entry (justification + expiry + removal plan), and
   only for seams that are *structurally* outside `@TenantId` — pre-tenant
   principal resolution, provisioning before a tenant context exists,
   system-actor NULL-tenant audit writes. "The query is complex" is not a
   qualifying justification. Existing entries get retired per-touch.

2. **Complex read shapes get a read-model, not native SQL.** Where a screen
   needs joins/aggregation beyond what a straightforward JPA query expresses
   (reports are the canonical case), maintain a **redundant read-model**:
   separate report entities/tables, written redundantly alongside the domain
   model at mutation time, queried with plain JPA finds. The sync lives in
   the **Java domain** — separate aggregates updated via application events
   from the write-side aggregates — **never DB triggers** (directive 2:
   business logic in the domain, not the database). Write-model ↔ read-model
   sync MUST be integration-tested: mutate through the production path,
   assert the read-model row.

3. **Tests seed through production code.** Aggregates + repositories,
   application services, or controllers — whichever seam is proportionate.
   No production code may be added solely for tests; an attribute the
   production surface deliberately doesn't expose (back-dating, forced
   process states, soft-delete stamps) is set via reflection in a test-side
   helper. This refines the seeding *mechanism* only —
   [ADR 0021](0021-integration-test-data-isolation.md)'s isolation rules
   (per-test Club, stable natural keys, pre-clean at start) are unchanged.

**Migration is per-touch, not big-bang:** the next material edit to a file
converts it. New code complies from day one.

## Consequences

- **Positive:**
  - Tenancy is structurally enforced on every read path; the register stops
    growing and starts shrinking.
  - Read-model rows can only come from domain mutations — reports cannot
    drift from business rules, and tests keep meaning something when
    read-models land.
  - Magic literals collapse into domain constants
    (`FlightCrewTypeIds`, `FlightAircraftType.legacyId()` — done in J-7).
- **Negative:**
  - Redundant storage + a sync seam to maintain and integration-test per
    read-model. Accepted: the sync is same-transaction (synchronous event
    handling), so no eventual-consistency window.
  - Mixed seeding styles in tests until the per-touch sweep completes
    (~85 files at decision time).
  - The flight-report conversion (native SQL → read-model) is real work and
    re-touches a just-shipped journey. Accepted as the cost of catching the
    pattern at its first instance instead of its fifth.
- **Follow-ups** (riders in [`_BOYSCOUT.md`](../stories/_BOYSCOUT.md)):
  - Convert the flight-report read path to a domain-maintained read-model
    (retires the `flight-report-read-model` register entry). Pulled forward
    at review: lands on a stacked branch BEFORE J-7 merges, so main never
    receives the native-SQL report path.
  - Retire `MeService` / `JpaUserRepository` / `JpaPersonRepository` /
    probe-impl JDBC sites per-module on next touch.
  - Done with this ADR (PR #215): J-7's own ITs seed via production code;
    `JpaFlightReportRepository` literals reference domain constants.
