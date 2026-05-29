# Native-SQL escape-hatch register

Every approved native SQL query against a tenant-scoped table must be listed
in this file. Hibernate's `@TenantId` filter does **not** apply to
`createNativeQuery(...)` / raw `JdbcTemplate` calls — adding native SQL
against a tenant-scoped table without the explicit `WHERE club_id = ?`
predicate would re-introduce the legacy [R1](../modernization/01-current-state.md#r1--multi-tenancy-enforced-by-convention)
risk that ADR 0008 was written to close.

This register is the gate.

## Approved escape hatches

### `tenancy-provisioning-reference-data-seed` — `Per-Club reference-data defaults at trial provisioning`

- **Caller:** `src/main/java/ch/alpenflight/tenancy/provisioning/application/ReferenceDataSeeder.java`
- **Tenant-scoped tables touched:** t_member_state, t_flight_type
- **Justification:** the seeder fires once per Club inside the S-138 trial-
  provisioning transaction, BEFORE any caller has a JPA entity to save and
  while the Hibernate session is mid-flight on the Deployment + Club inserts.
  Using JdbcTemplate sidesteps `@TenantId` filter activation under a still-
  null tenant carrier; the bundle-wins-on-conflict rule (refinement) needs
  `ON CONFLICT (...) DO NOTHING` against the V11 / V15 partial UNIQUE
  indexes, which JPA's batch insert doesn't expose. The structural gates
  (`ux_flight_type_club_name`, `ux_member_state_club_name`) are the
  idempotency anchors.
- **Tenancy gate:** every INSERT carries an explicit `club_id` /
  `operating_club_id` value (parameter-bound, never caller-controlled string
  interpolation). The service call site is wrapped in
  `Tenants.runAs(clubId, ...)` so the operating tenant matches the inserted
  rows — defence in depth.
- **Reviewer:** auto-registered with S-138 implementation; security-reviewer
  panel (implement Step 7) re-confirms.
- **Approved:** 2026-05-28.
- **Expires:** 2027-05-28
- **Remove when:** S-141 surfaces an ingest-pipeline-wide null-tenant write
  context that the seeder can join, OR Hibernate adds a first-class
  upsert API with partial-UNIQUE conflict targeting.

### `mutation-audit-event-system-actor-write` — `Cross-tenant audit row for system events`

- **Caller:** `src/main/java/ch/alpenflight/audit/application/MutationAuditEventListener.java`
- **Tenant-scoped tables touched:** `t_mutation_audit_event`
- **Justification:** true system events (no JWT principal — S-140 hourly
  handshake-TTL sweep is the first such writer) legitimately store NULL
  in `tenant_club_id`. JPA can't write that here: Hibernate's `@TenantId`
  resolver would override the null field with `NO_TENANT` (nil UUID),
  which fails the `fk_mutation_audit_event_tenant_club_id` FK because
  no nil-UUID row exists in `t_club`. JDBC bypasses the discriminator
  and lets the FK's "NULL ⇒ no parent" rule apply naturally — the
  design intent captured in `MutationAuditEvent`'s javadoc. Gated on
  `request.systemActor() == true`; authenticated principals still flow
  through JPA.
- **Tenancy gate:** the INSERT writes a literal `NULL` for
  `tenant_club_id`. The row is forensic-only (system-actor audit) and
  visible to S-056 admin readers under the deferred S-023 unscoped-read
  context.
- **Reviewer:** auto-registered with S-140 implementation; security-reviewer
  panel (implement Step 7) re-confirms.
- **Approved:** 2026-05-29.
- **Expires:** 2027-05-29
- **Remove when:** Hibernate's `@TenantId` exposes a per-write "leave null"
  switch, OR S-023's `UnscopedTenantContext` lands and the listener can
  flip back to JPA inside that context.

### `persons-cross-tenant-membership-check` — `Person soft-delete cross-tenant safety check`

- **Caller:** `src/main/java/ch/alpenflight/persons/infra/JpaPersonRepository.java`
- **Tenant-scoped tables touched:** `t_person_club`
- **Justification:** soft-deleting a `Person` must refuse when memberships
  in tenants OTHER THAN the caller's exist — the Person aggregate's
  `softDelete` invariant refuses to orphan another tenant's PersonClub
  records. Hibernate's `@TenantId` discriminator on `PersonClub.clubId`
  filters JPA reads to the caller's tenant; checking for *other* tenants
  is the literal opposite intent and cannot be expressed via the filtered
  path. The companion count query derives the response's `inOtherClubsCount`
  privacy projection — same cross-tenant truth shape, used only for
  reads (the value never lands on the wire as a per-club identifier).
- **Tenancy gate:** explicit `club_id <> :currentTenantId` predicate (or no
  predicate for the count). Both queries take parameterised path-bound
  caller-tenant values; no caller-controllable injection vector.
- **Reviewer:** auto-registered with S-051 implementation; security-reviewer
  panel (implement Step 7) re-confirms.
- **Approved:** 2026-05-24.
- **Expires:** 2027-05-24
- **Remove when:** Hibernate exposes a first-class "unscoped read" API that
  filters across all tenants by construction, or the soft-delete safety
  check moves to a dedicated service explicitly wrapped in
  `Tenants.runAs(null)` so the JPA path becomes idiomatic.

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
