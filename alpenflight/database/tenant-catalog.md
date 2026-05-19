# Tenant-scope catalog

Source-of-truth narrative for which entities are tenant-scoped, cross-tenant,
or otherwise special-cased under the structural multi-tenancy approach
([ADR 0008](../modernization/adrs/0008-multi-tenancy-mechanism.md)). Read this
file before adding a new `@Entity` to the new schema. Adding entities without
updating this catalog will fail S-022's build-time reflection check.

> Companion artifacts:
>
> - **`tenant-rules.yaml`** — committed YAML overrides + the public-flow
>   allowlist seed + the unscoped call-site matrix. The machine input.
> - **`extract/raw/tenant-classification.json`** — re-emitted by the legacy
>   extractor on every run; the machine output S-022 / S-023 / S-024 / S-025
>   consume.
> - **`native-sql-register.md`** — sibling file listing every approved native
>   SQL query against a tenant-scoped table (initially empty).

## Source-of-truth precedence

- **Authoritative for the new schema:** this MD + `tenant-rules.yaml`.
  When a future implementer needs to know "is X tenant-scoped?", the YAML is
  the answer; this MD explains the reasoning.
- **Authoritative for the legacy shape:** S-010's extractor output
  (`columns.json`, `fks.json`). The YAML carries `target_entity` mappings;
  the MD calls out the load-bearing reshape cases (chiefly `Flights` → `Flight`
  with denormalized `club_id`).
- The EF migration tree at `flsserver/src/FLS.Server.Data/Migrations/` is
  **frozen at the 2015 baseline** per S-010 Assumptions §3 and is not consulted.

## Classification taxonomy (six values)

The closed vocabulary every catalog row must use:

| Value | Definition | Hibernate `@TenantId`? |
|---|---|---|
| `TENANT_SCOPED` | Carries `club_id` (legacy `ClubId` / `OwnerClubId`); filtered on every JPA query. | yes |
| `CROSS_TENANT` | Referenced by tenant-scoped entities via FK but not itself tenant-filtered. Loading by PK works across tenants by design. | no |
| `INDIRECT_TENANT` | Legacy shape — no native `ClubId`, but reaches tenant scope through a single FK hop. **In the new schema these are reshaped to `TENANT_SCOPED` via denormalization.** | yes (after reshape) |
| `REFERENCE_DATA` | Static lookup data shared across all tenants. Never gets `@TenantId`. | no |
| `SYSTEM_GLOBAL` | System-level configuration, migration metadata, single-row settings. | no |
| `PRINCIPAL_SUBJECT` | Has a `ClubId` column but tenancy resolves FROM this entity, not OVER it. Cannot carry `@TenantId` (resolver chicken-and-egg). | no |

Assignment rule (first match wins) is implemented in
`extract/src/main/java/ch/fls/legacyextract/tenant/TenantClassifier.java`.

## Sacred-cow call-outs

### Flights — the canonical reshape case

Legacy: `Flights` has **no `ClubId` column** ([S-010](../modernization/stories/implemented/S-010-parity-baseline-extraction.md)
findings, confirmed by `columns.json`). Tenancy reaches `Flights` only via
`Flights.AircraftId → Aircrafts.OwnerClubId` — one FK hop.

| | `legacy_scope` | `target_scope` |
|---|---|---|
| `Flights` | `INDIRECT_TENANT` | `TENANT_SCOPED` |

**Precondition for S-013:** add `club_id NOT NULL` to the new `flight` table,
populated at migration time from `aircraft.owner_club_id`. S-016 maintains
the invariant during cutover (`flight.club_id == aircraft.owner_club_id`).

Without the denormalization, every `flight` list query would have to JOIN
`aircraft` — at 100K+ rows, a measurable p95 cost on the hottest query in
the system.

### FlightCrew — composite UNIQUE + the cross-club crew sacred cow

