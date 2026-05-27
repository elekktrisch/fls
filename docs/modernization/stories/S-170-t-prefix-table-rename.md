---
id: S-170
title: Apply `t_` prefix to all tables for naming consistency
epic: E-04
status: todo
estimate: M
parity_test: none
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0022]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
github_issue: 141
github_pr: 142
origin: scope-split
origin_story: S-052
---

## Context

S-052 introduced the `t_user` table name (instead of relying on quoting
`"user"`) because `user` is a Postgres reserved word and the unquoted
identifier requires a JPA workaround that's easy to get wrong. The
operator then chose the `t_` prefix convention as a project-wide
direction; S-052 only applied it where the reserved-word collision
forced it (the `user` table).

This story sweeps the convention across every remaining table so the
schema reads consistently.

## Acceptance criteria

- Every domain / reference table in every shipped Flyway migration
  carries the `t_` prefix, including `t_app_meta` (the V1 baseline
  sentinel — operator-confirmed in scope).
- Every JPA `@Table(name = ...)` annotation matches.
- Every native SQL query (`@Query(nativeQuery = true)`, raw JDBC) is
  updated.
- `flyway_schema_history` keeps the legacy name (Flyway-owned; renaming
  would break Flyway resume on existing databases).
- FK / UQ / CK constraint names and INDEX names **stay legacy-shaped**
  (`fk_aircraft_owner_club_id`, `ux_article_club_number`, `ix_*` — see
  Design notes for rationale).
- A new ADR (0025 — next free slot) pins the convention so future
  tables land prefixed.

## Notes

- No Flyway migrations have been shipped to anyone yet, so amending
  V1..V13 in place is acceptable (same precedent S-052 used).
- OpenAPI snapshot regenerates from the live spec — local Postgres via
  `DATASOURCE_URL` is the supported path (matches the dev compose /
  `~/.bashrc` defaults).

<!-- modernize-refine: start -->

## Design notes

**Migration strategy.** In-place amend V1..V13. No migration has shipped
externally; a parallel `ALTER TABLE … RENAME TO …` migration would split
"where is this table created" across two files and gain no operational
value. Developers re-baseline their local `alpenflight` DB
(`DATASOURCE_URL` + drop+recreate); document in the PR.

**JPA `@Table` strategy.** Every existing entity carries an explicit
`@Table(name = ...)`; update each to the `t_`-prefixed value. Do NOT
introduce a Hibernate physical-naming-strategy auto-prefix — that turns
the prefix into invisible magic and breaks the grep-for-table-name
workflow.

**Constraints + indexes stay legacy-shaped** (operator decision). FKs /
UQs / CKs / IXs keep current names (`fk_aircraft_owner_club_id`,
`ux_article_club_number`, …). Renaming ripples to ~17 production
`causeMessage.contains("fk_…")` exception-translation sites plus IT
string assertions; the cost-vs-consistency trade favors leaving them.
Visible inconsistency (table prefixed, FK not) is accepted.

**`app_meta` joins the sweep** as `t_app_meta` (operator decision).
Rule: every table created by AlpenFlight migrations gets `t_`; only
Flyway's own `flyway_schema_history` escapes.

**ArchUnit guard.** Add a one-rule ArchUnit check in
`ch.alpenflight.arch` — "every `@Entity` class's resolved table name
starts with `t_`". CI fails the next time someone adds an unprefixed
table. Cheaper than a docs-only ADR clause.

**New ADR 0025 — Table-naming convention.** Self-contained governance
file. ADR 0022 is the primary-directives manifesto; piling a typographic
convention onto it dilutes its purpose. Cite S-052 origin + S-170 sweep
+ the ArchUnit guard in Consequences.

**Native SQL touchpoints** (the rename ripples here beyond `@Table`):
- `platform/tenancy/{UserPrincipalLookup, LanguageCodeLookup}.java`
- `me/application/MeService.java`
- `persons/infra/JpaPersonRepository.java` (`@Query(nativeQuery=true)`)
- `referencedata/infra/{JpaClubStateRepository, JpaCountryRepository}.java`

