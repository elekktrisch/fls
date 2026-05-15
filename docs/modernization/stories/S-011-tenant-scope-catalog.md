---
id: S-011
title: Catalog tenant-scoped vs cross-tenant entities
epic: E-02
status: done
started_at: 2026-05-15
done_at: 2026-05-15
github_issue: 17
github_pr: 18
depends_on: [S-010]
acceptance:
  - A reference doc `next/database/tenant-catalog.md` lists every entity in two columns: tenant-scoped (carries `club_id`) vs. cross-tenant (no `club_id`).
  - The doc explains the rationale per entity (especially the gray-area ones: `Person`, `Aircraft`, `Location`).
  - Public-flow targets (TrialFlightRegistration, PassengerFlightRegistration) have a documented tenant-derivation strategy (URL slug → club_id allowlist).
estimate: S
adr_refs: [0008]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
ADR 0008 + the cross-tenant-Person edge case (a Flight's crew can reference a Person from another club via PersonClub) make this classification non-trivial. Getting it wrong here causes either: (a) leaks (R1), or (b) breaks multi-club pilot rosters (sacred cow).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Read `01-current-state.md §5` carefully — note the cross-cluster constraints.
- [ ] Classify every entity from S-010's baseline.
- [ ] For gray-area entities (Person, Aircraft if shared, Location if shared), document the rule: which queries are tenant-scoped, which are cross-tenant by design.
- [ ] Flag the cross-tenant references that ride through tenant-scoped entities (e.g. `Flight.PersonId → Person` where Person has no `club_id` — Flight's `club_id` is the operative tenant).
- [ ] Cross-check the catalog with [S-024](#) (the leakage CI test will run against this list).

## Notes
The legacy convention is "every entity has `club_id` and every query filters on it." The reality is more nuanced: reference data (Country, LanguageTranslation), `User` (single `ClubId` per user, not a tenant attribute on rows), and `Person` (cross-tenant via `PersonClub`) all sit outside the tenant-scoped rule. Get this catalog right or downstream stories break.

<!-- modernize-refine: start -->

## Design notes

### Artifact layout

Hybrid shape — slim curation MD + committed YAML overrides + extractor-emitted JSON. Mirrors S-010's "re-runnable tooling over frozen MD" precedent:

```
next/database/
├── tenant-catalog.md           # Slim narrative (~250 lines): taxonomy, sacred-cow
│                                # reasoning, gray-area justifications, downstream contract.
├── tenant-rules.yaml           # Committed: human-curated overrides + public-flow
│                                # allowlist seed. Source of truth for irreducible-judgment
│                                # cases (Person, User, PersonClub, AuditLogs, Flights).
└── extract/                    # Extends the existing S-010 Spring Boot CLI.
    └── src/main/java/ch/fls/legacyextract/tenant/
        ├── TenantScope.java          # Sealed enum: TENANT_SCOPED, CROSS_TENANT,
        │                              # SYSTEM_GLOBAL, REFERENCE_DATA, INDIRECT_TENANT,
        │                              # PRINCIPAL_SUBJECT.
        └── TenantClassifier.java     # Pure function: (columns.json, fks.json,
                                       # tenant-rules.yaml) → tenant-classification.json
                                       # emitted under raw/ (gitignored, ephemeral).
```

The integration test in S-010's module is extended with one new assertion: every entity in the seeded FLSTest fixture reaches a terminal classification (no `UNKNOWN`).

### Classification taxonomy

Six values. Assignment rule applied **in order**; first match wins:

| Value | Rule | Examples |
|---|---|---|
| `REFERENCE_DATA` | YAML override `kind: reference` | `Country`, `LanguageTranslation`, `AircraftType` |
| `PRINCIPAL_SUBJECT` | YAML override `kind: principal` | `User` (has `ClubId` but is the subject of tenancy, not a scoped row) |
| `CROSS_TENANT` | YAML override `kind: cross-tenant` | `Person`, `PersonClub`, system-admin views |
| `SYSTEM_GLOBAL` | YAML override `kind: system` | `Setting`, migration metadata |
| `TENANT_SCOPED` | Table has a `ClubId` column | `Aircraft`, `PlanningDay`, `Delivery`, `AccountingRuleFilter`, `AuditLogs`, `AuditLogDetails` |
| `INDIRECT_TENANT` | No `ClubId` AND FK reaches a `TENANT_SCOPED` table within 1 hop | `Flights` (→ `Aircrafts.OwnerClubId`) |

Cases that don't match any rule land in `UNKNOWN`. The catalog integration test fails on any `UNKNOWN` — forces the implementer to add a `tenant-rules.yaml` override.

### Flights — legacy `INDIRECT_TENANT`, target `TENANT_SCOPED` with denormalization precondition

Catalog stores **two scope columns per entity**: `legacy_scope` (what S-010's extract sees today) and `target_scope` (what S-022 / S-013 should land). Most entities have identical values; `Flights` is the canonical reshape case:

| Entity | `legacy_scope` | `target_scope` | Precondition |
|---|---|---|---|
| `Flights` | `INDIRECT_TENANT` | `TENANT_SCOPED` | **S-013 must add `club_id NOT NULL` to the new `flight` table, populated from `aircraft.owner_club_id` at migration time.** S-016 maintains the invariant during cutover. |

Rationale for the reshape: `INDIRECT_TENANT` would force every Flight list query to JOIN `aircraft` and would not satisfy ADR 0008's "single `@TenantId` column per scoped entity." At 100K+ Flight rows the JOIN hop is a measurable p95 cost.

### Public-flow allowlist

DB table, **not** application property — operators need to enable/disable public flows per club from the admin UI without a redeploy.

```yaml
# tenant-rules.yaml seed only — runtime source is the DB table.
public_flow_allowlist:
  table: public_flow_club          # see also security-engineer's suggested name
                                    # `club_public_slug`; pick one at implement time.
  columns: [club_id, url_slug, flow_kind, enabled]
  flow_kinds: [TRIAL_FLIGHT, PASSENGER_FLIGHT]
```

S-025 implements the URL-slug resolver → `UnscopedTenantContext.runAs(clubId)`. S-011 only declares the contract.

### Downstream contract — what S-022 / S-023 / S-024 / S-025 consume

Single machine-readable artifact `raw/tenant-classification.json` (ephemeral, gitignored, re-emitted on demand):

```json
{
  "version": 1,
  "generated_at": "2026-05-15T...",
  "entities": [
    {
      "legacy_table": "Flights",
      "target_entity": "Flight",
      "legacy_scope": "INDIRECT_TENANT",
      "target_scope": "TENANT_SCOPED",
      "tenant_column": "club_id",
      "rationale_ref": "tenant-catalog.md#flights",
      "preconditions": ["S-013 denormalize club_id from aircraft.owner_club_id"],
      "pii_blob": false,
      "emits_audit": true,
      "ride_through_targets": ["Person", "Aircraft"]
    }
  ]
}
```

Consumers:

| Story | Consumes | Use |
|---|---|---|
| **S-022** | `target_scope ∈ {TENANT_SCOPED, INDIRECT_TENANT}` | Adds `@TenantId` to each entity; `INDIRECT_TENANT` entries pair with S-013 denormalization |
| **S-023** | `target_scope ∈ {CROSS_TENANT, SYSTEM_GLOBAL}` + the unscoped call-site matrix | `UnscopedTenantContext` whitelist |
| **S-024** | Full catalog + `ride_through_targets` | Leakage CI test iterates: tenant-scoped → assert cross-tenant read is empty; cross-tenant → assert legitimate join path returns the row |
| **S-025** | `public_flow_allowlist` block + `public_flow_club` shape | URL-slug → club_id resolution |

### Native-SQL escape-hatch register

ADR 0008 explicitly notes Hibernate `@TenantId` doesn't filter native SQL — that's an attack surface. Ship a sibling file `next/database/native-sql-register.md` listing every approved native query against a tenant-scoped table (initially empty). CI grep on `@Query(nativeQuery = true)` or `JdbcTemplate` against tenant-scoped table names fails the build if not in the register. The register itself is owned by S-024 (which adds the CI check); S-011 only declares the register file and contract.

### Alternatives considered

- **Chosen: hybrid MD + YAML + extractor-emitted JSON.** Honors S-010's operator overrides (re-runnable tooling, ephemeral JSON, Spring Boot CLI, integration tests). Captures irreducible-judgment cases in YAML where reviewers can diff them.
- **Rejected: pure hand-written `tenant-catalog.md`.** What the AC currently says. A hand-maintained list drifts the moment FLSTest changes. Operator already rejected the equivalent shape in S-010.
- **Rejected: fully auto-derived JSON, no YAML, no MD.** `User`, `Person`, `AuditLogs`, `PersonClub` cannot be classified from column presence alone — they need recorded human judgment, and Person's cross-tenant nature is a sacred cow that must live in prose.

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — two-column tenant-scoped vs cross-tenant.** The binary split is wrong. `Flights` has no `ClubId` → neither column fits. `User` has a `ClubId` but is the principal subject, not a scoped row. `AuditLogs.ClubId` exists but the payload blobs (`OriginalValue`/`NewValue`) reference cross-tenant Persons. **The implementation must replace AC1's two-column shape with the six-value taxonomy above.** Catalog table grows two extra columns: `legacy_scope` + `target_scope` (Flights reshape).

**AC2 — gray-area rationale.** AC lists three (Person, Aircraft, Location) but at least seven more need explicit treatment: `User`, `PersonClub`, `AuditLogs`/`AuditLogDetails`, `FlightCrew.PersonId` ride-throughs, `Delivery.RecipientPersonId` ride-through, `AccountingRuleFilter` per-club but references shared master data, reference-data tables (`Country`, `LanguageTranslation`, etc.). All must carry rationale; ride-throughs are FK edges, not entities, but they feed the cross-tenant-reference inventory consumed by S-024.

**AC3 — public-flow allowlist.** AC underspecifies. Concrete asks:
- Slug source: URL path segment `/r/{slug}/...`, not query string or form field.
- Format: `^[a-z0-9-]{3,40}$`, Jakarta `@Pattern` validated.
- Storage: DB table (not application property — operators enable/disable per club without redeploy).
- Unknown-slug response: 404 (not 403, not "club not found") + constant-time response to defeat timing oracles.
- Soft-deleted / disabled clubs: reject before persist with the same 404 shape.
- Rate-limiting: per-IP and per-slug buckets to prevent enumeration. Mechanism out of scope (defer to ADR) but the catalog flags the requirement.

### Hidden requirements (promote or surface)

- **Cross-tenant-reference inventory** — every FK from a tenant-scoped entity to a cross-tenant entity. S-024 iterates this list. Promote from task line 26 to a new AC4.
- **`legacy_scope` + `target_scope` dual columns.** S-016 (migration) consumes `legacy_scope`; S-022 consumes `target_scope`. Without both, downstream tools have to re-derive.
- **`tenancy_enforcement` marker per entity:** `hibernate_only` (the default) vs `hibernate_plus_rls` (if ADR 0008's RLS follow-up lands). Catalog reserves the column even if all entries are `hibernate_only` today.
- **`emits_audit` boolean per entity.** Tenant-scoped mutations emit; reference data doesn't. Drives the audit-log schema in S-013/S-027.
- **`pii_blob` flag.** `AuditLogDetails.OriginalValue`/`NewValue` carry cross-cluster PII. Cross-references S-010 §4 PII catalog. Drives FADP DSAR scope.
- **OGN ingestion unscoped writes.** ADR 0008 calls it out; catalog must list `OGN_INGEST` as a named unscoped call site even though the endpoint itself lands in S-066.
- **Read-heavy vs write-heavy hint per tenant-scoped entity.** Informs composite-index column ordering in S-013. `Flights` + `AuditLogDetails` are write-heavy; `Aircraft` + `Club` are read-heavy.

### Scope clarifications

**In scope:**
- Classification of every entity from S-010's `tables.json` into the six-value taxonomy.
- Per-entity rationale for non-obvious cases; FK-chain documentation for `INDIRECT_TENANT`.
- Cross-tenant ride-through inventory (FK from tenant-scoped → cross-tenant entity).
- Public-flow allowlist contract (DB table shape + URL-slug rules).
- Native-SQL escape-hatch register file (initially empty).
- Machine-readable `tenant-classification.json` emitted by the extended extractor.

**Out of scope:**
- `@TenantId` annotation on Java entities → **S-022**.
- Leakage CI test (live data) → **S-024**.
- `UnscopedTenantContext` mechanism → **S-023**.
- `tenant_slug` column / DB allowlist schema → **S-013** schema design.
- OGN ingestion endpoint and its unscoped write contract → **S-066**.
- Postgres RLS defense-in-depth → ADR 0008 follow-up story.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | Tenant-scoped entity misclassified as cross-tenant → missing `@TenantId` → R1 reborn. Worst offenders: `Flight`, `AuditLogs`/`AuditLogDetails`, `AccountingRuleFilter`, `Delivery`. | **Critical** | Catalog drift-detection (S-022 build-time `@Entity` reflection vs catalog); S-024 leakage CI test iterates the catalog. |
| (b) | Cross-tenant misclassified as tenant-scoped → sacred cows die (cross-club crew, shared aircraft, multi-club Persons via PersonClub). | High | Catalog notes the legitimate cross-tenant join path per entity. |
| (c) | `INDIRECT_TENANT` denormalization drift — `flight.club_id` denormalized from `aircraft.owner_club_id` can diverge if aircraft ownership changes. | High | S-013 adds DB-level trigger or write-path invariant; parity test asserts `flight.club_id == aircraft.owner_club_id` on insert and on aircraft-owner update. |
| (d) | Public-flow slug spoofing on unauthenticated endpoints — enumerate slugs to harvest club existence, post writes against arbitrary tenants. | High | Slug-pattern validation, 404 (not 403) on unknown, constant-time response, rate-limit per IP + per slug. |
| (e) | Audit-log tenancy leak — mislabeled `AuditLogs.ClubId` leaks forensic trail across tenants. | **Critical** | Classify `AuditLogs` + `AuditLogDetails` as `TENANT_SCOPED` + `pii_blob: true`; S-027 writes through tenant-scoped session. |
| (f) | Native SQL / raw JDBC bypass — `@TenantId` doesn't filter native queries. | Med | Native-SQL escape-hatch register + CI grep; recommend RLS follow-up as defense-in-depth (ADR 0008 follow-up). |

### Authorization

- **Anonymous resolver behavior must be specified.** When `SecurityContext` has no `clubId` claim, `CurrentTenantIdentifierResolver` returning null causes Hibernate to fail or skip the filter (version-dependent). Catalog mandates: resolver returns a sentinel `__no_tenant__` that matches zero rows on every tenant-scoped table (**fail-closed**). Pin Hibernate version + behavior in catalog header.
- **Legitimate unscoped call sites — name them:** system-admin reports, OGN ingestion, daily scheduled jobs, public-flow slug resolution, FADP DSAR cross-tenant search. Each gets a named entry → `UnscopedTenantContext` whitelist → entity scope mask.
- Unscoped sessions require role `SYSTEM_ADMIN` or service-principal `OGN_INGEST`.

### Input validation

- Slug source = URL path segment (`/r/{slug}/...`), not query/form. Validate `^[a-z0-9-]{3,40}$` via Jakarta `@Pattern`.
- Allowlist storage = DB table `public_flow_club` (or `club_public_slug` — pick at implement). Updates via authenticated admin endpoint, audited.
- Rate-limiting: per-IP (10 req/min for resolution) + per-slug (3 submissions/hour). Defer mechanism to ADR; flag the requirement.
- Slug unknown → 404 with constant-time response (same as known-but-disabled).

### PII handling

Cross-reference S-010 §4. Per-entity flags in catalog:
- `Person.Firstname/Lastname/Email/Phone/Birthday/AddressLine*` → direct-identifier, **cross-tenant**. DSAR scope: Person's data exists once globally; their tenant-scoped Flight/Delivery rows ripple per club. Erasure cascade is cross-tenant by design.
- `AuditLogs.ClubId` + `AuditLogDetails.OriginalValue/NewValue` → audit-payload, **tenant-scoped**. Redaction-on-erasure follows tenant scope. When a Person is erased, audit blobs across ALL their clubs need scrubbing — requires unscoped session + per-row tenant context inheritance.
- `User.PasswordHash/EmailConfirmation` → auth-artifact, cross-tenant. Never logged, never in audit before/after.
- Build-time check fails if a tenant-scoped entity carries a PII column without a redaction marker.

### Audit-log events

- Per-entity flag: `emits_audit: yes|no`. Tenant-scoped mutations emit; reference data doesn't.
- Cross-tenant mutations (OGN ingestion writing flights for many clubs) emit per-club audit rows; calling principal is system but each per-row write inherits the row's tenant.
- Event payload: `{actor_user_id, actor_club_id (or "__system__"), tenant_club_id, event_type, target_entity, target_id, before, after, ts}`.

### Cross-tenant leakage

Catalog ships in **two forms** so S-024 iterates programmatically:
- Human doc `tenant-catalog.md` (rationale).
- Machine-readable `raw/tenant-classification.json` (ephemeral, re-emit on demand).

S-024 then auto-generates: for each `TENANT_SCOPED`/`INDIRECT_TENANT` entry, create-as-A / read-as-B / assert-empty; for each `CROSS_TENANT` entry, assert the documented join path returns the cross-club row.

### OWASP applicability

- **A01 Broken Access Control (primary).** Catalog is the source-of-truth for which entities require structural tenant gating; misclassification is direct A01.
- **A02 Cryptographic Failures (indirect).** `AuditLogDetails` blobs may contain PII; per-tenant DEK envelope encryption per S-010 §4. Catalog flags `pii_blob: true`.
- **A04 Insecure Design (the meta-concern).** Legacy R1 is exactly an A04. Catalog converts convention to structural design.
- **A05 Security Misconfiguration.** Hibernate version pin, resolver fail-closed semantics, RLS-on-by-default if adopted.
- **A07 Identification & Authentication Failures (public flow).** Slug-based pseudo-auth must be hardened.
- **A08 Data Integrity Failures.** `INDIRECT_TENANT` denormalization drift is an integrity failure.
- **A09 Security Logging & Monitoring.** Audit-log tenancy classification drives forensic correctness.

### Story-specific concerns

- **CODEOWNERS on `next/database/tenant-catalog.{md,yaml}`** — security + tech-lead review required on any change. Catalog drift = security incident.
- **CI drift detection (S-022 land):** walk all `@Entity` classes via reflection; assert every entity has a corresponding catalog entry; new entity without entry = build fail. Symmetric: catalog entry referencing nonexistent entity = build fail.
- **Catalog versioning:** include schema version + Hibernate version pin in YAML header; S-024 asserts both at test boot.
- **Native-SQL register** (sibling file): every approved native query against tenant-scoped tables, justification, reviewer, expiry date. CI grep on `createNativeQuery`/`JdbcTemplate` against tenant-scoped tables fails if not in register.
- **Fail-closed resolver contract** documented in catalog header — Hibernate behavior for null tenant must be tested and pinned; version upgrades re-verify.

## Test plan

### Coverage contract

**S-011 owns:** catalog completeness (every entity present), bucket validity (closed-vocabulary), rationale presence (gray-area cases), schema-coherence (tenant-scoped ↔ `ClubId` column exists in `columns.json`), machine-readability (downstream stories parse it), cross-tenant ride-through inventory.

**S-011 explicitly does NOT own:** `@TenantId` annotations (S-022), `UnscopedTenantContext` (S-023), live leakage assertions (S-024), URL-slug resolver (S-025), native-SQL escape-hatch CI grep (S-024).

### Verification layers (replaces traditional pyramid)

A catalog is documentation; the verification layer is integration-test-shaped checks against the catalog + S-010's JSON outputs.

| Layer | Count | Strategy |
|---|---|---|
| Completeness verifier | 1 | JUnit 5, set-equality between catalog entity IDs and S-010's `tables.json[*].name`. |
| Bucket / schema-sanity verifier | 1 | JUnit 5, reads catalog + `columns.json` + `fks.json`; per-bucket invariants. |
| Cross-reference verifier | 1 | JUnit 5, reads catalog + `fks.json`; emits ride-through list as a test artifact for S-024. |
| Downstream-contract verifier | 1 | JUnit 5, parses catalog via the documented machine-readable contract; asserts required keys per entry. |

Implementation: extend S-010's `next/database/extract/` Gradle subproject with one new integration test method (assertions run against the same FLSTest-seeded SQL Server the extractor already uses). No new Gradle subproject; no Testcontainers second instance. Re-uses the docker-CLI lifecycle from S-010.

### Specific test cases

**Completeness verifier**
- `catalog_classifies_every_table_in_S010_baseline` — set(catalog.entityIds) == set(tables.json[*].name).
- `catalog_has_no_orphan_entries` — no entity in catalog absent from tables.json.
- `new_table_in_tables_json_fails_loud` — parameterized; inject fake "NewTable"; verifier fails naming `NewTable`.

**Bucket / schema-sanity verifier**
- `tenant_scoped_entities_have_clubid_column` — for each `bucket=TENANT_SCOPED` entity, `columns.json` contains `ClubId`.
- `indirect_tenant_entities_document_fk_path` — for each `INDIRECT_TENANT`, rationale contains parseable `via: <FK> -> <ParentTable>.<ClubIdColumn>`. **Flights must appear with `via: AircraftId -> Aircrafts.OwnerClubId` and a "denormalize club_id for S-013" recommendation.**
- `cross_tenant_without_clubid_or_with_justified_override` — `CROSS_TENANT` entity has no `ClubId`, or rationale carries `override:` token.
- `reference_data_has_no_clubid` — invariant. Country, LanguageTranslation classified here.
- `principal_subject_classification_is_explicit` — User must be classified `PRINCIPAL_SUBJECT`; test pins the choice so reclassification trips review.
- `audit_logs_flagged_for_cross_cluster_pii` — `AuditLogs` and `AuditLogDetails` are `TENANT_SCOPED` AND `pii_blob: true`.
- `person_rationale_explains_personclub_join` — Person is `CROSS_TENANT`; rationale references `PersonClub` join path.

**Cross-reference verifier**
- `every_tenant_scoped_to_cross_tenant_fk_is_listed` — walk `fks.json`; for each FK where source bucket=`TENANT_SCOPED` and target bucket=`CROSS_TENANT`, catalog has a `ride_through` entry.
- `ride_through_targets_are_only_cross_tenant_or_reference_data` — invariant; never a ride-through to a `TENANT_SCOPED` target (that's a real leak vector, not ride-through).

**Downstream-contract verifier**
- `catalog_parses_under_documented_schema` — required keys: `id`, `bucket`, `rationale`; optional: `ride_through`, `via`, `override`, `pii_blob`, `emits_audit`, `preconditions`.
- `bucket_values_are_from_closed_set` — bucket ∈ closed 6-value vocabulary.

### Parity strategy

No legacy oracle to diff against, but the **legacy `ClubId`-bearing set** is the baseline truth-claim:
- `legacy_clubid_bearing_tables_are_all_accounted_for` — every legacy `ClubId`-bearing table is classified in the new catalog. Reshape is allowed (Flights: `INDIRECT_TENANT` → `TENANT_SCOPED` with denormalization), but every reshape carries a `legacy_shape:` token in rationale + forward pointer to the redesign story.

### Test data + fixtures

- **Primary fixture:** S-010's FLSTest-seeded SQL Server (re-used).
- **Inputs:** the extractor's JSON outputs (`tables.json`, `columns.json`, `fks.json`) consumed in-memory by the verifier; no need to commit fixture copies if the verifier runs in the same Gradle subproject as the extractor.
- **No new docker container** — re-uses S-010's `MssqlTestContainerLifecycle`.

### Doc-as-oracle for downstream

(Already captured in Design notes' Downstream contract table — S-022 / S-023 / S-024 / S-025 consume the JSON.)

### Coverage gaps (deferred)

- Live leakage assertion on seeded data → **S-024**.
- `@TenantId` annotation presence on every JPA entity → **S-022** (compile-time + reflection test).
- `CurrentTenantIdentifierResolver` correctness → **S-022**.
- `UnscopedTenantContext` mechanism + admin-report path → **S-023**.
- Native-SQL escape-hatch CI grep → **S-024**.
- URL-slug → club_id resolver wiring → **S-025**.

### Risks

- **Catalog drift as new entities land in `next/` schema.** Verifier reads from a *current* extractor output. Once S-013 lands and emits the new Postgres schema via an analogous extractor, S-011's verifier re-points. Document as a TODO in the catalog README.
- **Heuristic disagreement with hand-curated overrides** — catalog stores YAML overrides as source of truth; verifier reports auto-classification disagreements as warnings, not failures.
- **Frozen fixture rot** — N/A here since the verifier runs against the live extractor output, not committed JSON.

## Performance plan

### Hot paths (driven by the catalog, executed downstream)

- `Flight` list page (`GET /flights?...`) at 100K+ rows — always filtered by `club_id` (auto-appended by `@TenantId`) + sorted by `flight_date DESC`. Highest-volume tenant-scoped read.
- `Person` directory per-club — Person is `CROSS_TENANT`, per-club view goes through `PersonClub`.
- `Delivery` list per club — invoice workflow, filtered by `(club_id, status)`.
- `AircraftReservation` scheduler — hot during booking hours, filtered by `(club_id, start_at)`.
- `AuditLog` per-tenant trail — cold read, hot write.

### Required indexes implied by the catalog

S-013 owns implementation; the catalog records the recommended composite ordering as hints. `club_id` always leads — it's the always-present `WHERE` clause from `@TenantId`.

- `flight (club_id, flight_date DESC)`
- `aircraft_reservation (club_id, start_at DESC)`
- `delivery (club_id, status, created_at DESC)`
- `accounting_rule_filter (club_id)` plus rules-engine config columns
- `audit_log (club_id, created_at DESC)`
- `person_club (club_id, person_id)`

### N+1 risks driven by misclassification

- **`TENANT_SCOPED` misclassified as `CROSS_TENANT`** → every list query degrades to "fetch all, filter in-app." Catastrophic at 100K rows. Catalog enforces classification up front.
- **`CROSS_TENANT` misclassified as `TENANT_SCOPED`** → `@TenantId` doesn't affect FK fetches by ID (Hibernate loads by PK), so cross-club crew loads still work — but `personRepository.findAll()` would silently return zero for cross-club Persons. Catalog flags Person as `CROSS_TENANT` + documents the trap.

### Cartesian / explosion risks

- Hibernate `@TenantId` appends `WHERE owner.club_id = ?` once per query against the owning entity. JOIN-fetch chains don't multiply the predicate. Safe.
- **Native SQL queries skip the filter entirely.** Catalog flags `tenancy_enforcement: hibernate_only` per entity. Native escape-hatch register catches the rest.

### Caching strategy

- Tenant-scoped entities → cache key includes `club_id`. Cross-tenant → global key. Catalog drives the namespace decision downstream.
- No caching introduced by S-011 itself.

### Latency budget

- Catalog artifact: N/A (build-time document).
- Downstream derived budget the catalog enforces: **p95 < 100ms for tenant-scoped list queries at 100K rows.** Sub-budget of S-108's production baseline. Anchor for S-013 acceptance.

### Memory / storage

- `@TenantId` column overhead: ~60 entities × ~50% tenant-scoped × 8 bytes (Long) × 100K rows ≈ 12 MB schema-wide. Negligible.
- Composite indexes: ~10-30 MB each × ~30 indexes ≈ 300-900 MB. Acceptable.
- **Recommended `@TenantId` column type: `Long`.** Smaller index entries (8 vs 16 bytes for UUID), clean PK semantics, matches legacy `ClubId`. Surface as a decision for S-022; do not block. (See Open design questions.)

### Performance test plan

S-011 produces a document; the runtime tests land in S-013 / S-022. Surface these for downstream:

- **Composite-index ordering verifier:** `EXPLAIN ANALYZE` on top-5 list-page queries asserts an index scan on `(club_id, …)`, not seq scan.
- **Tenant-filter elision test:** same query in tenant A vs B vs unscoped; assert plan emits `club_id = ?` exactly once + result counts differ correctly.
- **Cross-tenant FK fetch behavior:** load a `Flight` whose Pilot Person is from another club; assert the Person loads (sacred-cow regression guard).
- **Native-query audit:** grep for `@Query(nativeQuery = true)` against tenant-scoped tables; assert each contains an explicit `club_id` predicate or is in the escape-hatch register.

## Open design questions

These specialists' analyses surfaced operator-decision points; the skill does not silently resolve them.

1. **Catalog generation model (architect's preferred vs. AC's literal wording).** AC1 says "a reference doc `next/database/tenant-catalog.md`" with two columns. The architect / security / qa specialists converge on hybrid: slim MD narrative + committed YAML overrides + extractor-emitted JSON. This is a deliberate reshape away from AC1's literal wording (matches the S-010 precedent of "re-runnable tooling over frozen MD"). **Operator decision:** confirm reshape, or hold AC1 literal?

2. **Tenant-id column type.** Performance recommends `Long` (8-byte index entries, clean PK semantics, matches legacy `bigint ClubId`). ADR 0008 left it open. **Operator decision:** lock `Long` here (catalog states the choice once for citation consistency), `UUID` (fresh-start opportunity), or defer to S-022? Affects every entity row in the catalog.

3. **Catalog drift control mechanism.** Security-engineer requires it; without it, the catalog goes stale the moment S-022 adds the first `@TenantId`. Options: (a) build-time check that walks `@Entity` classes via reflection and asserts catalog entries match (S-022 owns implementation); (b) CI grep over `@Entity` source + catalog YAML (cheaper, less precise); (c) accept "review eyes" as the only check. **Operator decision:** commit (now, in scope of S-011's contract) to mechanism (a) or (b) for S-022 to implement, or accept (c)?

4. **Catalog target naming — legacy or next-system or both?** S-022 lands `@TenantId` on next-system Java entities with names like `Flight` (not `Flights`), `PersonClub` (kept). S-024 iterates classifications. Should the catalog use legacy table names (`Flights`), next-system entity names (`Flight`), or carry both (`legacy_table` + `target_entity` columns)? Architect chose both; surface for explicit operator confirmation since it doubles the rationale-writing effort.

5. **Public-flow allowlist table name.** Architect's `public_flow_club` vs. security's `club_public_slug`. Functionally identical. Minor; pick at implement time. Surfaced for completeness.

<!-- modernize-refine: end -->

## Assumptions made (implement-time, 2026-05-15)

Operator decisions on the refinement's 5 open design questions:

1. **Catalog shape: hybrid** (Q1) — slim MD narrative + committed `tenant-rules.yaml` overrides + extractor-emitted `tenant-classification.json` (ephemeral, gitignored). Architect's recommended reshape away from AC1's literal 2-column MD. Matches S-010's "re-runnable tooling > frozen MD" precedent.
2. **Drift-control: build-time `@Entity` reflection check** (Q3) — S-011 declares the contract (machine-readable JSON shape + closed-vocabulary bucket values); S-022 implements the reflection check that asserts every `@Entity` has a catalog entry and vice versa. Failures are deterministic + actionable.
3. **Catalog naming: dual columns** (Q4) — every catalog row carries both `legacy_table` (matches S-010 extractor output) and `target_entity` (next-system Java entity name). S-016 (migration) consumes `legacy_table`; S-022 (annotations) consumes `target_entity`.
4. **Tenant-id column type: `Long`** (Q2) — performance-engineer's recommendation (8-byte index entries, clean PK semantics, matches legacy `bigint ClubId`). Pinned here as the catalog's documented choice; S-022 implements. Operator can override at S-022 implement time if a UUID-driven cross-instance argument emerges.
5. **Public-flow allowlist table name: `public_flow_club`** (Q5) — architect's preferred name (matches the `<feature>_<entity>` convention used by the modernized schema). Functionally identical to security's `club_public_slug` alternative.

