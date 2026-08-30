---
id: S-024
title: Cross-tenant leakage CI test (per-repository)
epic: E-03
status: done
started_at: 2026-05-23
done_at: 2026-05-23
merged: true
merged_at: 2026-05-23
depends_on: [S-022, S-011]
acceptance:
  - A property-based test under CI exercises every repository: create data in tenant A; attempt to read it while tenant context is B; assert empty result (or 404 from a controller-level test).
  - The test is parameterized by the tenant-scoped entity list from S-011.
  - The test fails the build if added.
  - A separate dimension covers the "unscoped" path — explicitly running unscoped should return both tenants' data. **Reframed:** S-024 ships the fail-closed `runUnscoped` contract; the literal "see both" assertion ships under `@Disabled("unblocks: S-023")` and is the contract witness for Phase G.
estimate: M
adr_refs: [0008]
refined: true
refined_at: 2026-05-22
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
github_issue: 103
github_pr: 100
---

## Context

The CI-time enforcement that closes R1. ADR 0008 makes leakage structurally impossible from JPA, but: native SQL queries bypass the filter, and developers may add new repository methods that should respect tenancy. This gate catches both.

## Load-bearing decisions

- **Reflective entity discovery** (no hardcoded roster). `TenantScopedEntityCatalog` scans `@TenantId`-bearing `@Entity` classes under `ch.alpenflight`; the sweep auto-includes new entities as they land.
- **Row builders are a registry, not a convention.** `TenantScopedRowBuilders` maps `Class<E> → Function<SweepFixtureContext, E>` returning a *transient* entity; the test persists via the entity's Spring Data repo so each save opens its own transaction and the resolver is consulted fresh. A new `@TenantId` entity without a registered builder fails `TenantSweepFloorAndPinTest::every_discovered_entity_has_a_registered_row_builder` at boot.
- **AC4 reframed.** `runUnscoped()` is fail-closed today (`NO_TENANT` → zero rows + FK reject). S-024 asserts that contract; the literal "see both tenants" semantics live behind a Hibernate filter-bypass that S-023 owns. `DisabledUnscopedSeeBothStub` is the witness placeholder; removing `@Disabled` is the S-023 deliverable.
- **Fail-closed write pins the FK constraint name.** `tenant_scoped_no_tenant_sentinel_insert_fails_on_fk` asserts the message contains `fk_<table>_club_id` — not "any DataIntegrityViolation" — so a resolver-drift regression (`null` → un-filter) can't be masked by a NOT NULL / CHECK / trigger violation elsewhere.
- **Cross-tenant positive sweep is empty today.** YAML `kind: cross-tenant` entries with a classpath-present entity get parameterized; today's set is `{}` (Person / Aircraft / PersonClub not yet ported). `allowZeroInvocations = true` keeps the gate dormant until those land.
- **Controller witness is a mixin contract.** `CrossTenantNotFoundContract` (TestRestTemplate base) — future tenant-scoped controllers extend it for the 404-not-403 IDOR-gate witness. Today's concrete subclass is `LocationsCrossTenantNotFoundIT`.
- **Aggregate-internal entities are gated by reflection.** `AggregateInternalRepositoryGuardTest` rejects any `JpaRepository<X, ?>` where X is `@Entity` without `@TenantId` and has a `@ManyToOne` to a `@TenantId`-bearing class (today: `InOutboundPoint` under `Location`).
- **Native-SQL register is a live CI gate, not advisory.** `NativeSqlRegisterTest` text-greps `src/main/java/**` for native-SQL primitives and rejects hits against tenant-scoped table names that lack an entry in `native-sql-register.md`. Expired entries fail the build.
- **Catalog files have CODEOWNERS.** `.github/CODEOWNERS` flags `tenant-rules.yaml`, `tenant-catalog.md`, and `native-sql-register.md` as sensitive.

## Cross-story contracts

- **Consumes** from S-011: `tenant-rules.yaml`, `native-sql-register.md`.
- **Consumes** from S-022: `TenantTestContext.runAs`, `runUnscoped`, `NO_TENANT` sentinel, resolver fail-closed write contract.
- **Produces**: `CrossTenantNotFoundContract` mixin, `TenantScopedRowBuilders` registry convention, `TwoClubFixture` shared two-club seeder, native-sql-register live CI gate, ArchUnit-equivalent rule against repositories on aggregate-internal entities.

## Assumptions made

- `CODEOWNERS` rule names `@elekktrisch` (solo dev today); when the team grows, owners list expands without re-running this story.
- The cross-tenant positive sweep stays empty until S-050 (Aircraft) or future Person stories land — `allowZeroInvocations = true` is load-bearing-but-quiet until then.

## Notes

Spec `25-multi-tenant-isolation.spec.ts` on the legacy side does this only on a sample. New system has zero excuse to skip any endpoint — every persisted aggregate participates.