`mutation_audit_event.target_entity_type` stores logical aggregate
names ("Club"), not table names — unchanged. `flsweb/` reference-only,
out of scope.

## Edge cases & hidden requirements

- The AC's table list is illustrative; the canonical set is the 48
  `CREATE TABLE` rows across V1..V13. Implementer enumerates from the
  SQL, not the AC prose.
- Don't double-prefix `t_user` (already in V2) or `t_app_meta` once
  written. Dev seed V8 already references `t_user` — verify post-sweep
  it still resolves.
- Stale prose in `V8__dev_user_seed.sql:1-20` header + V2 comments
  references "the `user` table" / "`user` carries no `@TenantId`" —
  refresh inline as part of the sweep.
- ~30 integration tests use raw JDBC against bare table names
  (`MeControllerIT`, `UsersJitFirstLoginIT`, every `*AuthorizationIT`,
  every `*TenantIsolationIT`, the audit IT suite, baseline tests). The
  global grep is `\b(DELETE FROM|INSERT INTO|UPDATE|FROM)\s+([a-z_]+)`.
- Migration-baseline tests carry hard-coded table-name lists
  (`IdentityBaselineIntegrationTest:79,135,154,272,541`,
  `ReservationsBaselineIntegrationTest:52,115,133,177`,
  `FlightBaselineIntegrationTest` similar). Lockstep with the
  migrations or the build goes red.
- `NativeSqlRegisterTest` consumes
  `TenantScopedEntityCatalog.resolveTableName` reflexively — auto-updates
  with `@Table` renames; running it post-sweep is the cheapest "I missed
  one" check.
- OpenAPI snapshot: byte-identical diff expected (table names don't
  surface in DTOs). Non-empty diff means an entity-class name leaked
  into a serialised field — fix at source.

## Security plan

(N/A — pure schema-naming refactor; no auth / authz / tenancy / PII
surface changes.)

## Test plan

- **Existing-suite regression net** carries most of the proof. Flyway
  boots from V1 cleanly via `FlywayBootstrapIntegrationTest`; Hibernate
  validates every `@Table` mapping at every `@SpringBootTest` boot;
  every IT's `DELETE FROM`/`INSERT INTO` fixtures fail loudly if a
  string was missed.
- **New: one schema-derived sweep IT.** `TableNamingConventionTest`
  (in `server.migration`): query `information_schema.tables WHERE
  table_schema='public'` and assert every `table_name` matches `^t_`
  with `{flyway_schema_history}` as the documented exception set. One
  assertion, no hard-coded list — survives every future migration.
- **New: one JPA-side sweep.** Same test scans all `@Entity` classes
  via the existing `TenantScopedEntityCatalog` discovery pattern
  (widened to all `@Entity`) and asserts each resolved table name
  starts with `t_`. Catches an entity whose `@Table` was missed where
  the DDL coincidentally still works.
- **New: one fixture-cleanup audit (DB-free).** Walk `src/test/java/**`
  with regex `(DELETE FROM|INSERT INTO|UPDATE|FROM)\s+(\w+)`; assert
  each captured identifier starts with `t_` or sits in an explicit
  allow-list (none expected). Makes "I missed a fixture" structurally
  impossible. Hits today: ~16 call sites across 12 files.
- **Updates to existing tests:** the three baseline tests
  (`IdentityBaselineIntegrationTest`, `FlightBaselineIntegrationTest`,
  `ReservationsBaselineIntegrationTest`) hardcode names + carry
  `'person_club'::regclass` casts — re-spell in lockstep.
- **Don't duplicate** Hibernate's own `@Table`-vs-DDL validation or
  Flyway's re-run check. Don't snapshot table identity per-row in dev
  seed — IT coverage of `t_user` already proves it.

## Performance plan

(N/A — pure naming refactor; no query / index / lookup shape changes.
Constraints and indexes keep current names so existing query plans are
unaffected.)

<!-- modernize-refine: end -->