`FlightCrew` is `TENANT_SCOPED` (carries the flight's `club_id` transitively).
It has a composite UNIQUE on `(FlightId, PersonId, CrewType)` — the same
person cannot be both pilot and instructor on the same flight.

`FlightCrew.PersonId` is a **ride-through** to `Person` (a `CROSS_TENANT`
entity): the Person may belong to a different club than the flight's operating
club. This is the multi-club-pilot sacred cow. Hibernate's `@TenantId` does
not apply to FK loads by primary key, so the cross-club Person fetch works
naturally — but a hypothetical `personRepository.findAll()` would silently
return zero for cross-club Persons (Person has no `@TenantId`). Document the
trap; do not add `@TenantId` to Person.

### AuditLogs + AuditLogDetails — tenant-scoped row, cross-cluster payload

Both `AuditLogs` and `AuditLogDetails` are `TENANT_SCOPED` (the row's `ClubId`
identifies which tenant's mutation is being recorded). The `AuditLogDetails`
`OriginalValue` / `NewValue` `nvarchar(max)` blob columns hold serialized
entity snapshots — including cross-cluster Person data, IBAN strings, license
numbers. Flagged `pii_blob: true` in the YAML.

**DSAR scope cascades cross-tenant.** When a Person requests data deletion,
their audit blobs need scrubbing in every club they touched — a cross-tenant
operation requiring an `UnscopedTenantContext` (S-023). S-027 owns the
redaction.

### Person / PersonClub — cross-tenant by construction

`Person` is `CROSS_TENANT`. A Person can belong to multiple Clubs via
`PersonClub`; their direct-identifier columns
(`Firstname`/`Lastname`/`Email*`/`Phone*`/`Birthday`/`AddressLine*`) exist
once globally.

`PersonClub` is also `CROSS_TENANT` — it's the join table that expresses
N-to-N membership. Classifying it as `TENANT_SCOPED` would break multi-club
pilots; classifying it as `REFERENCE_DATA` would lose the obvious tenant
association in admin views.

**FK ride-throughs to Person** are inventoried per `ride_through_targets` in
the YAML for S-024 to assert: `FlightCrew.PersonId`, every `*PilotPersonId`
on `Flights`, `Delivery.RecipientPersonId`, `AircraftReservation.*PersonId`,
`PlanningDayAssignment.*PersonId`. Each must load cross-club correctly.

### User — has `ClubId` but is the principal subject

`User` carries a `ClubId` column (the user's home club) but is the principal
SUBJECT of tenancy, not a tenant-scoped row. Hibernate's
`CurrentTenantIdentifierResolver` reads the tenant FROM the authenticated
User; adding `@TenantId` to User would chicken-and-egg the user load before
any tenant context exists.

Classified `PRINCIPAL_SUBJECT`. Per-club user lists run through `UserRole`
(itself `SYSTEM_GLOBAL`) + an explicit `clubId` filter applied at the
service layer, not via Hibernate.

### Reference data — opt-out of tenancy

`Countries`, `Languages`, `LanguageTranslations`, all `*Types`,
`AircraftStates`, `FlightAirStates`, `FlightProcessStates`, `MemberStates`,
`ClubStates`, `*UnitTypes`, `PersonCategories`. Shared across all tenants;
never get `@TenantId`. The reference-data set is enumerated in
`tenant-rules.yaml` under `kind: reference`.

## Tenant-derivation strategy for public flows

`TrialFlightRegistration` and `PassengerFlightRegistration` run **without
an authenticated principal** — there's no `SecurityContext` to read a
`clubId` claim from. Tenant resolution proceeds through a URL-slug lookup:

1. URL path segment: `/r/{slug}/trial-flight` or `/r/{slug}/passenger-flight`.
2. Slug pattern: `^[a-z0-9-]{3,40}$` (Jakarta `@Pattern` validated).
3. Allowlist storage: DB table `public_flow_club` with columns
   `(club_id, url_slug, flow_kind, enabled)`. Operators enable/disable per
   club from the admin UI without a redeploy.
4. Unknown slug → **404 with constant-time response** (not 403; defeats
   timing-oracle enumeration).
5. Disabled-club lookup returns the same 404 shape — same response time as
   a missing slug.
6. Once resolved, the flow runs under `UnscopedTenantContext.runAs(clubId)`
   for the duration of the registration request.

Rate-limiting (per-IP + per-slug buckets) is required to prevent slug
enumeration; the mechanism is out of S-011's scope (defer to ADR) but the
YAML flags the requirement.

S-013 designs the `public_flow_club` schema; S-025 implements the resolver.
S-011 only declares the contract.

## Unscoped call-site matrix

Five legitimate cross-tenant operations are named in `tenant-rules.yaml`
under `unscoped_call_sites`:

| Call site | Principal | Reason |
|---|---|---|
| `system-admin-reports` | `SYSTEM_ADMIN` | Cross-club rollup reports for system administrators |
| `ogn-ingest` | `OGN_INGEST` | OGN flight ingestion writes flights for many clubs from a service principal |
| `daily-scheduled-jobs` | `SCHEDULED_JOB` | Statistics, license-expiry checks across all tenants |
| `public-slug-resolution` | `ANONYMOUS` | Pre-tenant slug lookup before context is established |
| `fadp-dsar-cross-club-search` | `DPO` | Cross-club audit-blob redaction on Person erasure |

S-023 implements `UnscopedTenantContext` with this set as its whitelist.

## Downstream contract

Every consumer reads `raw/tenant-classification.json` (re-emitted by the
extractor on demand):

| Consumer | Reads | Use |
|---|---|---|
| **S-022** | `target_scope ∈ {TENANT_SCOPED, INDIRECT_TENANT}` | Apply `@TenantId` annotations; INDIRECT entries pair with S-013 denormalization preconditions |
| **S-023** | `target_scope ∈ {CROSS_TENANT, SYSTEM_GLOBAL}` + `unscoped_call_sites` | Build `UnscopedTenantContext` whitelist |
| **S-024** | Full catalog + `ride_through_targets` | Leakage CI test — per-bucket assertions |
| **S-025** | `public_flow_allowlist` block + `public_flow_club` schema shape | URL-slug → `club_id` resolution |
| **S-016** | `legacy_scope` + `preconditions` per entity | Migration mapping incl. the `Flights → Flight` reshape |
| **S-027** | `pii_blob: true` entities | Audit-log blob redaction during Person erasure |

## Drift-control mechanism

S-022 implements a **build-time `@Entity` reflection check** (Assumptions §2
on the story file). At test boot, the check walks all `@Entity`-annotated
Java classes and:

- Every entity with `@TenantId` must have a `target_scope ∈ {TENANT_SCOPED, INDIRECT_TENANT}` entry in the catalog.
- Every entity without `@TenantId` must have a `target_scope ∈ {CROSS_TENANT, SYSTEM_GLOBAL, REFERENCE_DATA, PRINCIPAL_SUBJECT}` entry.
- Every catalog entry's `target_entity` must resolve to an actual `@Entity` class.

New entity without a catalog entry → build fails. Stale catalog entry → build
fails. The catalog is a contract, not a snapshot.

`CODEOWNERS` should require security + tech-lead review on changes to this
MD + `tenant-rules.yaml`.

## Naming conventions

- `legacy_table` matches S-010's `tables.json[*].table_name` verbatim
  (e.g. `Flights`, `PersonClub`).
- `target_entity` matches the AlpenFlight JPA entity name
  (e.g. `Flight`, `PersonClub`). Singular for entity tables, retains the
  legacy spelling for join tables.
- `tenant_column` is `club_id` (snake_case, target schema convention) on every
  `TENANT_SCOPED` entity.

## Tenant-id column type

`Long`. Eight-byte index entries; smaller than UUID; matches the legacy
`bigint ClubId`. Per-instance unique is sufficient — the system is single-deployment
per operator (ADR 0001's solo-operator profile). Pinned in
`tenant-rules.yaml` under `tenant_id_type` so S-022 reads it deterministically.

If a future cross-instance migration requires globally-unique tenant
identifiers, the catalog header is the single place to flip to `UUID` —
S-022 picks up the change.
