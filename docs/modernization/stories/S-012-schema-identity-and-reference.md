---
id: S-012
title: V1__baseline part 1 — identity + reference data
epic: E-02
status: done
started_at: 2026-05-16
done_at: 2026-05-16
depends_on: [S-009, S-010, S-011]
acceptance:
  - Tables defined: `club`, `club_extension`, `club_state`, `user`, `role`, `user_role`, `person`, `person_club`, `country`, `language`, `member_state`, `person_category`, `length_unit_type`, `elevation_unit_type`, `counter_unit_type`, `start_type`, `email_template`, `extension_type`, `extension_value` (19 tables).
  - **Every PK is `UUID NOT NULL PRIMARY KEY`** (Postgres native `uuid`, 16 bytes) per ADR 0019; every FK column is `uuid`. Tenant discriminator `club_id` becomes `uuid` references `club(id)`. No `DEFAULT gen_random_uuid()` — application generates via Hibernate 7 + `f4b6a3:uuid-creator` `UuidCreator.getTimeOrderedEpoch()` (wired in S-022).
  - **Aggregate-root column comments** on `club.id`, `person.id`, `user.id` reference ADR 0019 + the prefix scheme (`clb_<crockford-base32>`, `psn_...`, `usr_...`). Internal-entity PKs (`person_club.id`, `user_role.id`, `club_extension.id`, `email_template.id`, `extension_value.id`) carry no prefix comment (raw UUID at every layer).
  - PK/FK constraints, NOT NULL where required, indexes on FKs and hot filter columns (full grid in design notes).
  - `person_club` reshapes from legacy composite `(PersonId, ClubId)` PK to surrogate `id uuid PRIMARY KEY` + `UNIQUE (person_id, club_id) WHERE deleted_on IS NULL`.
  - `user.keycloak_sub uuid` nullable column reserved; partial `UNIQUE (keycloak_sub) WHERE keycloak_sub IS NOT NULL`. Backfill + NOT NULL flip deferred to S-052.
  - `member_state` + `person_category` reclassified from S-011's `reference` to **TENANT_SCOPED** (legacy carries `ClubId NOT NULL`); become internal entities of the `Club` aggregate per ADR 0018. `tenant-rules.yaml` flips both.
  - `email_template` + `extension_value` carry nullable `club_id`: `IS NULL` rows are SYSTEM_GLOBAL defaults (not aggregate-internal); `IS NOT NULL` rows are internal to the referenced `Club` aggregate. Partial unique indexes cover.
  - `tenant-rules.yaml` updates: `tenant_id_type: Long` → `UUID`; `hibernate_pin: 6.x` → `7.x`; `pii_columns` arrays added on `person`, `user`, `person_club`; `MemberStates` + `PersonCategories` classification flipped.
  - Reference-data seeds use **fixed canonical UUID v7 literals** (generated once via committed script, embedded in the migration); same UUIDs across every installation for forensic traceability.
  - Flyway migration succeeds against a fresh Postgres in Testcontainers; `IdentityBaselineIntegrationTest` asserts table list, type-shape per column, FK rules, partial-unique indexes, seed UUIDs, aggregate-root column comments. `TenantCatalogConsistencyTest` asserts catalog/schema alignment.
estimate: M
adr_refs: [0001, 0002, 0003, 0007, 0008, 0018, 0019]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_speculative: false
refined_speculative_at: 2026-05-16
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 24
github_pr: 25
reviewed: true
reviewed_at: 2026-05-16
review_outcome: improvements-only
review_blockers: 0
review_improvements: 10
review_nudges: 5
review_parity_oracle: N/A — schema reshape per ADR 0008 + 0018 + 0019; S-016 owns the legacy-MSSQL → new-Postgres cutover parity oracle
review_pass: 2
reworked: true
reworked_at: 2026-05-16
rework_mode: bold
rework_address_now: 11
rework_deferred: 0
rework_accepted: 9
rework_auto_decisions: 17
rework_followups: []
---

## Context
First chunk of V1__baseline. Identity (User/Person/PersonClub/Club triad) is sacred-cow shape — see seed and ADR 0008.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define `club` and `club_state`.
- [ ] Define `user` with `club_id` FK (a User is scoped to exactly one club).
- [ ] Define `person` *without* `club_id` (cross-tenant; see S-011).
- [ ] Define `person_club` many-to-many with `member_number`, `member_state_id`, role flags (`is_pilot`, `is_instructor`, `is_trainee`, `is_pax`), notification prefs.
- [ ] Define reference tables (`country`, `language`, `member_state`, `person_category`, unit types, `start_type`).
- [ ] Define `email_template` (per-club template overrides — likely tenant-scoped).
- [ ] Define `extension_type` + `extension_value` for `club_extension`.

## Notes
The `User` ↔ `Person` distinction is sacred. Resist any urge to collapse them. See current-state §5 and seed.

The Keycloak user ID also needs a home — likely a `user.keycloak_sub` column (UUID) for the OIDC subject claim mapping. Decided in S-052 but reserve the column now.

**Mid-implementation carry-alongs (rode this PR, not part of the identity baseline scope):**
- **`modernize/aircraft-cross-tenant-amendment`** carry-forward: 2 commits that never reached `main` via their own PR (`65b3b13` CI paths-filter fix + `f474527` S-014 JIT refine). Operator chose to bundle into S-012's PR rather than spin a separate one.
- **ADR 0020** (categorical column shape) — drafted mid-story after operator interrupt; retroactively reshaped `start_type.is_for_glider/tow/motor` boolean trio to `applicable_categories TEXT[]`.
- **`next/ops/dev-up-full.sh`** + `docker-compose.yml` `next` profile + custom pgAdmin image — operator-convenience for inspecting the migrated DB via pgAdmin alongside the legacy MSSQL stack.
- **`e2e/playwright.config.ts` `maxFailures: 10`** — added after a CI Playwright flake during the status:done push burned 20+ runner-minutes. Cap fails-fast on mass regressions; local runs override with `--max-failures=0`.
- **Drop H2 testcontainer fallback** — every `@SpringBootTest` now boots against `SharedPostgresContainer`; `@EnabledIf("ch.fls.server.testsupport.SharedPostgresContainer#available")` with CI fail-loud guard.
- **ADR 0021** (integration-test data isolation strategy) — pinned before S-022 lands JPA + INSERTs.

<!-- modernize-refine: start -->

## Design notes

### Migration shape

