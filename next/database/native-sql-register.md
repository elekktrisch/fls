# Native-SQL escape-hatch register

Every approved native SQL query against a tenant-scoped table must be listed
in this file. Hibernate's `@TenantId` filter does **not** apply to
`createNativeQuery(...)` / raw `JdbcTemplate` calls — adding native SQL
against a tenant-scoped table without the explicit `WHERE club_id = ?`
predicate would re-introduce the legacy [R1](../modernization/01-current-state.md#r1--multi-tenancy-enforced-by-convention)
risk that ADR 0008 was written to close.

This register is the gate.

## Approved escape hatches

_(none currently — S-011 deliberately ships the register empty.)_

When you need to add one:

1. Open a PR that updates this file with the entry below filled in.
2. The PR must be reviewed by both a tech lead and a security reviewer
   (CODEOWNERS rule, not yet wired — see drift-control TODO).
3. S-024's CI grep (added in that story) checks every `@Query(nativeQuery = true)`
   and every direct `JdbcTemplate` call against tenant-scoped table names.
   Calls not present in this register fail the build.
4. Expired entries (past `expires`) trigger a build warning + a follow-up
   review.

## Entry template

```
### `<unique-id>` — `<short title>`

- **Caller:** path:line of the Java method making the native call.
- **Tenant-scoped tables touched:** comma-separated list.
- **Justification:** why a native query is required (Hibernate limitation,
  perf, vendor-specific SQL feature, …). One paragraph.
- **Tenancy gate:** how the query is tenant-filtered (explicit `club_id`
  predicate in the SQL, or a documented unscoped call site from
  `tenant-rules.yaml`'s `unscoped_call_sites`).
- **Reviewer:** name of the security reviewer who approved this entry.
- **Approved:** YYYY-MM-DD.
- **Expires:** YYYY-MM-DD (12 months from `Approved` by default).
- **Remove when:** the condition under which this hatch is no longer needed
  (e.g. "Hibernate 7 adds the missing feature").
```

## Related

- [`tenant-catalog.md`](tenant-catalog.md) — the catalog this register
  defends.
- [`tenant-rules.yaml`](tenant-rules.yaml) — the machine-readable contract.
- [ADR 0008](../modernization/adrs/0008-multi-tenancy-mechanism.md) §Negative
  consequences — native SQL is explicitly called out as the residual risk.
