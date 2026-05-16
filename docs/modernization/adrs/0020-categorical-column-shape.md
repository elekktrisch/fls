# 0020 — Categorical column shape: enum-as-string by default

- **Status:** Accepted
- **Date:** 2026-05-16
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  6. Preserves sacred cows cleanly
  7. Solo-operator operability
  8. Enables fast feature dev post-cutover
  11. Mature ecosystem for our integration points

## Context

The legacy schema mixes three column shapes for categorical / state / discriminator values, with no consistent rule:

- **Booleans for almost everything.** `Person` has 10 licence flags + 6 start-permission flags + 11 metadata flags; `PersonClub` has 8 role flags + 4 notification preference flags + `IsActive`; `Club` has 3 job-toggle flags + `IsClubMemberNumberReadonly`; `User` has 5 auth-state flags.
- **`SMALLINT` discriminators** for state machines (`User.AccountState` 1/2/10, `Delivery.ProcessState`, `Flight.ProcessState`, `Flight.FlightAircraftType`). Opaque in raw queries + audit blobs.
- **FK-to-lookup-table** for richly-described enums (`Club.ClubStateId → ClubStates`, `Person.CountryId → Countries`). Carries per-language display names and sort metadata.

The rewrite has three pressure points pulling toward enum-as-string for the first two:

