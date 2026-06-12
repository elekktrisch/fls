# Native-SQL escape-hatch register

Every approved native SQL query against a tenant-scoped table must be listed
in this file. Hibernate's `@TenantId` filter does **not** apply to
`createNativeQuery(...)` / raw `JdbcTemplate` calls — adding native SQL
against a tenant-scoped table without the explicit `WHERE club_id = ?`
predicate would re-introduce the legacy [R1](../modernization/01-current-state.md#r1--multi-tenancy-enforced-by-convention)
risk that ADR 0008 was written to close.

This register is the gate. Policy:
[ADR 0027](../../docs/modernization/adrs/0027-jpa-first-persistence-and-domain-read-models.md)
— JPA-first; this register is a **shrinking exception list**. Only seams
structurally outside `@TenantId` qualify (pre-tenant resolution, provisioning
before tenant context, system-actor NULL-tenant writes); query complexity does
not — complex reads get a domain-maintained read-model instead. Every entry
carries an expiry + removal plan.

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

### `tenancy-showcase-seed-deterministic-ids` — `On-demand showcase demo seed with deterministic ids`

- **Caller:** `src/main/java/ch/alpenflight/tenancy/showcase/ShowcaseSeeder.java`
- **Tenant-scoped tables touched:** t_location, t_flight, t_person_club
- **Justification:** the showcase seed (J-3) needs *fixed* row ids so the e2e
  display spec + admin-dashboard tiles can assert against known rows
  (`LOCATION_C1_HOME`, the per-state flight matrix, …). The Location + Flight
  aggregates own id generation (`@GeneratedValue(strategy = UUID)` mints a
  fresh random id on every persist), so the JPA save path cannot honour a
  chosen id. The seeder therefore mirrors the migration ingestor's
  validate-via-aggregate-then-JDBC-INSERT pattern for these very entities:
  construct the aggregate (so every ADR 0022 directive-2 invariant runs —
  ICAO shape, blank-name, flight operational data, the legal state matrix via
  the real `FlightStateTransitionService`), read the normalised values off the
  getters, then carry the deterministic id in an idempotent
  `ON CONFLICT (id) DO NOTHING` INSERT. The seeder is showcase-only
  (`@Profile("showcase")`), never on the IT bootstrap path, and never serves a
  request — it is a curated demo loader. (J-4 T-14:
  `insertPilot1PersonClub()` extends the same chosen-id pattern to
  `t_person_club` — `pilot1`'s deterministic club-1 membership row carries the
  notification-pref values the `/profile` Notifications tab renders +
  round-trips, and must reuse a fixed id (`PERSON_CLUB_PILOT1`) so the e2e
  profile spec can assert against the known membership.)
- **Tenancy gate:** every INSERT/UPDATE sets the tenant column explicitly —
  `t_location.club_id`, `t_flight.operating_club_id` and `t_person_club.club_id`
  are parameter-bound literals (never caller-controlled string interpolation).
  The location + flight seed additionally runs inside `Tenants.runAs(clubId, ...)`
  so its effective-tenant write-context matches the inserted rows (defence in
  depth); the `t_person_club` membership row carries `CLUB_1` as the bound
  `club_id` parameter directly. This does NOT bypass tenant scoping — it sets the
  tenant column rather than relying on the `@TenantId` discriminator, which the
  chosen-id INSERT path can't engage.
- **Reviewer:** auto-registered with J-3 T-03c; security-reviewer panel
  (ship-time gate) re-confirms.
- **Approved:** 2026-06-04.
- **Expires:** 2027-06-04
- **Remove when:** the showcase seed is retired, OR Location/Flight expose a
  persist-with-supplied-id path (e.g. an explicit assigned-id factory the
  ingestor + seeder share) that routes through JPA so the `@TenantId`
  discriminator engages without a chosen-id JDBC INSERT.

### `reservations-conflict-gist-overlap-probe` — `Aircraft reservation GiST range-overlap conflict probe`

- **Caller:** `src/main/java/ch/alpenflight/reservations/infra/AircraftReservationConflictProbeImpl.java`
- **Tenant-scoped tables touched:** t_aircraft_reservation
- **Justification:** the conflict probe (J-5 `existsActiveConflict`) tests whether
  any active reservation on the same aircraft overlaps a half-open
  `[start,end)` window. The overlap test uses the Postgres `&&` range-overlap
  operator against the `reservation_range tstzrange` column that is
  `GENERATED ALWAYS AS STORED` in V4 — neither the `&&` operator nor a
  reference to a generated range column is expressible in JPQL/HQL. The query
  rides the partial GiST index `ix_arv_aircraft_range_gist` on
  `(aircraft_id, reservation_range) WHERE deleted_on IS NULL` for the sub-10ms
  probe the V4 schema design notes call out. The rule itself lives on the
  `AircraftReservation` aggregate (`conflictsWith`, ADR 0022 directive 2 — no
  `EXCLUDE` constraint); this is the persistence-layer fast path the service
  consults before save.
- **Tenancy gate:** explicit `operating_club_id = :tenantId` predicate — the
  tenant id is resolved from `ClubTenantIdentifierResolver` (same JWT →
  `Tenants.runAs` carrier precedence the JPA path uses) and parameter-bound,
  never caller-controlled string interpolation. Hibernate's `@TenantId`
  discriminator does not apply to native SQL, so the predicate is the explicit
  tenant gate. Soft-deleted rows excluded (`deleted_on IS NULL`); the edited row
  self-excluded (`:excludeId IS NULL OR id <> :excludeId`).
- **Keep-vs-convert decision (J-26 T-17, 2026-06-12 — KEEP GiST):** re-affirm
  pass weighed the derived-JPQL alternative
  (`reservationStart < :end AND :start < reservationEnd`) and **rejected it.**
  This is a genuine vendor-SQL-feature seam, not a complex-read that ADR 0027
  would push into a read-model: (a) the `&&` range-overlap operator over the
  `GENERATED ALWAYS AS … STORED` `reservation_range tstzrange` column
  (V4:210-211) has **no JPQL/HQL form** — neither the operator nor a reference
  to a generated range column is expressible; (b) the probe rides the partial
  GiST index `ix_arv_aircraft_range_gist` (V4:245-246, `WHERE deleted_on IS
  NULL`) for the sub-10ms point-probe the V4 design notes call out — a derived
  JPQL form drops to a seq-scan-prone two-column compare with no index that fits
  the half-open semantics; (c) the rule itself already lives on the aggregate
  (`AircraftReservation.conflictsWith`) — this is purely the persistence fast
  path, not business logic in SQL. The half-open `[)` boundary is encoded once
  in the generated column, so there is no parity drift risk a hand-written JPQL
  range compare would re-introduce. Decision: **stays native, keeps GiST.**
- **Reviewer:** auto-registered with J-5 T-04; security-reviewer panel
  (ship-time gate) re-confirms; J-26 T-17 re-affirm pass (above).
- **Approved:** 2026-06-06.
- **Expires:** 2027-06-06
- **Remove when:** Hibernate exposes a first-class range-overlap predicate over a
  generated range column under the `@TenantId` filter. (The derived-JPQL
  fallback was evaluated and rejected in the J-26 T-17 decision above — only the
  Hibernate-feature path retires this entry now.)

## Re-affirm log

- **J-26 T-17 (2026-06-12) — full re-affirm pass.** Every entry above was
  re-checked against the live tree: the caller file still exists, still makes the
  native/JDBC call, and the justification is still a structurally-pre-tenant /
  vendor-SQL-feature / system-actor-null-tenant seam (not a "complex read" ADR
  0027 would route to a read-model). Verdicts: all five **KEPT**
  (`tenancy-provisioning-reference-data-seed`, `mutation-audit-event-system-actor-write`,
  `persons-cross-tenant-membership-check`, `tenancy-showcase-seed-deterministic-ids`,
  `reservations-conflict-gist-overlap-probe`). The conflict-probe keep-vs-convert
  decision is recorded in-entry (KEEP GiST).
- **Structurally-pre-tenant `t_user` JDBC sites — reviewed, no register entry
  required (by design).** `platform/tenancy/UserPrincipalLookup.java` (Hibernate
  tenant-resolver session-open path — resolves the JWT principal's `club_id`
  *before* a tenant carrier exists, so it cannot run through JPA) and
  `migrations/application/PreTenantUserLookup.java` (S-140 pre-tenant handshake
  provisioning) both touch only `t_user`, which is **not** `@TenantId`-scoped
  (the `User` aggregate is deliberately cross-tenant — scoping is an explicit
  `WHERE u.club_id` predicate, see its javadoc). The register defends
  tenant-scoped tables only (`NativeSqlRegisterTest` derives its roster from
  `@TenantId`-bearing entities), so these are correctly absent — they stay JDBC
  and stay off the register.
- **Sites retired/converted by T-14/T-15/T-16 — confirmed gone from the native
  inventory.** `LanguageCodeLookup` (T-14 → RM-4 `Language` JPA repo),
  `JpaClubStateRepository` + `JpaCountryRepository` (T-15 → derived JPQL after the
  V40 column-level ICU collation), `PlanningDayPersistenceProbeImpl`
  (T-16 → `reservations.api.ReservationCountPort`), and `MeService` (RM-4) no
  longer hold any `JdbcTemplate`/`createNativeQuery` call — the only remaining
  textual hits in those files are javadoc notes recording the retirement. None
  of those touched a tenant-scoped table, so none ever held a register entry to
  remove; the two read-model retirements that *did* have entries are logged under
  `## Retired`.

When you need to add one:

1. Open a PR that updates this file with the entry below filled in.
2. The PR must be reviewed by both a tech lead and a security reviewer
   (CODEOWNERS rule, not yet wired — see drift-control TODO).
3. S-024's CI grep (added in that story) checks every `@Query(nativeQuery = true)`
   and every direct `JdbcTemplate` call against tenant-scoped table names.
   Calls not present in this register fail the build.
4. Expired entries (past `expires`) trigger a build warning + a follow-up
   review.

## Retired

- `flight-report-read-model` — retired 2026-06-11 by ADR 0027 RM-3: the report
  read path is now plain JPA over `t_flight_report_row` (domain-maintained
  read-model, `JpaFlightReportReadAdapter`); the native-SQL caller was deleted.
- `planning-day-reservation-count` — retired 2026-06-12 by J-26 T-16 (the
  entry's own "Remove when"): a shared cross-module count port
  (`reservations.api.ReservationCountPort`, a Spring Modulith `@NamedInterface`)
  now exposes the per-day reservation count, so `planning` reads
  `NumberOfAircraftReservations` through the `reservations` domain API instead
  of native SQL. The implementation (`JpaReservationCountAdapter` →
  `JpaAircraftReservationRepository.countActiveOnDayAtLocation`) is plain JPQL
  over `AircraftReservation`: the `date(reservation_start) = :planningDate` cast
  became a derived half-open UTC-day range
  (`reservationStart >= dayStart AND reservationStart < dayStart + 1 day`),
  tenant-filtered via Hibernate's `@TenantId` discriminator. The native caller
  in `PlanningDayPersistenceProbeImpl` was deleted.

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