Ships as **`V<n>__identity_and_reference.sql`** — implementer reads `next/server/src/main/resources/db/migration/` first to pick the next free integer. S-009 ships V1 (`app_meta`); S-018 ships V2 (`shedlock`); S-012 likely lands as V3 (or V2 if S-018 hasn't merged — coordinate). Single migration with 19 tables + reference-data seed INSERTs (with **fixed canonical UUID v7 literals**), ordered by FK dependency. Reference data first (`*_unit_type`, `country`, `language`, `start_type`, `club_state`, `extension_type`, `role`) → `club` → `member_state` + `person_category` (per-club; seeded at S-016 cutover, NOT in this migration) → `person` → `user` → `user_role` → `person_club` → `club_extension` → `email_template` → `extension_value`.

Migration header documents: (a) UUID v7 PK convention + app-side generation; (b) reference to ADR 0019 prefix scheme; (c) tenant column flip to UUID; (d) no `DEFAULT gen_random_uuid()` — application generates; (e) the `person_club` surrogate-PK reshape from legacy composite.

### ID strategy (per ADR 0019)

**Pure UUID v7 everywhere.** Every PK is `uuid NOT NULL PRIMARY KEY` (Postgres native, 16 bytes). FK columns are `uuid`. The tenant discriminator `club_id` is `uuid REFERENCES club(id)`. Audit columns `created_by_user_id uuid` / `modified_by_user_id uuid` carry no FK constraint (chicken-and-egg at first-user bootstrap, preserved from prior refinement).

**Application-generated** — no `DEFAULT gen_random_uuid()` on any PK column. Forward Java wiring (lands at S-022):

```java
// next/server/src/main/java/ch/fls/domain/common/FlsUuidV7Generator.java (S-022)
public final class FlsUuidV7Generator implements BeforeExecutionGenerator {
    @Override public Object generate(SharedSessionContractImplementor s, Object owner, Object cur, EventType e) {
        return UuidCreator.getTimeOrderedEpoch();  // com.github.f4b6a3:uuid-creator
    }
    @Override public EnumSet<EventType> getEventTypes() { return EnumSet.of(EventType.INSERT); }
}

@IdGeneratorType(FlsUuidV7Generator.class)
@Retention(RUNTIME) @Target({FIELD, METHOD})
public @interface UuidV7 {}
```

**Hibernate gotcha** (Context7-confirmed): Hibernate 7's built-in `@UuidGenerator(style = TIME)` produces UUID **v1** (RFC 4122, MAC + clock), NOT v7. For ADR 0019's v7 contract, we wire `f4b6a3:uuid-creator` via the custom `BeforeExecutionGenerator` above. `UuidCreator.getTimeOrderedEpoch()` (default) is ~30ns/ID; `getTimeOrderedEpochPlus1()` is ~3ns/ID (monotonic +1 within same ms; 20× faster). Recommend default — FLS write volume (< 1000 IDs/sec peak) makes the delta unmeasurable.

### Aggregate composition (per ADR 0018)

| Layer | Tables in S-012 scope | Notes |
|---|---|---|
| **Aggregate roots** (3) | `club`, `person`, `user` | UUID v7 PK; prefix scheme `clb`/`psn`/`usr` at JSON/URL/log boundary; SQL column comment forward-references ADR 0019. |
| **Internal entities under `Person`** | `person_club` | Loaded as `Person.personClubs` collection; lifecycle bound to Person (CASCADE on Person delete). Cross-aggregate FK `person_club.club_id → club.id`. |
| **Internal entities under `Club`** | `club_extension`, `email_template` (where `club_id IS NOT NULL`), `extension_value` (where `club_id IS NOT NULL`), **`member_state` + `person_category`** (per Open Q2 resolution: keep per-club + reclassify TENANT_SCOPED) | Loaded as collections on Club aggregate root; CASCADE on Club delete. |
| **Internal entities under `User`** | `user_role` | Loaded as `User.roles`; CASCADE on User delete. |
| **System-global lookup / reference data — not aggregates** | `role`, `country`, `language`, `start_type`, `club_state`, `extension_type`, `length_unit_type`, `elevation_unit_type`, `counter_unit_type` + system-default `email_template`/`extension_value` rows (where `club_id IS NULL`) | Plain JPA entities (anemic acceptable per ADR 0018 escape hatch). Hardcoded canonical UUID v7 in seeds. |

Cross-tenant references (Hibernate `@TenantId` filters table-level queries, NOT FK-by-ID loads — sacred-cow preserved):
- `user.club_id → club.id` (PRINCIPAL_SUBJECT home club, NOT @TenantId).
- `user.person_id → person.id` (cross-tenant: Person has no `club_id`).
- `person_club.club_id → club.id` (records cross-tenant membership).
- `person_club.person_id → person.id` (sacred-cow cross-tenant FK from Flight crew via PersonClub).

### Per-table column inventory (PK + FK type re-pin from legacy entity files)

Column lengths from `flsserver/src/FLS.Server.Data/DbEntities/Person.cs`, `User.cs`, `Club.cs`, `PersonClub.cs` — re-pinned:

| Table.Column | Type | Source |
|---|---|---|
| `person.id` | `UUID NOT NULL PRIMARY KEY` | `Person.cs:29` |
| `person.firstname/lastname` | `VARCHAR(100) NOT NULL` | `Person.cs:32,36` |
| `person.midname/company_name/city/region` | `VARCHAR(100)` | `Person.cs:39-58` |
| `person.address_line1/2` | `VARCHAR(200)` | |
| `person.zip` | `VARCHAR(10)` | |
| `person.country_id` | `UUID NULL → country.id` | `Person.cs:60` (legacy `Guid?`) |
| `person.private_phone/mobile_phone/business_phone/fax_number` | `VARCHAR(30)` | `Person.cs:62-71` |
| `person.email_private/business` | `VARCHAR(256)` | `Person.cs:74,77` (legacy verbatim 256, NOT 254) |
| `person.birthday` | `DATE` | `Person.cs:82-83` + `CHECK (birthday IS NULL OR birthday <= CURRENT_DATE)` |
| `person.licence_number` | `VARCHAR(20)` | `Person.cs:105` |
| `person.spot_link` | `VARCHAR(250)` | `Person.cs:132` |
| `person.medical_class1_expire_date/class2/lapl` | `DATE` | `Person.cs:108-124` |
| `user.id` | `UUID NOT NULL PRIMARY KEY` | `User.cs:23` |
| `user.club_id` | `UUID NOT NULL → club.id ON DELETE RESTRICT` | `User.cs:25` (legacy `Guid` — supports UUID flip) |
| `user.username` | `VARCHAR(256) NOT NULL` + `LOWER(username)` functional UNIQUE | `User.cs:28-30` |
| `user.friendly_name` | `VARCHAR(100) NOT NULL` | |
| `user.person_id` | `UUID NULL → person.id ON DELETE SET NULL` | `User.cs:38` |
| `user.notification_email` | `VARCHAR(256) NOT NULL` | |
| `user.phone_number` | `VARCHAR(30)` | |
| `user.remarks` | `VARCHAR(250)` | |
| `user.language_id` | `UUID NOT NULL → language.id` | `User.cs:76` (legacy `int`; remapped at S-016) |
| `user.keycloak_sub` | `uuid NULL` + `UNIQUE WHERE NOT NULL` partial | reserved; backfilled at S-052 |
| `user.account_state_id` | `SMALLINT NOT NULL` | matches legacy `User.cs:69`; FK target table out of S-012 scope (defer) |
| `club.id` | `UUID NOT NULL PRIMARY KEY` | `Club.cs:36` |
| `club.clubname` | `VARCHAR(100) NOT NULL` | |
| `club.club_key` | `VARCHAR(10) NOT NULL UNIQUE` | URL slug |
| `club.country_id` | `UUID NOT NULL → country.id` | `Club.cs:55` (NOT NULL in legacy) |
| `club.club_state_id` | `UUID NOT NULL → club_state.id` | `Club.cs:114` (legacy `int`; remapped) |
| `club.email` | `VARCHAR(256)` | |
| `club.phone/fax_number` | `VARCHAR(30)` | |
| `club.send_*_to / reply_to_email_address` | `VARCHAR(250)` | `Club.cs:83-99` |
| `person_club.id` | `UUID NOT NULL PRIMARY KEY` | **new surrogate** (legacy uses composite — `PersonClub.cs:20-26`) |
| `person_club.person_id` | `UUID NOT NULL → person.id ON DELETE CASCADE` | |
| `person_club.club_id` | `UUID NOT NULL → club.id ON DELETE RESTRICT` | |
| `person_club.member_number` | `VARCHAR(20)` | `PersonClub.cs:29` |
| `person_club.member_state_id` | `UUID NULL → member_state.id` | |
| `person_club.is_motor_pilot / is_tow_pilot / is_glider_instructor / is_glider_pilot / is_glider_trainee / is_passenger / is_winch_operator / is_motor_instructor` | `BOOLEAN NOT NULL DEFAULT false` | `PersonClub.cs:33-47` |
| `person_club.receive_flight_reports / receive_aircraft_reservation_notifications / receive_planning_day_role_reminder / is_active` | `BOOLEAN NOT NULL DEFAULT false` | `PersonClub.cs:49-55` |
| `email_template.club_id` | `UUID NULL → club.id ON DELETE CASCADE` | nullable = system default |
| `email_template.template_code` | `VARCHAR(64) NOT NULL` | |
| `email_template.subject` | `VARCHAR(256) NOT NULL` | |
| `email_template.html_body / text_body` | `TEXT` | unbounded |
| `extension_value.club_id` | `UUID NULL → club.id ON DELETE CASCADE` | nullable = system default |
| `extension_value.extension_type_id` | `UUID NOT NULL → extension_type.id` | |
| `extension_value.value` | `TEXT` | |
| `role.code` | `VARCHAR(32) NOT NULL UNIQUE` | seeded ADMIN/FLIGHT_OPS/INSTRUCTOR/PILOT/READER |
| `country.iso2_code / iso3_code` | `CHAR(2) / CHAR(3) NOT NULL UNIQUE` + upper-case CHECK | |
| `language.code` | `VARCHAR(10) NOT NULL UNIQUE` + BCP-47 CHECK | |

Audit columns on every TENANT_SCOPED + CROSS_TENANT mutable table:
- `created_on TIMESTAMPTZ NOT NULL DEFAULT now()`
- `created_by_user_id UUID` (no FK; chicken-and-egg pattern; SQL comment "service-layer only — never bind from request payload")
- `modified_on TIMESTAMPTZ NOT NULL DEFAULT now()`
- `modified_by_user_id UUID` (same shape)

Reference tables skip audit columns (operator-only via migration).

### SQL column comments for forensic clarity

```sql
COMMENT ON COLUMN person.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: psn_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN club.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: clb_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN "user".id IS
  'UUID v7. Aggregate root (ADR 0018). External form: usr_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN "user".club_id IS
  'Principal-subject home club. NOT a @TenantId discriminator — do not add @TenantId on the User entity.';
COMMENT ON COLUMN person.created_by_user_id IS
  'No FK constraint by design (chicken-and-egg at first-user bootstrap). Service layer populates; never bind from request payload.';
```

### Indexes per table (per-table grid)

(Composite ordering preserved from prior refinement; column types now `uuid`.)

- `club`: `pk(id)`, `ux_club_key(club_key)`, `ix_club_state_id(club_state_id)`, `ix_club_country(address_country_id)`.
- `user`: `pk(id)`, `ux_user_username(LOWER(username))`, `ux_user_keycloak_sub(keycloak_sub) WHERE keycloak_sub IS NOT NULL`, `ux_user_email(email)`, `ix_user_club(club_id)`, `ix_user_person(person_id) WHERE person_id IS NOT NULL`.
- `person`: `pk(id)`, `ix_person_name(lastname, firstname)`, `ix_person_email_priv(LOWER(email_private)) WHERE email_private IS NOT NULL`.
- `person_club`: `pk(id)`, `ux_person_club(person_id, club_id) WHERE deleted_on IS NULL`, `ix_person_club_club_person(club_id, person_id) INCLUDE (member_state_id, is_pilot, is_instructor)` (index-only directory render), `ix_person_club_member(club_id, member_number) WHERE member_number IS NOT NULL`.
- `email_template`: `pk(id)`, `ux_email_template_club_code(club_id, template_code)`, partial `ux_email_template_default(template_code) WHERE club_id IS NULL`.
- `extension_value`: `pk(id)`, `ux_extension_value_club_type(club_id, extension_type_id)`.
- `role`, `user_role`: `pk(id)`, `ux_role_code(code)`, `ix_user_role_role(role_id)`.
- Reference tables: PK only (small + cold). `ux_country_iso2(iso2_code)`, `ux_country_iso3(iso3_code)`, `ux_language_code(code)`.

### FK cascade rules (full grid)

| FK | On delete | Rationale |
|---|---|---|
| `club_extension.club_id → club.id` | CASCADE | Club-internal lifecycle |
| `email_template.club_id → club.id` | CASCADE | Same |
| `extension_value.club_id → club.id` | CASCADE | Same |
| `member_state.club_id → club.id` | CASCADE | Per-club seed; Club-internal |
| `person_category.club_id → club.id` | CASCADE | Same |
| `person_category.parent_person_category_id → person_category.id` | RESTRICT | Self-FK; preserve hierarchy |
| `user.club_id → club.id` | RESTRICT | Offboard users first |
| `user.person_id → person.id` | SET NULL | Cross-tenant ride-through |
| `person_club.club_id → club.id` | RESTRICT | Preserves Person cross-club state |
| `person_club.person_id → person.id` | CASCADE | Orphan join cleanup on Person erasure |
| `user_role.user_id → user.id` | CASCADE | User-internal |
| `user_role.role_id → role.id` | RESTRICT | |
| Reference FKs | RESTRICT (or SET NULL where nullable) | |

### Reference-data seeds — fixed canonical UUID v7 literals

Migration runs before any Java code; seed UUIDs must be **literals**. Approach:

1. At implement time, run a committed Java/groovy script (`next/server/src/test/resources/scripts/generate-canonical-uuids.java`) that calls `UuidCreator.getTimeOrderedEpoch()` once per seed row, captures the output.
2. Embed the UUIDs into the migration as `'01234567-89ab-...'::uuid` literals.
3. **Commit the generated UUIDs in source.** Each reference row's UUID is fixed across all installations forever — Switzerland's `country.id` is bit-identical on every cluster. Forensic traceability via grep.
4. S-016 cutover builds a `legacy_id_map (legacy_int_or_guid, new_uuid)` lookup mapping legacy IDs → these canonical seed UUIDs.

In-scope seeds:
- `country` (≥ 196 ISO-3166 rows).
- `language` (≥ 5: de, fr, it, en + extras).
- `start_type` (5: WinchLaunch, Aerotow, SelfStart, ExternalStart, Motor).
- `*_unit_type` (Meter / Feet / etc.; legacy snapshot).
- `club_state` (3: Active, Suspended, Closed).
- `extension_type` (per legacy snapshot).
- `role` (5: ADMIN, FLIGHT_OPS, INSTRUCTOR, PILOT, READER; S-026 finalizes).

**NOT seeded in this migration** (per-club seeds done at S-016 cutover):
- `member_state` (Active/Suspended/Cancelled/Deceased per club).
- `person_category` (per legacy snapshot per club).

### `tenant-rules.yaml` updates (in scope)

```yaml
# Line 7-8:
hibernate_pin: "7.x"            # was "6.x" — ADR 0001 + 0019
tenant_id_type: "UUID"          # was "Long" — ADR 0019

# Reclassify (legacy carries ClubId):
member_state: { kind: tenant-scoped, target_entity: MemberState, tenant_column: club_id }
person_category: { kind: tenant-scoped, target_entity: PersonCategory, tenant_column: club_id }

# Add PII column lists:
person:
  pii_columns: [firstname, lastname, midname, birthday, gender, address_line1, address_line2, zip, city, region, email_private, email_business, private_phone, mobile_phone, business_phone, fax_number, licence_number, spot_link]
user:
  pii_columns: [username, email, notification_email, phone_number, remarks]
person_club:
  pii_columns: [member_number]   # quasi-PII (combined with name reveals membership)
```

### Module layout

- `next/server/src/main/resources/db/migration/V<n>__identity_and_reference.sql` (new — single file, ~700-850 lines).
- `next/database/tenant-rules.yaml` (edit — `tenant_id_type` flip + classification flips + PII catalog).
- `next/server/src/test/java/ch/fls/server/migration/IdentityBaselineIntegrationTest.java` (new — table + constraint + seed + UUID-shape + comment assertions).
- `next/server/src/test/java/ch/fls/server/migration/TenantCatalogConsistencyTest.java` (new — drift-detection invariant).
- `next/server/src/test/resources/reference-seeds-canonical-uuids.json` (new — pinned UUID ground truth for test fixtures).
- `next/server/src/test/resources/scripts/generate-canonical-uuids.java` (new — committed script that produced the canonical UUIDs).
- Extend `MigrationFolderConventionsTest` (V<n> awareness) + `FlywayBootstrapIntegrationTest` (version floor + new history row check).

### Alternatives considered

- **Chosen — single V<n> migration with 19 tables + seeds + UUID v7 PKs + DDD aggregates + Person owns PersonClub.** Single transactional unit; FK ordering done once; Flyway baseline intact.
- Rejected — Hibernate built-in `@UuidGenerator(style = TIME)`: produces UUID v1, not v7 (Context7-confirmed).
- Rejected — Postgres `DEFAULT gen_random_uuid()`: produces v4 (random); breaks ADR 0019's time-ordering locality + app-generation contract.
- Rejected — BIGINT IDENTITY PKs (prior speculative refinement choice): per ADR 0019 operator chose UUID v7 for conceptual uniformity. ~300 MB additional 5-year index footprint in S-012 alone; inside the ~3-5 GB envelope.
- Rejected — `PersonClub` as own aggregate root: PersonClub lifecycle is intimately tied to Person; cross-aggregate-write coordination at every membership change defeats DDD.
- Rejected — composite PK on `person_club(person_id, club_id)` matching legacy: JPA composite-key handling is awkward; surrogate `id uuid` + `UNIQUE (person_id, club_id)` is the canonical reshape.
- Rejected — per-installation seed UUIDs via UUID v5 namespace derivation: deterministic but harder to grep in forensics; committed literals are clearer.
- Rejected — `member_state`/`person_category` as REFERENCE_DATA: legacy carries `ClubId NOT NULL`; per-club semantics preserved by reclassifying as TENANT_SCOPED.
- Rejected — `Spring Modulith` dependency in S-012: deferred to S-022 when JPA + entities + events land. S-012 is schema-only.

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — 19 tables with UUID PKs:**
- Every PK becomes `id uuid NOT NULL PRIMARY KEY`; no `DEFAULT gen_random_uuid()`.
- FK columns flip to `uuid`: `user.club_id`, `user.person_id`, `person_club.person_id/club_id/member_state_id`, `club_extension.club_id/extension_type_id`, `email_template.club_id`, `extension_value.club_id/extension_type_id`, `club.address_country_id/club_state_id`, `person.country_id`, `user.language_id`, `user_role.user_id/role_id`, `person_category.parent_person_category_id`.
- Reference-table PKs uniformly `uuid` (per ADR 0019 §Decision "every entity") — hardcoded literal seeds.
- Audit columns: `created_by_user_id uuid` / `modified_by_user_id uuid` (was BIGINT); no FK constraint.
- Tenant column on `club_extension`, `email_template`, `extension_value`, `member_state`, `person_category` is `club_id uuid REFERENCES club(id)`.

**AC2 — constraints + indexes survive type flip:**
- All UNIQUE/CHECK/NOT NULL orthogonal to PK type.
- Index size widens: composite PK btrees +50% entry size; `person_club(club_id, person_id) INCLUDE (member_state_id, is_pilot, is_instructor)` entries grow ~50B → ~70B; on 100K rows < 10 MB additional.
- B-tree locality preserved by UUID v7's time-ordered prefix (no `fillfactor` tuning needed).
- `LOWER(username)` functional unique index independent of PK type.
- `keycloak_sub uuid` partial UNIQUE `WHERE NOT NULL` — column rejects malformed input at the type level.

**AC3 — `club_id` tenant column:**
- `club_id uuid REFERENCES club(id)` — same shape, new type.
- SQL comment on `user.club_id` clarifying "home club, NOT @TenantId discriminator" preserved.
- `person` has NO `club_id` (sacred cow); pin in `person_has_no_club_id_column` test.
- **NEW per ADR 0019:** aggregate-root column comments on `club.id`, `person.id`, `user.id` reference the prefix scheme (`clb`/`psn`/`usr`). Internal entities (`person_club.id`, `user_role.id`, etc.) get NO prefix comment.

**AC4 — smoke test:**
- Assert column type is `uuid` (not `bigint`) for every PK + every FK via `INFORMATION_SCHEMA.columns.data_type = 'uuid'`. Parameterized over 19 tables.
- Reference-data seed assertions: lower-bound counts + canonical UUID pinning (Switzerland's `country.id` is the same UUID on every install).
- New invariant: `aggregate_root_column_comments_reference_adr_0019` — `pg_description` on `person.id`/`club.id`/`user.id` matches the prefix-scheme comment.

**DDD aggregate composition edge cases:**
- `PersonClub` aggregate home — **Person** (per Open Q1 resolution). Cross-club crew sacred cow loads `Person.findById(psn)` returning a Person with full multi-club PersonClub collection. Operator confirms; JPA boundary at S-022 honors.
- `person_club` legacy composite PK (`PersonId`, `ClubId`) → new surrogate `id uuid` + `UNIQUE (person_id, club_id)`. Migration header documents the reshape.
- `member_state` + `person_category` reclassified per Open Q2 resolution: per-club + TENANT_SCOPED + Club-aggregate-internal.
- `email_template` + `extension_value` with nullable `club_id`: `IS NULL` rows are SYSTEM_GLOBAL defaults (NOT in any Club aggregate); `IS NOT NULL` rows are Club-internal. Repository surface decided at S-022.
- Reference tables (`country`, `language`, etc.) are NOT aggregate roots — anemic JPA acceptable per ADR 0018 escape hatch.

**Hibernate v7 generation:**
- Hibernate 7's built-in `@UuidGenerator(style = TIME)` is **UUID v1**, not v7. ADR 0019 requires v7. Solution: wire `f4b6a3:uuid-creator` via custom `BeforeExecutionGenerator` at S-022.
- The migration MUST avoid `DEFAULT gen_random_uuid()` so the app-generation contract isn't accidentally bypassed.

### Hidden requirements

- **`next/database/tenant-rules.yaml` line 8 update:** `tenant_id_type: "Long"` → `"UUID"`; line 7: `hibernate_pin: "6.x"` → `"7.x"`. In-scope here.
- **No `gen_random_uuid()` DEFAULT on PK columns.** Extend `forbidden-migration-patterns.txt` to add this string OR document the convention in CONVENTIONS.md.
- **Aggregate-root column comments** on `club.id`, `person.id`, `user.id`. New convention from ADR 0019 §Aggregate-prefix scheme.
- **`keycloak_sub uuid UNIQUE` partial** — column is `uuid` (matches Keycloak's subject claim shape); backfill at S-052.
- **`person_club` surrogate `id uuid` PK** — legacy composite → surrogate + composite UNIQUE.
- **Reference-data canonical UUIDs committed in source** — generated once via committed script; reviewer can re-derive deterministically.
- **`member_state` + `person_category` per-club seeds** — NOT in V<n>__identity_and_reference.sql. S-016 cutover seeds them per club from legacy data.
- **Spring Modulith dependency** deferred to S-022 (no JPA / no events in S-012).
- **Strong-typed ID `record FlightId(UUID)` + Crockford base32 codec + Jackson module + Spring URL converters + MDC formatting** all per ADR 0019 — NOT in S-012; deferred to S-022 / a new "ID infrastructure" story TBD.
- **psql debugging-helper functions** (`clb(text) RETURNS uuid` etc.) — separate post-baseline migration per ADR 0019 follow-ups; NOT in S-012.
- **PII column lists in `tenant-rules.yaml`** — `person`, `user`, `person_club` enumerate `pii_columns`.

### Scope clarifications

**In:** 19 tables + indexes + FKs + CHECK constraints + reference-data seeds (canonical UUID literals) + `ALTER TABLE` not needed (all in single CREATE TABLE batch) + `tenant-rules.yaml` updates (`tenant_id_type`, `hibernate_pin`, `MemberStates`/`PersonCategories` flip, PII catalog) + `IdentityBaselineIntegrationTest` + `TenantCatalogConsistencyTest` + 3 SQL `COMMENT ON COLUMN` clauses on aggregate roots + `user.club_id` "principal-subject" comment + `person_club` surrogate-PK reshape (migration header explains).

**Out:** JPA entities + `@UuidV7` annotation + `FlsUuidV7Generator` wiring (S-022); `@TenantId` filter wiring (S-022); typed-ID value-object records + Crockford codec + Jackson/Spring/MDC infrastructure (S-022 or new "ID infrastructure" story); psql `clb(text)/psn(text)/usr(text)` debug helpers (separate post-baseline migration); Spring Modulith dependency (S-022 or earlier infra story); `keycloak_sub` backfill + NOT NULL (S-052); `member_state` + `person_category` per-club seeds (S-016 cutover); `flight_type` / `homebase_id` FK columns on `club` (S-013 ALTER TABLE); audit log table + AOP advice (S-027); public-flow `public_flow_club` table (S-025).

### Things not the right shape

- AC1 title says "V1__baseline part 1" — V1 is locked by S-009 (`app_meta`). Ships as V<n> (likely V3 after S-018 V2).
- Prior refinement's Open Q1 "BIGINT vs UUID" is now resolved by ADR 0019 — restated as a fact, not a question.
- Prior refinement's "configuration choices" referred to BIGINT — flipped to UUID throughout.
- `member_state` + `person_category` previously listed as REFERENCE / CONFLICTED — now formally TENANT_SCOPED + Club-aggregate-internal.
- `user.account_state_id` references a `user_account_state` lookup that doesn't exist in S-012's 19-table scope — kept as `SMALLINT NOT NULL` matching legacy; FK added later when the lookup table lands.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | `person` PII spill via unscoped cross-tenant JOIN | High | Column-level RBAC at service layer (S-022/S-026); Person aggregate-method boundary narrows mutation surface per ADR 0018; S-024 leakage CI |
| (b) | `user.keycloak_sub` collision / spoofing | High | `uuid UNIQUE WHERE NOT NULL` partial index; type itself rejects malformed input |
| (c) | `email_template.body` template injection | Med | Schema can't prevent; S-082 sender escapes/sandboxes; column comment flags |
| (d) | `extension_value` tenant tampering | Med | `club_id uuid NOT NULL` + `@TenantId` at S-022 |
| (e) | FADP right-to-erasure cascade complexity | Med | `person → person_club` CASCADE; audit-blob redaction operates on column-name list, not on UUID-string-shape detection |
| (f) | Forgotten `@TenantId` on tenant-scoped table | Med | `TenantCatalogConsistencyTest` + S-024 leakage CI |
| (g) | Reference-data seed tampering | Low | CODEOWNERS on `db/migration/**`; Flyway checksum detects post-deploy mutation |
| (h) | **UUID v7 timestamp leak in error messages** (new per ADR 0019) | Low | UUID v7's leading 48 bits expose record creation time. For FLS Person/User this is creation ≈ pilot-join date — low sensitivity. Column comment documents; CONVENTIONS.md captures rule "IDs in error messages reveal creation time; assume errors reach only the authorized owner." |
| (i) | **Aggregate-prefix reveals entity type at boundary** (new per ADR 0019) | Very low | `psn_...` vs `clb_...` makes entity type readable from any leaked ID. By design (humans + audit-log search disambiguate types at a glance). No mitigation required. |
| (j) | **Aggregate boundary as authz surface** (positive change per ADR 0018) | N/A | Per-aggregate repositories + aggregate methods replace direct field-setter access. S-026 places `@PreAuthorize` at aggregate-method entry points; mutation surface narrowed structurally. |

### Authorization

- **DB-role split** (unchanged): `migrator` (DDL + reference-data INSERT), `app_runtime` (DML on tenant-scoped + cross-tenant tables; SELECT-only on reference tables; column-restricted SELECT on `person`/`user` PII columns).
- **App-layer authz surface (new per ADR 0018):** `@PreAuthorize` at aggregate-method entry points (e.g., `Person.recordContactUpdate()`, `Club.changeState()`) and repository `save(...)` boundaries — not at controller methods directly. Per-aggregate repositories expose only `findById(UUID)` / `save(root)` / `delete(root)`.
- **Reference-data role-check policy:** SELECT-only on `country`, `language`, `start_type`, `*_unit_type`, `club_state`, `role`, `extension_type`. Mutation reserved to `migrator` (operator-only via Flyway). S-026 must NOT expose mutation endpoints on these.

### Input validation (schema-level)

- `person.email_private/business`: `CHECK (col IS NULL OR col LIKE '%_@_%._%')` — cheap sanity, not RFC 5322.
- `person.firstname/lastname`: NOT NULL, no min length.
- `person.birthday`: `CHECK (birthday IS NULL OR birthday <= CURRENT_DATE)`.
- `country.iso2_code/iso3_code`: length-pinned + `CHECK (upper(iso2_code) = iso2_code)`.
- `language.code`: BCP-47 — `CHECK (code ~ '^[a-z]{2,3}(-[A-Z]{2})?$')`.
- `person_club.member_number`: partial `UNIQUE (club_id, member_number) WHERE member_number IS NOT NULL`.
- `person_club` role flags: `NOT NULL DEFAULT false`.
- **UUID columns (new per ADR 0019):** Postgres `uuid` type rejects malformed input at INSERT; no extra CHECK needed. No `CHECK (id <> '00000000-...-000000000000')` — nil UUIDs are valid but never generated.
- **Aggregate-prefix validation:** boundary concern (Spring `Converter<String, FlightId>` at S-022); DB never sees `psn_...` strings.

### PII handling

- **Direct-identifier PII on `person`** (per `tenant-rules.yaml` extension): `firstname`, `lastname`, `birthday`, `gender`, `address_*`, `email_private/business`, `phone_*`, `licence_number`, `spot_link`. Logged-redacted; HMAC-hash for correlation if needed.
- **Quasi-PII on `person_club`:** `member_number` (combined with name reveals membership). Listed in `pii_columns`.
- **Auth artifacts on `user`:** `username`, `email`, `notification_email`, `phone_number`, `remarks`. `keycloak_sub` redacted to hashes in audit `before/after`.
- **UUID PKs are NOT PII themselves:** `person.id`, `user.id` carry no personal data. Audit-blob redaction operates on column NAMES (e.g., `firstname`), not on UUID-shape detection.
- **UUID v7 creation-time exposure** (cross-cutting per threat (h)): document in `next/server/CONVENTIONS.md` — IDs in error messages reveal record creation time; assume errors reach only the authorized owner.
- **DSAR scope:** Person erasure cascades to `person_club` across ALL clubs (cross-tenant by design); audit-blob redaction uses the prefixed `psn_<uuid>` form as search key.
- **PII catalog in `tenant-rules.yaml`** — every PII column above enumerated under `pii_columns`. Build-time check (`TenantCatalogConsistencyTest`) fails on unlisted PII.

### Audit-log events (forward to S-027)

- S-027 owns the `audit_event` table + AOP advice. S-012 contributes `created_by_user_id uuid` / `modified_by_user_id uuid` columns (no FK).
- Audit `target.id` field carries the **prefixed external form** (`psn_0jwq...`) for forensic readability + grep-ability. Raw UUID stored in column; prefix added at serialization.
- Reference-data mutations audited at action layer (admin UI), not row layer.
- Audit `before/after` JSON snapshots of `person` rows MUST apply the `pii_columns` redaction list before serialization.

### Cross-tenant leakage

- `person` + `person_club`: NO `club_id` on Person; PersonClub records cross-tenant membership. S-024 asserts no `@TenantId` annotation on Person aggregate root nor PersonClub.
- **Aggregate boundary (new per ADR 0018):** PersonClub is internal to Person aggregate; not directly addressable via own repository. Mutation goes through `Person.addMembership(club, role)` / `Person.removeMembership(club)`. Cross-tenant access still works (sacred cow preserved).
- `user`: PRINCIPAL_SUBJECT — carries `club_id` (home) but NOT `@TenantId`-filtered.
- TENANT_SCOPED entities (`club`, `club_extension`, `email_template` non-default rows, `extension_value` non-default rows, `member_state`, `person_category`): S-022's `@TenantId` filters JPA queries by `club_id` (now UUID). System-admin cross-club views use `UnscopedTenantContext.runAs` (S-023).
- **Tenant ID type change (new per ADR 0019):** `tenant_id_type: Long → UUID` in `tenant-rules.yaml`. S-022 resolver consumes a UUID claim from Keycloak (`fls_club_id`) instead of Long.

### OWASP applicability

- **A01 Broken Access Control:** applies. Aggregate-root boundary narrows where `@PreAuthorize` lives.
- **A02 Cryptographic Failures:** N/A — `user` has no password/token columns (Keycloak owns). Migration header MUST flag "future PR adding a password column is wrong."
- **A03 Injection:** applies — seed INSERTs use literal values; CODEOWNERS gate.
- **A04 Insecure Design:** applies — `@TenantId` via correct UUID column shape; aggregate-root boundary structural.
- **A05 Security Misconfiguration:** `@TenantId` resolver fail-closed contract (S-022); migrator-vs-app_runtime role split.
- **A07 Identification & Authentication Failures:** `user.keycloak_sub uuid UNIQUE` is the linchpin.
- **A08 Software & Data Integrity:** Flyway checksum + CODEOWNERS protects reference data.

### Story-specific concerns

- **`tenant-rules.yaml` `tenant_id_type` flip** is in scope.
- **PII catalog cross-update** in `tenant-rules.yaml` is in scope.
- **Reference-seed allowlist** in `forbidden-migration-patterns.txt` — extend.
- **CODEOWNERS** on `db/migration/**` (covered by S-009).
- **`audit_event` table NOT in scope** — S-027.
- **`user.club_id` SQL column comment** — "home club, NOT @TenantId discriminator" to prevent S-022 implementer mistake.
- **`person` has no `club_id` by design** — SQL comment.
- **UUID v7 timestamp visibility** — cross-cutting `next/server/CONVENTIONS.md` note (out of strict S-012 scope but flagged).
- **Aggregate-prefix column comments** in scope — `person.id`, `user.id`, `club.id`.
- **Reference-data seeded UUIDs are fixed-canonical** — same UUID across every install (by design); knowing "Switzerland has UUID xyz" is publishable, not a secret.
- **`person_club` is internal to Person aggregate** — no own repository at S-022; no aggregate prefix.

## Test plan

### Coverage contract

**Owns:** 19 tables + indexes + FKs + CHECK constraints + reference-data seeds (canonical UUIDs) + `tenant-rules.yaml` updates + aggregate-root column comments + `IdentityBaselineIntegrationTest` + `TenantCatalogConsistencyTest`.

**Does NOT own:** JPA entities + `@UuidV7` + `FlsUuidV7Generator` (S-022); `@TenantId` filter behavior (S-022); aggregate-method invariant enforcement (S-022/S-051); aggregate prefix codec (S-022); cross-aggregate reference-as-ID rule (S-022/S-051); live leakage CI (S-024); audit-log capture (S-027); DSAR cross-club cascade behavior (S-051); production-scale perf (S-108); `keycloak_sub NOT NULL` (S-052); `flight_type` / `homebase_id` FKs on club (S-013).

### Specific test cases

**Extensions to `MigrationFolderConventionsTest`:**
- `identity_and_reference_migration_present` — exactly one `V<n>__identity_and_reference.sql`, n ≥ 2.
- `vN_identity_baseline_is_non_empty`.

**Extensions to `FlywayBootstrapIntegrationTest`:**
- `current_version_at_least_3_after_s012` — `flyway.info().current().getVersion() >= MigrationVersion.fromVersion("3")` (relaxed to ≥, tolerates S-018 ordering).
- `flyway_history_contains_identity_row`.

**New `IdentityBaselineIntegrationTest`** (`@SpringBootTest` + shared `PostgresTestContainerLifecycle`):
- `all_19_tables_present` — `containsExactlyInAnyOrder` against the 19 + framework tables (`flyway_schema_history`, `app_meta`, `shedlock`).
- `all_pk_columns_are_uuid_not_null` (parameterized over 19 tables) — `INFORMATION_SCHEMA.columns.data_type = 'uuid' AND is_nullable = 'NO'`.
- `all_fk_columns_are_uuid` (parameterized over the FK list).
- `user_has_keycloak_sub_uuid_unique_nullable_partial`.
- `user_has_club_id_uuid_fk_to_club_on_delete_restrict`.
- `person_has_no_club_id_column` (sacred cow).
- `person_club_composite_unique_person_club` (both `uuid`).
- `person_club_role_flags_default_false` (parameterized).
- `person_club_member_state_id_fk_to_member_state_uuid`.
- `audit_columns_present_on_mutable_tables` (parameterized; `uuid` type; NO FK).
- `audit_columns_absent_on_reference_tables` (parameterized).
- Seed-pin assertions (parameterized over `reference-seeds-canonical-uuids.json`):
  - `country_seeded_with_fixed_uuids` (Switzerland UUID pinned).
  - `country_count_at_least_196`.
  - `language_seeded_at_least_5_rows_with_fixed_uuids`.
  - `start_type_seeded_5_canonical_values_with_fixed_uuids`.
  - `club_state_seeded_3_canonical_values_with_fixed_uuids`.
  - `role_seeded_with_canonical_codes_and_fixed_uuids`.
  - `unit_type_tables_seeded_with_fixed_uuids` (parameterized).
  - `extension_type_seeded_with_fixed_uuids`.
  - `member_state_NOT_seeded_in_this_migration` (per-club seeds at S-016).
  - `person_category_NOT_seeded_in_this_migration` (same).
- `email_template_has_nullable_club_id_for_defaults`.
- `email_template_composite_unique_club_template`.
- `index_on_person_club_composite_with_include` (introspect via `pg_indexes`).
- `username_lower_functional_unique_index`.
- `person_email_check_constraint_present`.
- `person_birthday_check_not_in_future`.
- `country_iso2_length_pinned`.
- `language_code_bcp47_check`.
- `aggregate_root_column_comments_reference_adr_0019` (parameterized over `person`, `club`, `user`) — `pg_description` contains "ADR 0019" + prefix token (`psn_`/`clb_`/`usr_`).
- `non_aggregate_root_columns_do_not_carry_prefix_comments` (sample of `person_club.id`, `country.id`).
- `user_club_id_principal_subject_comment_present`.

**New `TenantCatalogConsistencyTest`:**
- `every_tenant_scoped_table_has_club_id_uuid_not_null` (parameterized over yaml entries).
- `every_cross_tenant_table_has_no_club_id` (parameterized over `person`, `person_club`).
- `every_reference_table_has_no_club_id`.
- `every_system_global_table_has_no_club_id`.
- `tenant_rules_yaml_tenant_id_type_is_uuid` — parse YAML, assert `tenant_id_type: UUID` (flipped per ADR 0019).
- `tenant_rules_yaml_hibernate_pin_is_7x`.
- `tenant_rules_yaml_pii_columns_present_for_person_and_user`.
- `member_state_reclassified_to_tenant_scoped`.
- `person_category_reclassified_to_tenant_scoped`.

### Parity strategy

N/A — schema reshape per ADR 0008 + 0018 + 0019. Legacy `dbo.*` shape is reference-only. S-016 builds legacy-ID remapping at cutover.

### Test data + fixtures

- Shared `PostgresTestContainerLifecycle` — single per-JVM container.
- Same `@DynamicPropertySource` shape across `FlywayBootstrapIntegrationTest`, `IdentityBaselineIntegrationTest`, `TenantCatalogConsistencyTest` so Spring context cache hits (single boot per JVM).
- `reference-seeds-canonical-uuids.json` at `next/server/src/test/resources/` — pinned UUID v7 ground-truth map keyed by `(table, natural_key)`. Read once in `@BeforeAll`.
- Gradle `processTestResources` copy task lifts `next/database/tenant-rules.yaml` → `next/server/build/resources/test/database/tenant-rules.yaml`.

### Coverage gaps (deferred)

- JPA entity correctness + `@UuidV7` wiring → S-022.
- `@TenantId` filter behavior → S-022.
- Aggregate-method invariant enforcement → S-022/S-051.
- Aggregate prefix codec → S-022.
- Live cross-tenant leakage CI → S-024.
- Audit-log capture → S-027.
- DSAR cross-club cascade → S-051.
- Production-scale perf → S-108.
- `keycloak_sub NOT NULL` → S-052.

### Risks

- **V<n> collision with S-018 (shedlock).** Mitigation: pick at implement time from `db/migration/` listing; tests assert `>= 3` not `== 3`.
- **Canonical-UUID immutability.** Flyway checksum-locks the migration; a typo requires a follow-up migration with cascading FK updates. Mitigation: (a) commit the generator script for review; (b) pin tests against the JSON map so typos fail CI pre-merge; (c) snapshot date in code comments matches both files.
- **Test boot time growth.** 19 tables + ~250 seed rows add 1-2s. Mitigation: identical `@DynamicPropertySource` shape so context cache hits.
- **`tenant-rules.yaml` cross-module load.** Mitigation: Gradle `Copy` task with explicit `from`/`into`.
- **`pg_description` comment drift.** Mitigation: tolerant regex `ADR\s*[-_]?\s*0019` + tokenized prefix lookup.

## Performance plan

### Hot paths

- Login lookup (`user.username` UNIQUE via `LOWER(username)`): sub-ms.
- OIDC subject lookup (`user.keycloak_sub uuid UNIQUE`): sub-ms — UUID equality is 16-byte SIMD-accelerated on Postgres 17.
- Per-club Person directory (`person_club JOIN person` filtered by `club_id`): hottest read. < 30ms DB-side at 100K Persons.
- Cross-club Person fetch by PK: sacred-cow Flight crew load (`Person.findById(personId)` without `@TenantId`). Sub-ms.
- Email template lookup (`(club_id, template_code)` partial UNIQUE): < 10ms cold, < 1ms cached.
- Reference-data bootstrap (`country`, `language`, etc.): once per app boot. Cold ~30ms total.

### Required indexes

(All PK/FK columns now `uuid`; composite ordering preserved.)
- See per-table grid in Design notes above.

**Index footprint widening:** PK columns 8→16 bytes; composite-FK btrees roughly 2×. At S-012's scale:
- `person` PK + indexes: ~250 MB (vs ~125 MB BIGINT). Delta ~125 MB.
- `person_club` PK + composite UNIQUE + `(club_id, person_id) INCLUDE`: ~300 MB. Delta ~150 MB.
- User + reference tables: trivial deltas.
- **Aggregate S-012 delta: ~300 MB additional index space.** Inside ADR 0019's ~3-5 GB envelope (S-013 flight + flight_crew dominates).

### N+1 risks (forward to S-022)

- `person_club → person` on directory render: `@EntityGraph("personClubs.person")`.
- `person_club → member_state`: reference cache + `@BatchSize(50)`.
- `user → club`: second-level cache (one row per session).
- `extension_value → extension_type`: `@BatchSize(50)` + reference cache.
- `user → person` (when non-null): eager fetch-join in auth-principal-resolution path; lazy from admin user-list page.

### Caching

| Entity | Cache | TTL | Reason |
|---|---|---|---|
| Reference data (`country`, `language`, `member_state`, `person_category`, `start_type`, `*_unit_type`, `club_state`, `role`, `extension_type`) | L2 (Caffeine) | 24h | Read-only, invalidate on migration; < 1 MB total |
| `email_template` | L2 per-club | 5min | `(club_id uuid, template_code)`; evict on mutation |
| `club` | L2 per-club | 15min | ~100 clubs × 2 KB = 200 KB |
| `user` | **NEVER** | — | Auth state + lockout counters mutate per request |
| `person` / `person_club` | **NEVER** | — | PII + multi-club mutation patterns |

### Latency budget (forward to S-108)

- Login p95 < 20ms.
- OIDC subject lookup p95 < 10ms.
- Directory page (50 rows, 100K Persons) p95 < 100ms end-to-end, < 30ms DB.
- Email template lookup p95 < 10ms cold, < 1ms cached.
- Reference-data fetch p95 < 30ms cold, < 5ms cached.

### Memory

- Schema footprint at prod scale: ~150 MB heap + ~300 MB indexes (UUID-widened). Total ~450 MB.
- Postgres `shared_buffers` recommendation: **1 GB minimum** (was 128 MB default in prior refinement; UUID widening pushes the recommendation up). Document for S-019 ops config.
- Reference-data L2 cache: 1 MB ceiling.
- UUID v7 generator stateless; ~30ns per ID; irrelevant at FLS volume.

### Performance test plan

- `FlywayBootstrapIntegrationTest` boots in < 30s (existing budget).
- EXPLAIN on 3 canary queries asserts Index Scan (not Seq Scan) after seeding 10 fixture rows; force `enable_seqscan = off` for assertion only. Canaries: per-club Person directory; OIDC subject lookup; club-by-slug.
- Runtime latency tests deferred to S-108.

### Configuration choices

- `uuid NOT NULL PRIMARY KEY` (Postgres native; per ADR 0019).
- `TIMESTAMPTZ` for audit columns; `DATE` for `birthday`; `TEXT` for `email_template.body`.
- `jsonb` not introduced (reserved for S-014).
- UUID v7 via `UuidCreator.getTimeOrderedEpoch()` (uuid-creator library); Hibernate 7 `@UuidV7` annotation wires it at the entity layer in S-022.
- `pgcrypto` extension NOT required (app generates; no `gen_random_uuid()`).

## Open design questions

(Reduced from prior refinement — Q1 [BIGINT vs UUID] resolved by ADR 0019.)

1. **`member_state` + `person_category` per-club seed strategy at S-016 cutover.** Migration seeds none (no canonical defaults); legacy data drives the per-club seeds. Confirm S-016 owns this.
2. **`email_template` + `extension_value` repository surface at S-022.** When `club_id IS NULL` the row is SYSTEM_GLOBAL (not in any Club aggregate). Options: (a) `Club.findById(clb)` materializes only `email_templates WHERE club_id = clb`; system defaults via separate `SystemEmailTemplateRepository`; (b) `Club.findById(clb)` returns both per-club + system-default rows (Union semantics). Recommend (a); operator confirms at S-022.
3. **`username` case-sensitivity** (carried — Open Q4). `LOWER(username)` functional unique index recommended.
4. **Reference-data canonical UUIDs.** Generator script approach: (a) committed `generate-canonical-uuids.java` script + JSON output + embedded SQL literals (recommended; reviewable in PR); (b) UUID v5 namespace derivation. Recommend (a).
5. **`user.account_state_id` FK target.** Legacy carries `AccountState int`; lookup table not in S-012 scope. Recommend: ship column as `SMALLINT NOT NULL` matching legacy; defer FK addition to a "user lifecycle" follow-up story. Keeps 19-table count clean.
6. **Migration version (V2 vs V3).** Depends on whether S-018 has merged. Implementer reads `db/migration/` listing.

<!-- modernize-refine: end -->

## Review

<!-- modernize-review: start -->

**Reviewed:** 2026-05-16 (re-review after rework) · **PR:** [#25](https://github.com/elekktrisch/fls/pull/25) · **Diff size:** 14 commits, 28 files changed, +6073/-131 · **Outcome:** improvements-only

> **Re-review note:** this is the second review pass after the rework batch addressed all 11 prior improvements + 4 carry-along changes (H2 retirement, SharedPostgresContainer with CI fail-loud guard, ADR 0021, OWASP A02 header guard). All prior findings verified resolved except where noted below. New findings reflect the post-rework state.

### Maintainability

- **[improvement]** CONVENTIONS.md V2 line citation is **still wrong** — the rework moved the number but didn't correct it. `next/server/CONVENTIONS.md:81` says `V2__identity_and_reference.sql:93-102 — column on line 97`, but the actual `start_type` table is at **V2 lines 108-117 with `applicable_categories` on line 112** (after the OWASP A02 guard block inserted in the rework shifted everything down). Lines 93-102 are inside `language` / `club_state`. **Fix:** retarget to `V2__identity_and_reference.sql:108-117 — column on line 112`.
- **[improvement]** SnakeYAML is an undeclared transitive dependency — `TenantCatalogYamlTest.java:12` imports `org.yaml.snakeyaml.Yaml`, but `next/server/build.gradle.kts` declares no `snakeyaml` (test or otherwise). It resolves today only because Spring Boot bundles it via `spring-boot-starter`; any future starter slim-down silently breaks this test. **Fix:** add `testImplementation("org.yaml:snakeyaml")` (let Boot's BOM pin the version), or document the transitive expectation in a comment.
- **[improvement]** OWASP A02 header guard is **documentation-only — no automated enforcement** — `V2__identity_and_reference.sql:60-77` says "DO NOT add columns named `password_hash`, `password_salt`, `mfa_secret`, …" and the Security plan promises "Migration header MUST flag." But `src/test/resources/security/forbidden-migration-patterns.txt` only blocks the literal `PASSWORD '`, not those column names. A future migration `ALTER TABLE "user" ADD COLUMN password_hash bytea` passes every gate. **Fix:** extend the patterns file with `\b(password_hash|password_salt|mfa_secret|totp_seed|security_stamp|refresh_token|access_token|credential|reset_token|verification_token)\b`. (Cross-reviewer agreement with Security.)
- **[improvement]** Shadow auth-state columns kept on `user` — `V2__identity_and_reference.sql:280-283` retains `two_factor_enabled`, `lockout_enabled`, `lockout_end_date_utc`, `access_failed_count`. ADR 0007 explicitly hands "account lockout, MFA" to Keycloak; once S-052 wires the IdP these become stale shadow state that may drift out of sync with the source of truth. The V2 A02 header guard at line 68-69 names password/token/MFA-secret patterns but not these state flags. **Fix:** either drop them now (matching the `last_password_change_on` precedent) with the same rationale comment, or extend the A02 block to list them explicitly as "tolerated legacy columns, will be removed at S-052 cutover when Keycloak attributes are wired." (Cross-reviewer agreement with Security.)
- **[improvement]** `SharedPostgresContainer.AVAILABLE` static-init order is fragile — `SharedPostgresContainer.java:32-33` declares `INSTANCE` then `AVAILABLE = tryStart()` which calls `INSTANCE.start()`. Field-init order works today (declaration order), but a future refactor that flips the lines, or a `static {}` block inserted above, silently NPEs. **Fix:** collapse both into a single `static {}` block with explicit ordering, or lazy-init `AVAILABLE` inside `available()` behind a once-flag.
- **[improvement]** `TenantCatalogYamlTest.locateTenantRules` walk is needlessly clever — `TenantCatalogYamlTest.java:103-116` walks `getParent()` ancestors with two candidate paths. `tenant-rules.yaml` lives one fixed sibling away from `next/server/`; a one-line `Path.of("../database/tenant-rules.yaml")` (Gradle always runs tests with `cwd = module dir`) suffices. **Fix:** drop the loop; use `Path.of("../database/tenant-rules.yaml")` with one assert-exists. Or stuff a copy under `src/test/resources/` at build time via a Gradle copy task.
- **[improvement]** `IdentityBaselineIntegrationTest.all_19_tables_present` framework-tables whitelist drifts from production — `IdentityBaselineIntegrationTest.java:95` hardcodes `Set.of("flyway_schema_history", "app_meta")`. When S-018's shedlock migration lands, it'll add a `shedlock` table and this test will break for the wrong reason. **Fix:** assert `actual` is a superset of the 19 domain tables AND a subset of `expected + knownFramework`, where `knownFramework` lives next to the test as a documented allowlist with one entry per ADR-acknowledged framework concern.
- **[nudge]** `PostgresTestContainerLifecycle.DB_NAME = "fls_test"` collision risk if `maxParallelForks > 1` lands later. Only one container per JVM today; future parallel-Gradle scenarios could share the DB name across forks. **Fix:** include the random suffix in `DB_NAME` so the container's DB name is unique by construction.
- **[nudge]** ADR 0021 §"escape hatch" is vague — `docs/modernization/adrs/0021-integration-test-data-isolation.md:43` says "a test that genuinely cannot be tenant-scoped … opts out and uses TRUNCATE for the duration of its own setUp." No marker / annotation / package prescribed. When S-024 leakage CI looks for `@AfterEach`-TRUNCATE violations, it'll need to distinguish allowed escapes from forbidden ones. **Fix:** document the marker (`@TenantScopedExempt` annotation? `truncate.allowlist` file?) before the first exception lands.
- **[nudge]** `IdentityBaselineIntegrationTest.all_fk_columns_are_uuid` lacks the same silent-zero-row guard as M-I-5 fixed for PK — `IdentityBaselineIntegrationTest.java:161-186` iterates the FK rows and asserts each is uuid, but if the FK join returns zero rows the test passes silently. M-I-5 added the guard for PKs only. **Fix:** mirror the same `collect-then-assert-non-empty` pattern.

### Parity

**Oracle:** N/A — schema reshape per ADR 0008 + 0018 + 0019 (+ ADR 0020 for `start_type`). Legacy `dbo.*` is reference-only; S-016 owns the legacy-MSSQL → new-Postgres cutover parity oracle.

Re-review verification: (a) the rework's drop of `last_password_change_on` + `force_password_change_next` is parity-safe — grepped both columns across `flsserver/src` and `flsweb/src`; all references are auth-only (UserService password-change, IdentityUserStoreService, UserDetails DTO, password-change test). Zero non-auth consumers; Keycloak fully supersedes. (b) `V2__identity_and_reference.sql:62-75` OWASP A02 guard matches the Security plan verbatim. (c) Drop justification at `V2:286-288` cross-references the header + ADR 0007.

- **[nudge]** When S-016 is refined, add a one-line "skip `User.LastPasswordChangeOn` + `User.ForcePasswordChangeNextLogon` — Keycloak owns; no target column" to its legacy-User mapping section. Not a blocker on S-012; forward-pointer so the cutover author doesn't re-derive this finding.

### Security

Rework verification (all 4 prior items landed correctly):
- S-I-1: V2 drops `last_password_change_on` / `force_password_change_next` with rationale at `V2__identity_and_reference.sql:286-288`.
- S-I-2: OWASP A02 guard block present at `V2__identity_and_reference.sql:62-75`.
- S-I-3: All four service ports (mssql 1433, mailpit 1025/8025, postgres 5432, pgadmin 8080) bound to `127.0.0.1`; rationale at `docker-compose.yml:36-40`.
- S-I-4: "DEV ONLY" block above pgadmin at `docker-compose.yml:110-116`.

- **[improvement]** Shadow auth-state columns kept on `user` contradict ADR 0007 — `V2__identity_and_reference.sql:280-283` keeps `two_factor_enabled`, `lockout_enabled`, `lockout_end_date_utc`, `access_failed_count`. ADR 0007 explicitly hands "account lockout, MFA" to Keycloak; once S-052 wires the IdP these become stale shadow state. The A02 header guard at line 68-69 names password/token/MFA-secret patterns but not these state flags. **Fix:** either drop them now (matching the `last_password_change_on` precedent), or extend the A02 block to list them as "tolerated legacy columns, will be removed at S-052 when Keycloak attributes are wired." (Cross-reviewer agreement with Maintainability.)
- **[improvement]** A02 forbidden-column enumeration is not exhaustive — `V2__identity_and_reference.sql:68-69` lists 8 patterns plus "or any equivalent." Common future-PR risks not literally named: `credential`, `client_secret`, `api_key`, `reset_token`, `verification_token`, `session_token`, `recovery_code`. The catch-all phrase covers them in spirit, but a reviewer scanning for literal matches may miss `verification_token`. **Fix:** extend `forbidden-migration-patterns.txt` with a regex covering the broader pattern; the test catches what the comment relies on humans to catch. (Cross-reviewer agreement with Maintainability.)
- **[improvement]** `MigrationFolderConventionsTest` strips only line comments — `MigrationFolderConventionsTest.java:107` uses `--[^\n]*`. V2 today has no `/* ... */` block comments, but a future `R__` or `V3__` migration could introduce them; a forbidden literal inside a block comment would trigger a false-positive (or, more dangerously, hide a real violation in a `/* */` block). **Fix:** extend the strip to also consume `/\*.*?\*/` (DOTALL).
- **[improvement]** `SharedPostgresContainer.available()` keys CI fail-loud on `CI` env-var presence only — `SharedPostgresContainer.java:52`. A workflow step that explicitly does `unset CI` (or runs tests in a sub-shell with a sanitised environment) would bypass the guard and silently skip every DB test. **Fix:** also check `GITHUB_ACTIONS`, `GITLAB_CI`, `BUILDKITE`, or accept any of `{CI, GITHUB_ACTIONS}` as the fail-loud trigger.
- **[nudge]** `MssqlTestContainerLifecycle.SA_PASSWORD = "TestPa$$w0rd_2026"` at `next/database/extract/src/test/java/ch/fls/legacyextract/MssqlTestContainerLifecycle.java:40` is a test-only literal — fine, but worth a single-line comment "test-only; never reused in prod" so a future pre-commit gate that greps for "password =" doesn't false-positive.

### Usability

(N/A — no UI changes; backend-schema-only story.)

Four operator-facing artifacts re-verified post-rework: V2 migration header (OWASP A02 guard reads cleanly), `next/server/CONVENTIONS.md` column-shape + ID-strategy sections, `docker-compose.yml` `--- DEV ONLY ---` block (names the consequence rather than just labeling it), pgAdmin setup. All read cleanly for a future contributor.

No new usability findings. The three `dev-up-full.sh` nudges from the first review (ANSI escapes, tear-down drift, fall-back hint) were auto-accepted in the rework triage and remain open as accepted.

### Cross-reviewer agreements

- **OWASP A02 column-name guard is documentation-only — no automated enforcement.** Maintainability + Security both flagged the same gap. The V2 header at `V2:60-77` enumerates forbidden column names beautifully, the Security plan promised enforcement, yet nothing in CI fails when a future migration adds `password_hash bytea`. One regex line in `forbidden-migration-patterns.txt` closes the gap and is the **strongest signal** of the re-review.
- **Shadow auth-state columns on `user`** — Maintainability + Security both flagged the residual `two_factor_enabled`, `lockout_enabled`, `lockout_end_date_utc`, `access_failed_count` columns. ADR 0007 hands these to Keycloak; the V2 A02 header guard scrupulously lists password/token/MFA-secret patterns but leaves these state flags in. Pick a side before S-052 wires Keycloak.
- **`SharedPostgresContainer.available()` gate strength** — Maintainability flagged static-init order fragility; Security flagged the CI env-var being a single point of bypass. Same file (`SharedPostgresContainer.java:32-52`), overlapping concerns.

<!-- modernize-review: end -->