1. **R5 — enum drift between server and client** ([current-state §7](../01-current-state.md#r5--flightstatemapper-enum-duplication)): legacy hand-encodes server enum values as JS strings in `FlightStateMapper`. [ADR 0005](0005-api-shape.md)'s OpenAPI codegen story fixes drift by generating TS from the server contract — but only if the server contract names enum values readably. `SMALLINT 30` means nothing to the codegen layer; `'BOOKED'` does.

2. **Audit-blob readability** ([ADR 0027](../stories/) area): audit log captures `before`/`after` JSON snapshots. `"process_state": 30` requires a reverse-lookup; `"process_state": "BOOKED"` is self-describing.

3. **Boolean-cluster combinatorial gaps**: multiple booleans encoding a state machine have 2^N combinations of which most are usually invalid. The schema doesn't know which. Collapsing to a single enum locks down legal states structurally.

This ADR pins the **default** column shape for categorical / state / discriminator columns and the rule for when to deviate. It does not retire FK-to-lookup-table for genuine reference data — that pattern stays for the country / language / member_state / person_category / role cases where per-language display names + sort order + soft-deprecation flags justify the JOIN.

## Options considered

### Option A — `VARCHAR` (no DB CHECK) ← chosen

- **Capabilities:** Postgres `VARCHAR(N) NOT NULL` storage. The Java enum is the **only** source of truth for the value set; no DB-side `CHECK IN (...)` constraint mirrors it. Adding or removing a value is a Java-only change; no Flyway migration to keep in lock-step. Maps to Java enums via `@Enumerated(EnumType.STRING)`. JSON serialization is native (`"col": "A"`). Audit blobs + raw `psql` + log lines all show the readable value. OpenAPI emits `enum: [A, B, C]` in the schema (derived from the Java enum), which orval / openapi-typescript turns into a TS string-union — closes R5 by construction. SET-membership case (e.g., `applicable_categories`) maps to `TEXT[] NOT NULL` (no subset / non-empty CHECK either — Java enforces).
- **Fit to criteria:**
  - **2 ✓** — `@Enumerated(EnumType.STRING)` is one annotation per JPA entity field; idiomatic Spring Data; no custom converter.
  - **6 ✓** — `Flight.flight_process_state`, `Delivery.process_state` become readable strings; sacred-cow state-machine columns stop being SMALLINT 10/20/30/99 with a separate hand-written enum-to-name lookup.
  - **7 ✓** — operator inspecting prod data via `psql` or pgAdmin sees `'BOOKED'`, not `30`. No reverse-lookup table needed for forensic browsing.
  - **8 ✓** — OpenAPI codegen path is one step: Java enum → `springdoc-openapi` exports it → orval generates TS string-union. Single source of truth.
  - **11 ✓** — universal SQL feature; no Hibernate-specific incantation; works with every observability / migration / replication tool.
- **Migration cost:** For new tables, zero — write `VARCHAR(32) NOT NULL`. For an existing column moving from boolean cluster to enum, write a normal Flyway migration: add the new column, backfill from existing booleans, drop the booleans. Adding/renaming/removing a value: **Java-only change** — no migration. Renaming an outgoing value still requires an `UPDATE` of existing rows that hold the old string; removing requires an upstream value-replacement (same as any data-migration).
- **Ecosystem risk:** low. `@Enumerated(EnumType.STRING)` is JPA standard; no DB feature beyond `VARCHAR`.
- **Trade-off — accepted:** dropping the DB `CHECK` removes defence-in-depth. An out-of-band INSERT (operator running raw SQL, a buggy migration, a Java path bypassing the enum) can persist an unknown value. The reverse — keeping the CHECK — forced a Flyway migration on every enum-value addition; that drag was judged worse than the missing safety-net. Mitigation: enum-value INSERTs only happen from JPA-mapped service paths in practice; operator scripts are reviewed; S-016 cutover validates target values.
- **Escape hatch:** a column can migrate from enum-as-string → FK-to-table without changing the entity API: replace the column with `<name>_id UUID` + populate a lookup table seeded with the same string codes as natural keys. JPA mapping switches from `@Enumerated` to `@ManyToOne` — entity field shape changes but service-layer + API contract stays stable if the new lookup table's display value matches the previous string code.

### Option B — Postgres native `CREATE TYPE foo AS ENUM (...)`

- **Capabilities:** Strongly-typed by Postgres itself; the type system rejects invalid values without a `CHECK` constraint. `ALTER TYPE ... ADD VALUE` adds values transactionally on Postgres 12+. Type appears in `pg_type` catalog.
- **Fit to criteria:** 2 ✗ — Hibernate ↔ Postgres ENUM mapping requires `@Type` annotations + a custom `UserType` per type (or the `hibernate-types` library), adding boilerplate to every entity field. Otherwise wins are marginal vs Option A.
- **Why not chosen:** removing or renaming a value requires recreating the type + reassigning every column referencing it. The Hibernate friction is real per-column overhead; the strong-typing win over `CHECK` is invisible to anyone but the database administrator. Operator-friendliness (criterion 7) and ecosystem maturity (criterion 11) tip back to Option A.

### Option C — FK to reference / lookup table (legacy pattern)

- **Capabilities:** Most flexible — each row carries per-language display names, sort order, soft-deprecation flag, club-scoped overrides if needed. Adding a value = `INSERT`. Renaming = `UPDATE` of the row's display name; the FK doesn't shift.
- **Fit to criteria:** 7 ✗ — audit blobs and raw queries carry UUIDs (per [ADR 0019](0019-entity-id-strategy.md)), not readable values. Resolution requires a JOIN. 8 ~ — OpenAPI codegen can't auto-derive the enum membership from a runtime FK target; the contract is fuzzier.
- **Why not chosen as default:** too heavy for columns that just need "one of N strings". **Retain** for genuine reference data — `country` (per-language display name + ISO codes + dial code), `language`, `member_state` (per-club + lifecycle), `person_category` (hierarchical), `role` (S-026 finalises permission matrix per row). Those are columns where the look-up table's *extra metadata* justifies the JOIN.

### Option D — `SMALLINT` discriminator (legacy)

- **Capabilities:** Compact (2 bytes); fastest equality comparisons.
- **Fit to criteria:** 7 ✗, 8 ✗, 10/audit ✗ — values are opaque numbers; reverse-lookup required everywhere.
- **Why not chosen:** operator's explicit framing of the question rejected this shape. The compactness win (2 bytes vs ~8-12 for enum-as-string) is imperceptible at FLS scale.

## Decision

Chosen: **Option A — `VARCHAR` (no DB CHECK; Java enum is the only enforcer)** as the default column shape for categorical / state / discriminator columns. Java side maps via `@Enumerated(EnumType.STRING)`; code values UPPER_SNAKE_CASE for grep-ability. Adding / removing enum values is a Java-only change with no migration burden.

### Decision rule (applies from this ADR forward)

1. **Categorical / state / discriminator column** → enum-as-string. `VARCHAR(32) NOT NULL` (no CHECK). Java `@Enumerated(EnumType.STRING)` is the value-set authority. Code values UPPER_SNAKE_CASE.

2. **Independent, orthogonal flags** (each can vary without affecting the others' validity) → keep `BOOLEAN`. Examples that pass this test: `user.email_confirmed`, `person.prefer_mail_to_business_mail`, `person_club.is_active`, `club.run_delivery_creation_job`.

3. **Multiple booleans where some combinations are illegal** → collapse to enum-as-string. Concrete future case: a `user_account_state` that today would be `is_active`/`is_locked`/`is_disabled` belongs in one `account_state VARCHAR(16) NOT NULL` column — 8 boolean-combinations of which 3 are valid, the Java enum enforces.

4. **Multiple booleans encoding SET-MEMBERSHIP** (every combination is valid, but the "subset of N" relationship matters at the type level) → Postgres `TEXT[] NOT NULL` (no subset / non-empty CHECK). Java side maps via `@JdbcTypeCode(SqlTypes.ARRAY)` and enforces both subset and non-empty invariants.

5. **Categorical with rich metadata** (per-language display name, sort order, soft-deprecation, club-scoped variants) → FK-to-reference-table. Retain. Examples: `country`, `language`, `member_state`, `person_category`, `role`.

### Natural-invariant CHECKs still allowed

This ADR drops CHECKs **only** for enum value-set restrictions and SET-membership subset / size restrictions. CHECKs that encode **permanent business invariants** independent of any value-set (which never change with code) remain useful and recommended:

- `CHECK (birthday IS NULL OR birthday <= CURRENT_DATE)` — birthdays in the past.
- `CHECK (upper(iso2_code) = iso2_code)` — country code casing.
- `CHECK (email LIKE '%_@_%._%')` — coarse email shape.

These survive the rule because they don't drift with enum changes — they encode physical / business facts.

## Retroactive application to S-012

S-012's column inventory was reviewed under this rule:

| Boolean cluster | Decision | Shape after |
|---|---|---|
| `start_type.is_for_glider / is_for_tow / is_for_motor` | Collapse (rule 4) | `applicable_categories TEXT[] NOT NULL` (no CHECK — Java enforces subset + non-empty) |
| `person.has_motor_pilot_licence / has_tow_pilot_licence / has_glider_*_licence / has_tmg_licence / has_winch_operator_licence / has_motor_instructor_licence / has_part_m_licence` (10 flags) | Keep boolean (rule 2) | Unchanged. A `person_licence` junction table is a future story when expire-date per licence-type needs richer modelling than the parallel `medical_classN_expire_date` columns. |
| `person_club.is_motor_pilot / is_tow_pilot / is_glider_instructor / is_glider_pilot / is_glider_trainee / is_passenger / is_winch_operator / is_motor_instructor` (8 flags) | Keep boolean (rule 2) | Unchanged. Independent per-club role capabilities. |
| `person_club.receive_*`, `is_active` | Keep boolean (rule 2) | Unchanged. |
| `user.email_confirmed / phone_number_confirmed / two_factor_enabled / lockout_enabled / force_password_change_next` | Keep boolean (rule 2) | Unchanged. |
| `club.run_delivery_creation_job / run_delivery_mail_export_job / is_club_member_number_readonly` | Keep boolean (rule 2) | Unchanged. |
| `person.prefer_mail_to_business_mail`, `has_glider_*_start_permission` (3 permission bits) | Keep boolean (rule 2) | Unchanged. |
| `user.account_state_id SMALLINT` (legacy 1/2/10 — Active/Locked/Disabled) | Existing SMALLINT preserved in S-012 for now; reshape to enum-as-string in a follow-up "user lifecycle" story | Tracked as Open Q5 in S-012; not in this ADR's retroactive scope. |

Only one S-012 reshape lands in this ADR's wake: `start_type.is_for_glider/tow/motor` BOOLEAN ×3 → `applicable_categories TEXT[]`.

## Consequences

- **Positive:**
  - **R5 closed at the data layer.** Server emits the same string values that Postgres stores; OpenAPI codegen produces TS unions that name the same values; one source of truth. Drift becomes a compile-time error in the generated client.
  - **Audit-log blobs (S-027) become self-describing.** A 2030 forensic review of "what was this row's state?" doesn't require a 2026 SMALLINT lookup table.
  - **State-machine illegal-state INSERTs caught at the DB.** `CHECK` constraint rejects unknown values; the runtime never relies on "the service layer validated this."
  - **Operator inspections** (`psql`, pgAdmin, ad-hoc SQL) show readable values without JOINs.
  - **JPA mapping cost is one annotation per field** — `@Enumerated(EnumType.STRING)`. Same boilerplate as smallint discriminator.
  - **Set-membership case has a typed home** — `TEXT[]` array with `<@` subset CHECK locks down legal subsets without exploding into per-element booleans.

- **Negative:**
  - **Storage cost: 4-12 bytes per row per enum vs 2 bytes for `SMALLINT`.** Imperceptible at FLS scale (top tables are flight + flight_crew + audit_event, all well under 10M rows in the 5-year horizon).
  - **No DB-side defence-in-depth on enum value sets.** An out-of-band INSERT (operator running raw SQL, S-016 cutover script bug, a bypass of the JPA layer) can write an unknown value; subsequent reads materialize it into the enum and fail with `IllegalArgumentException` at deserialization. Mitigation: enum-value INSERTs only happen from JPA-mapped service paths in practice; operator scripts are reviewed; S-016 cutover script validates target values before write.
  - **Renaming an enum value still requires an `UPDATE` of existing rows** (Java enum constant + OpenAPI spec + TS callers + the SQL `UPDATE` to rewrite stored values). Mitigation: don't rename — deprecate the old value + add the new one. Same discipline as renaming a public API field.
  - **OpenAPI codegen does NOT introspect Postgres column constraints.** The Java enum is the canonical declaration; springdoc-openapi exports it; orval generates the TS union from there. Acceptable — Java is the design canonical anyway per [ADR 0001](0001-backend-language-and-framework.md).
  - **`TEXT[]` array columns are harder to index than scalars.** Mitigation: GIN index on the array column when set-membership queries become hot. Not needed for `start_type` (5 rows total).

- **Follow-ups (other ADRs / stories implied):**
  - **S-012 in-flight reshape**: replace `start_type.is_for_glider/tow/motor` with `applicable_categories TEXT[]`; update seed rows; update test assertions. Lands as a commit within the in-flight S-012 PR.
  - **Convention entry**: add a `## Column shape` section to `next/server/CONVENTIONS.md` (first authored by S-012) summarising the decision rule + linking back here. Future schema PRs cite this conventions section.
  - **Re-refine S-013** (flight + flight_crew): the speculative refinement may have proposed `flight_air_state_id` / `flight_process_state_id` as `SMALLINT` or FK columns. Change to `flight_air_state VARCHAR(32) CHECK IN (...)` + `flight_process_state VARCHAR(32) CHECK IN (...)`. Drop the `FlightAirStates` + `FlightProcessStates` reference tables from S-011's classification (they were `reference`); they become Java-enum value sources only — no DB tables. Same for `FlightCrewType` (enum) and `FlightCostBalanceType` (enum).
  - **Re-refine S-014** (reservations / planning / accounting): `Delivery.process_state` → enum-as-string (`'NEW' | 'PREPARED' | 'BOOKED' | 'PROCESSED'`); `PlanningDayAssignmentType` → enum-as-string; `AccountingRuleFilterType` → enum-as-string. The `process_state_id` naming from S-014's speculative refinement becomes `process_state` (no `_id` suffix — it's not an FK).
  - **S-011 amendment** to `next/database/tenant-rules.yaml`: tables reclassified from `reference` to "enum value source — no DB table" — `FlightAirStates`, `FlightProcessStates`, `FlightCrewTypes`, `FlightCostBalanceTypes`, `AircraftReservationTypes` (if it's a closed set), `PlanningDayAssignmentTypes` (if closed), `AccountingRuleFilterTypes`. Open items the operator decides per-table in the re-refinement of each story.
  - **User-lifecycle follow-up story** (TBD): reshape `user.account_state_id SMALLINT NOT NULL` (S-012) to `account_state VARCHAR(16) NOT NULL CHECK IN ('ACTIVE','SUSPENDED','DISABLED')`. Closes S-012's Open Q5.
  - **S-022 entity skeleton**: every JPA entity with a categorical field uses `@Enumerated(EnumType.STRING)` + a Java enum named after the column. UPPER_SNAKE_CASE matches the SQL literals.
  - **No-op for**: S-024 leakage CI (column-name parameterised; agnostic to type), S-027 audit-event (column-name-based redaction; agnostic), S-016 cutover (legacy SMALLINT → new VARCHAR is a per-table mapping in the cutover script — straightforward).
