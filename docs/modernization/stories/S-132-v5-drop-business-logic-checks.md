---
id: S-132
title: V5 — drop business-logic CHECKs + generated total_amount from V4 per ADR 0022
epic: E-02
status: done
started_at: 2026-05-17
done_at: 2026-05-17
depends_on: [S-014]
acceptance:
  - V5 migration drops business-logic CHECK constraints from V4 that encode domain rules (state machine values, range guards, required-when-state-X, sanity caps, calculated values). Aggregate-method enforcement lands at S-022/S-064. Schema retains only structural invariants (PKs, FKs, structural NOT NULL, identity-bearing partial UNIQUE, performance indexes) per [ADR 0022 directive 2](../adrs/0022-modernization-primary-directives.md).
  - Specific drops — `ck_dlv_process_state_in_set` (state-machine value set; aggregate `Delivery.transitionTo()` owns); `ck_dlv_delivery_number_positive` (range; value object owns); `ck_dlv_batch_id_nonnegative` (range; value object owns); `ck_dlv_booked_requires_number` / `ck_dlv_booked_requires_delivered_on` / `ck_dlv_booked_requires_recipient` (state-machine guards; `Delivery.book()` owns); `ck_dli_quantity_nonnegative` / `ck_dli_unit_price_nonnegative` / `ck_dli_discount_range` / `ck_dli_position_positive` (value-object ranges); `ck_dcti_*` (snapshot ranges); `ck_arv_max_30_days` (operational cap; `AircraftReservation` aggregate owns); `ck_pln_planning_date_reasonable` (sanity; aggregate owns); `ck_pdat_required_nr_nonnegative` (value-object range); `ck_arf_sort_indicator_nonnegative` (value-object range); `ck_cdnc_next_number_positive` (value-object range).
  - Retain — `ck_arv_end_after_start` (structural for the generated `reservation_range tstzrange`; tsrange constructor rejects inverted ranges anyway), `ck_dlv_process_state` if reframed as defense-in-depth for the legal-record invariant (OR Art. 957a); decision per refinement.
  - Drop `delivery_item.total_amount NUMERIC(14,4) GENERATED ALWAYS AS (...) STORED`. Calculation moves to `DeliveryItem.totalAmount()` method (or value-object) at S-022. V5 migration drops the generated column; service-layer computes on read (and on persistence if needed for audit / reporting).
  - V5 must also `DROP INDEX ix_dli_delivery` and re-create it without `total_amount` in the `INCLUDE` clause: `CREATE INDEX ix_dli_delivery ON delivery_item (delivery_id) INCLUDE (article_id, article_number, quantity, unit_price);`. Otherwise the `DROP COLUMN total_amount` errors with "column used by index ix_dli_delivery" — caught by V4 review pass 2 (`maintainability`, M3).
  - V5 migration includes a `forbidden-migration-patterns.txt` entry for `CHECK \(.+\)` (with documented allow-list for the structural retentions) so future implementers can't accidentally re-add business-logic CHECKs. The deny list cites ADR 0022 in its rationale block.
  - Tests in `ReservationsBaselineIntegrationTest` that asserted the dropped CHECKs are removed; replaced (where applicable) with aggregate-method unit tests at S-022. `MigrationFolderConventionsTest` extends with `forbidden_check_patterns_caught` test.
  - `tenant-rules.yaml` carries no new entries (unchanged shape); story body documents the schema-shape shift in `## Implementation notes`.
estimate: S
adr_refs: [0018, 0019, 0022]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements, solution, qa, performance]
github_issue: 44
github_pr: 45
origin: rework-meta
origin_story: S-014
origin_pattern: V4 ships ~15 business-logic CHECK constraints + 1 generated calculation column that under ADR 0022 directive 2 belong on aggregates. Filed pre-emptively so V4 stays directive-compliant before any production deployment.
---

## Context

S-014 (V4__reservations_planning_accounting.sql) shipped before [ADR 0022](../adrs/0022-modernization-primary-directives.md) was accepted. The migration carries ~15 CHECK constraints encoding business logic (state-machine values, range guards, "required when state=X", sanity caps) and one generated calculation column (`delivery_item.total_amount`). Under ADR 0022 directive 2 these belong on aggregates, not the schema.

V4 hasn't shipped to a production environment (only ephemeral Testcontainers), so V4 itself is technically still amendable — but the operator's policy is "once committed + CI-green = treat as locked" to keep the Flyway-checksum + multi-environment story simple. So: V5 drops them.

The split also enables faster behavior change: a future business rule shift (e.g. "Booked deliveries can be revoked within 24h") is a Java deployment instead of a coordinated migration + multi-env rollout. That's the whole point of Directive 2.

## Acceptance criteria

See frontmatter.

## Tasks

Superseded by `## Implementation notes` below — story scope pivoted mid-implementation from a corrective V5 migration to in-place editing of V1-V4 ("no grandfather exceptions" operator directive). Frontmatter ACs are preserved as the contract that was filed; the Implementation notes section maps how each was satisfied (or superseded by the pivot).

## Notes

This is the first story to be filed *because* ADR 0022 landed. It's a deliberate guinea-pig for the directive's enforcement story — if the workflow can't easily ship this kind of "remove the business logic from schema" change, the directive isn't load-bearing.

Estimate: S. A focused 2-4 hour pass; mostly mechanical edits + test deletions.

Refinement note: `## Open design questions` should surface the Booked-requires-* CHECKs and the `ck_arv_end_after_start` question for the operator before drop decisions are baked in.

<!-- modernize-refine: start -->

## Design notes

### Migration file shape

Filename: `V5__drop_business_logic_checks.sql`. Header matches V1-V4 style — 80-col `-- ===` rule bars, top block citing ADR 0022 directive 2 + naming the re-home stories (S-022 for the Delivery aggregate + value objects, S-064 for service-layer state-machine guards). No `app_meta` bump (V5 is corrective, not a new schema generation).

Statement order:

```sql
-- 1. ix_dli_delivery rebuild (must precede DROP COLUMN total_amount).
DROP INDEX ix_dli_delivery;
ALTER TABLE delivery_item DROP COLUMN total_amount;
CREATE INDEX ix_dli_delivery
    ON delivery_item (delivery_id)
    INCLUDE (article_id, article_number, quantity, unit_price);

-- 2. delivery — business-logic CHECKs.
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_process_state_in_set;     -- domain enum → S-022
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_delivery_number_positive; -- VO range  → S-022
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_batch_id_nonnegative;     -- VO range  → S-022
-- Booked-requires-* trio: subject to OR Art. 957a operator decision (see Open design questions).
-- Default = drop. If retain, omit these three statements + COMMENT ON CONSTRAINT … rationale.
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_booked_requires_number;
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_booked_requires_delivered_on;
ALTER TABLE delivery DROP CONSTRAINT ck_dlv_booked_requires_recipient;

-- 3. delivery_item — value-object ranges.
ALTER TABLE delivery_item DROP CONSTRAINT ck_dli_position_positive;
ALTER TABLE delivery_item DROP CONSTRAINT ck_dli_quantity_nonnegative;
ALTER TABLE delivery_item DROP CONSTRAINT ck_dli_unit_price_nonnegative;
ALTER TABLE delivery_item DROP CONSTRAINT ck_dli_discount_range;

-- 4. delivery_creation_test_item — mirrors dli_*.
ALTER TABLE delivery_creation_test_item DROP CONSTRAINT ck_dcti_position_positive;
ALTER TABLE delivery_creation_test_item DROP CONSTRAINT ck_dcti_quantity_nonnegative;
ALTER TABLE delivery_creation_test_item DROP CONSTRAINT ck_dcti_unit_price_nonnegative;
ALTER TABLE delivery_creation_test_item DROP CONSTRAINT ck_dcti_discount_range;

-- 5. operational caps + sanity guards.
ALTER TABLE aircraft_reservation DROP CONSTRAINT ck_arv_max_30_days;
ALTER TABLE planning_day DROP CONSTRAINT ck_pln_planning_date_reasonable;
ALTER TABLE planning_day_assignment_type DROP CONSTRAINT ck_pdat_required_nr_nonnegative;
ALTER TABLE accounting_rule_filter DROP CONSTRAINT ck_arf_sort_indicator_nonnegative;
ALTER TABLE club_delivery_number_counter DROP CONSTRAINT ck_cdnc_next_number_positive;

-- 6. Retained: ck_arv_end_after_start (deviation — see "Deviations from ADR 0022" below).
COMMENT ON CONSTRAINT ck_arv_end_after_start ON aircraft_reservation IS
  'ADR 0022 retained: pins generated tstzrange shape — empty-range degenerate (lower=upper) would slip past GiST conflict probes. Borderline-structural retention.';
```

Verified by V4 grep: `total_amount` is referenced only in the `ix_dli_delivery` INCLUDE clause and the GENERATED column definition — no other indexes, views, materialized views, or rules touch it. Postgres drops the column comment (V4:673-674) automatically with the column.

No `CREATE INDEX CONCURRENTLY` — Flyway wraps each migration in a transaction; CONCURRENTLY would fail at runtime. Plain transactional DDL is correct because V4 has not shipped to a populated environment.

### Retain / drop decisions

| Constraint | Type | Decision | Rationale |
|---|---|---|---|
| `ck_arv_end_after_start` | range (border on structural) | **Retain (deviation)** | `tstzrange(start, end, '[)')` rejects `start > end` but accepts `start == end` (empty range — valid value, GiST treats as non-overlapping). The CHECK closes the equal-bounds case, ruling out zero-duration reservations the GiST conflict probe would silently let through. **Deviation marker required** — see Deviations below. |
| `ck_arv_max_30_days` | operational cap | Drop | 30-day cap is policy. `AircraftReservation.validateDuration()` at S-064. |
| `ck_dlv_process_state_in_set` | domain enum (10/20/30/99) | Drop | ADR 0022 explicitly: domain enums → `@Enumerated(EnumType.STRING)` (ADR 0020). `Delivery.ProcessState` at S-022. Story-body shorthand `ck_dlv_process_state` is **not** the constraint name; use `ck_dlv_process_state_in_set`. |
| `ck_dlv_delivery_number_positive` | VO range | Drop | `DeliveryNumber` VO constructor. S-022. |
| `ck_dlv_batch_id_nonnegative` | VO range | Drop | `BatchId` VO constructor. S-022. |
| `ck_dlv_booked_requires_number` | state-machine guard | **Drop (default)** | See Open design questions — OR Art. 957a retention is a valid alternative. Default drop on consistency grounds: gap-free numbering is already enforced by `ux_dlv_club_number_partial`; keeping three half-state-machine CHECKs scatters policy across layers. |
| `ck_dlv_booked_requires_delivered_on` | state-machine guard | **Drop (default)** | Same. |
| `ck_dlv_booked_requires_recipient` | state-machine guard | **Drop (default)** | Same. |
| `ck_dli_position_positive` / `_quantity_nonnegative` / `_unit_price_nonnegative` / `_discount_range` | VO range | Drop | `Position` / `Quantity` / `Money` / `DiscountPercent` VOs at S-022. |
| `ck_dcti_position_positive` / `_quantity_nonnegative` / `_unit_price_nonnegative` / `_discount_range` | VO range | Drop | Same VOs reused on the test-harness aggregate. |
| `ck_pdat_required_nr_nonnegative` | VO range | Drop | `AssignmentCount` VO. |
| `ck_pln_planning_date_reasonable` | sanity cap | Drop | `PlanningDay` constructor. |
| `ck_arf_sort_indicator_nonnegative` | VO range | Drop | `SortIndicator` VO. |
| `ck_cdnc_next_number_positive` | VO range | Drop | `DeliveryNumberCounter` VO. |

### `total_amount` re-home strategy

**Recommend (a) pure compute-on-read** via `DeliveryItem.totalAmount() : Money` value-object method. No persisted column.

Evidence: grep of `flsserver/Server/Service/Accounting/Delivery/` and `flsserver/Server/Service/Accounting/` for `TotalAmount` in WHERE / ORDER BY / sort / filter contexts returns **zero hits**. Legacy treats this as display-only; nothing queries, sorts, filters, or stores delivery-item totals. Postgres can re-introduce a generated column trivially if a sort/filter use case materialises later.

Rejected: (b) header-level `delivery.total_amount` (premature — no consumer); (c) `@PrePersist` write-back (dual-source drift, exactly what ADR 0022 forbids); (d) materialised view (heavy lift, zero current consumer). Surface (a)/(b) trade-off in Open design questions for operator confirmation since the story body left it open.

### Denylist mechanics — V5+ filename-scoped (Option A)

V4 already contains 15 real `CHECK (...)` clauses; a global deny would fail the existing test. Scope by version threshold — simplest, no allowlist machinery, still catches forward violations.

**Change to `MigrationFolderConventionsTest.no_forbidden_patterns_in_migrations()` (lines 157-177):**

```java
@Test
void no_forbidden_patterns_in_migrations() throws IOException {
    List<Pattern> forbidden = loadForbiddenPatterns();
    List<Path> migrations = listMigrations();
    var violations = new ArrayList<String>();
    for (Path m : migrations) {
        String name = m.getFileName().toString();
        // V1-V4 predate ADR 0022; their CHECK clauses are grandfathered.
        // The deny list catches forward additions only. See ADR 0022 §"Risks".
        if (name.matches("^V[1-4]__.*\\.sql$")) continue;
        String stripped = Files.readString(m, StandardCharsets.UTF_8)
                .replaceAll("(?m)--[^\\n]*", "");
        for (Pattern p : forbidden) {
            if (p.matcher(stripped).find()) {
                violations.add(m.getFileName() + " matches forbidden pattern: " + p.pattern());
            }
        }
    }
    assertThat(violations).isEmpty();
}
```

**Append to `forbidden-migration-patterns.txt`:**

```
# ADR 0022 directive 2 — business logic lives on aggregates, not the schema.
# CHECK constraints encoding state-machine values, numeric ranges, required-
# when-state-X guards, sanity caps belong in the domain (value-object
# constructors + aggregate methods). Applied to V5+ only; V1-V4 are
# grandfathered (see MigrationFolderConventionsTest version-threshold skip).
# Retained structural exceptions are documented in V<n> headers + COMMENT ON
# CONSTRAINT (e.g. ck_arv_end_after_start pins the tstzrange generated-column shape).
\bCHECK\s*\(
```

Rationale for filename scope over per-line allowlist markers: V1-V4 baseline is checksum-locked; the deny pattern would never usefully fire against them. Filename scope is one-line legible and matches the additive-going-forward mental model. The retained `ck_arv_end_after_start` doesn't trip the test because it lives in V4 (grandfathered) and V5 doesn't re-add the CHECK literal — only the `COMMENT ON CONSTRAINT` text references the name.

### Test rework (high-level — see Test plan for the full list)

- **Drop** the 10 `ReservationsBaselineIntegrationTest` methods that assert dropped CHECKs / the generated column. No `// Moved to S-022` placeholders — the dropped-rules catalogue in `## Implementation notes` is the single source of truth.
- **Split** `delivery_process_state_id_smallint_check_in_10_20_30_99` (line 243): keep the `data_type='smallint'` half as `delivery_process_state_id_is_smallint`; drop the CHECK-clause half.
- **Retain** `aircraft_reservation_end_after_start_check` (line 573) — `ck_arv_end_after_start` stays per the retain decision above.
- **Add to `MigrationFolderConventionsTest`:** `v5_drop_business_logic_checks_migration_present`, `v5_drop_business_logic_checks_migration_is_non_empty`, `forbidden_check_patterns_caught` (per AC8).
- **Add to `ReservationsBaselineIntegrationTest`:** post-V5 introspection — `delivery_no_business_logic_checks_after_v5`, `delivery_item_no_business_logic_checks_after_v5`, `all_tables_no_orphan_business_logic_checks_after_v5`, `delivery_item_total_amount_column_absent_after_v5`, `ix_dli_delivery_does_not_include_total_amount_after_v5`.
- **`TenantCatalogConsistencyTest`:** verified by direct read — carries **no** CHECK / `total_amount` assertions. The story's task line 45 is a false positive; no changes there.

### Dropped-rules catalogue (lands in `## Implementation notes` after V5 is written)

| Dropped | SQL expression | Re-home (story / aggregate method or VO) |
|---|---|---|
| `ck_dlv_process_state_in_set` | `process_state_id IN (10,20,30,99)` | S-022 — `Delivery.ProcessState` enum (`@Enumerated(EnumType.STRING)`) |
| `ck_dlv_delivery_number_positive` | `delivery_number IS NULL OR delivery_number > 0` | S-022 — `DeliveryNumber` VO constructor |
| `ck_dlv_batch_id_nonnegative` | `batch_id >= 0` | S-022 — `BatchId` VO constructor |
| `ck_dlv_booked_requires_number` | `process_state_id <> 20 OR delivery_number IS NOT NULL` | S-064 — `Delivery.book()` precondition |
| `ck_dlv_booked_requires_delivered_on` | `process_state_id <> 20 OR delivered_on IS NOT NULL` | S-064 — `Delivery.book()` precondition |
| `ck_dlv_booked_requires_recipient` | `process_state_id <> 20 OR (recipient_lastname IS NOT NULL AND recipient_firstname IS NOT NULL)` | S-064 — `Delivery.book()` recipient-snapshot precondition |
| `ck_dli_position_positive` / `_quantity_nonnegative` / `_unit_price_nonnegative` / `_discount_range` | range guards | S-022 — `Position` / `Quantity` / `Money` / `DiscountPercent` VOs |
| `ck_dcti_*` (4) | mirrors dli_* | S-022 — same VOs on `delivery_creation_test_item` |
| `ck_arv_max_30_days` | `end <= start + INTERVAL '30 days'` | S-064 — `AircraftReservation.validateDuration()` |
| `ck_pln_planning_date_reasonable` | `BETWEEN '1990-01-01' AND '2100-01-01'` | S-022 — `PlanningDay` constructor |
| `ck_pdat_required_nr_nonnegative` | `required_nr_of_assignments >= 0` | S-022 — `AssignmentCount` VO |
| `ck_arf_sort_indicator_nonnegative` | `sort_indicator >= 0` | S-022 — `SortIndicator` VO |
| `ck_cdnc_next_number_positive` | `next_number >= 1` | S-022 — `DeliveryNumberCounter` VO |
| `delivery_item.total_amount` (column) | `quantity * unit_price * (100 - discount) / 100.0` | S-022 — `DeliveryItem.totalAmount() : Money` (compute-on-read) |

### Deviations from ADR 0022

**One retention** — `ck_arv_end_after_start` on `aircraft_reservation`. Per ADR 0022 directive 2 this is flagged as a deviation requiring rationale rather than silent acceptance: it pins the *shape* of the generated `reservation_range tstzrange` column to be a non-empty range. The constructor rejects `start > end` but accepts `start == end` (empty range, valid value). Without the CHECK, an empty-range row writes successfully and GiST conflict probes silently miss it. Border-structural; the rationale is documented inline via `COMMENT ON CONSTRAINT` so `git blame` is not required.

If the operator opts to drop it (see Open design questions), the migration adds `ALTER TABLE aircraft_reservation DROP CONSTRAINT ck_arv_end_after_start;` and the catch moves to the `AircraftReservation` aggregate constructor at S-064.

### ADR conformance

- Directive 1 (working software over docs): V5 file targets ≤ 200 lines; story `## Implementation notes` gains the ~25-line catalogue — within budget.
- Directive 2 (business logic in domain): one flagged retention; all other CHECKs drop; generated column drops. No new business logic in schema.
- ADR 0019 (UUID v7): V5 contains zero column adds — no PK exposure.

## Edge cases & hidden requirements

### 1. Constraint name shorthand in current AC2/AC3 doesn't match V4

The story body's AC retain-list (`ck_dlv_process_state`) is shorthand; V4's actual constraint is `ck_dlv_process_state_in_set` (V4:440). V5 must use the exact name. The story-body shorthand is harmless documentation — does not need an AC edit unless implementation hits it. Disposition: implementer uses canonical name; story body's wording can be left as-is.

### 2. `ck_arv_end_after_start` is not purely structural

Story body wording "tsrange constructor rejects inverted ranges anyway" is partial. The constructor raises on `lower > upper` but accepts `lower = upper` (returns empty range). The CHECK closes that gap. Disposition: **surface in Open design questions** — retain (default per design notes) closes the empty-range degenerate; drop pushes that catch into the aggregate constructor.

### 3. Booked-requires-* legal-record argument is stronger than story implies

ADR 0022:61-62 explicitly names the recipient NOT-NULL-when-Booked check as a defense-in-depth candidate under OR Art. 957a. The same legal-record argument applies to `_number` (gap-free numbering) and `_delivered_on` (mandatory invoice date). The story's AC says drop without committing. Disposition: **surface in Open design questions**. Default per design notes is drop on consistency grounds; legal-record-favoring retention is a one-`COMMENT ON CONSTRAINT` swap.

### 4. Order-of-operations: only one hard sequencing constraint

`DROP INDEX ix_dli_delivery` must precede `DROP COLUMN total_amount` (INCLUDE dependency). Verified by V4 grep: `total_amount` is referenced only in the index INCLUDE + the GENERATED column itself — no other indexes, views, MVs, or rules touch it. `COMMENT ON COLUMN delivery_item.total_amount` (V4:673-674) is dropped automatically with the column. No other ordering required.

### 5. `CREATE INDEX CONCURRENTLY` would fail inside Flyway's default tx

Flyway wraps each migration in one transaction. CONCURRENTLY can't run inside a transaction block. The story's plain `CREATE INDEX` is correct because V4 is not in any populated environment yet. Document inline in V5 that future rebuilds against populated production can opt into `executeInTransaction = false` + `CREATE INDEX CONCURRENTLY` per the S-016 cutover runbook.

### 6. `forbidden-migration-patterns.txt` denylist scope problem (resolved in design notes)

`MigrationFolderConventionsTest.no_forbidden_patterns_in_migrations` (line 157-177) applies every pattern to **every** `.sql` file. A naive `CHECK \(.+\)` addition would immediately fail on V1-V4. Solution: filename-scoped skip for `V[1-4]__*.sql` (Option A in design notes). The forward-deny intent is preserved.

### 7. `TenantCatalogConsistencyTest` is a false positive in the story's task list

Story task line 45 says strip CHECK assertions from `TenantCatalogConsistencyTest.java`. Read confirmed: that file carries zero CHECK / `total_amount` / business-logic assertions. Its S-014 tests (lines 201-279) are all structural. Disposition: drop that task from the implementation checklist; no edits to `TenantCatalogConsistencyTest`.

### 8. Unsafe window between V5 and aggregate-method landing

V5 removes schema guards. The replacement aggregate methods (`Delivery.book()`, `DeliveryItem` VO constructors, `AircraftReservation.validateDuration()`) don't yet exist — S-022 as currently filed is `tenant-id-resolver` (not the Delivery aggregate body). No JPA entity for `Delivery` / `DeliveryItem` exists under `next/server/src/main/java/` today. Between V5 shipping and the aggregate landing, any direct-SQL insert (integration test fixture, migration data backfill, manual debug) can write invalid values without rejection. The provocation tests being deleted in this story (`delivery_process_state_id_999_rejected_by_check` etc.) were exactly that safety net. Mitigation: V5 ships before the `Delivery` aggregate, but no DTO / service / controller writes to `delivery` or `delivery_item` exist yet either, so the actual write surface is empty. Document the window in `## Implementation notes`; do not gate V5 on the aggregate landing.

### 9. `total_amount` re-home empirically safe

Grep of `flsserver/Server/Service/Accounting/Delivery/` + `flsserver/Server/Service/Accounting/` for `TotalAmount` in WHERE/ORDER/sort/filter contexts: zero hits. Compute-on-read at `DeliveryItem.totalAmount()` is sufficient; no persisted shape needed. Surfaced in Open design questions for operator confirmation since story-body wording left it ambiguous.

### 10. `tenant-rules.yaml` requires no regeneration

The file is human-curated (per its header) — not introspection-derived. Dropping CHECKs and one generated column changes no column names, table presences, or `operating_club_id` columns. The `fadp_dsar_retention_exempt_when: "process_state_id >= 20"` entry references the state *value*, not the CHECK. AC9 ("no new entries") is correct as written.

### 11. ADR 0022 follow-up self-close

ADR 0022:87 lists S-132 in `## Follow-ups`. Per directive 1 doc drift is a nudge — but leaving an explicit follow-up entry for a shipped story misleads. Disposition: boyscout the ADR follow-up list when V5 lands; mention in PR description. Not a separate AC.

## Security plan

(N/A — story is schema DDL only. No auth / authz / PII / tenant-isolation surface. `tenant-rules.yaml` unchanged. The denylist update is a security-adjacent test fixture but reviewed under maintainability — pattern is "stops business-logic CHECKs," not a security gate.)

## Test plan

### Tests to drop (CHECK assertions) — `ReservationsBaselineIntegrationTest`

| Test method | Line | Dropped CHECK / column | Disposition |
|---|---|---|---|
| `delivery_process_state_id_smallint_check_in_10_20_30_99` | 243 | `ck_dlv_process_state_in_set` | **Split.** Keep `data_type='smallint'` half as new `delivery_process_state_id_is_smallint`; drop the `checkConstraintDefs` half |
| `delivery_process_state_id_999_rejected_by_check` | 266 | `ck_dlv_process_state_in_set` (live INSERT) | Drop — INSERT now succeeds; aggregate unit test owns this after S-022 |
| `delivery_booked_requires_delivery_number_check` | 368 | `ck_dlv_booked_requires_number` | Drop if drop; keep if retain (operator decision) |
| `delivery_booked_requires_recipient_snapshot_check` | 395 | `ck_dlv_booked_requires_recipient` | Drop if drop; keep if retain |
| `delivery_booked_requires_delivered_on_check` | 421 | `ck_dlv_booked_requires_delivered_on` | Drop if drop; keep if retain |
| `delivery_batch_id_negative_rejected_by_check` | 448 | `ck_dlv_batch_id_nonnegative` | Drop |
| `delivery_item_total_amount_is_generated_always_stored` | 503 | `delivery_item.total_amount` column | Drop |
| `delivery_item_quantity_nonnegative_check` | 517 | `ck_dli_quantity_nonnegative` | Drop |
| `delivery_item_unit_price_nonnegative_check` | 527 | `ck_dli_unit_price_nonnegative` | Drop |
| `delivery_item_discount_in_percent_range_check` | 535 | `ck_dli_discount_range` | Drop |

Approach: delete the test methods cleanly. No `// Moved to S-NNN` placeholders — the dropped-rules catalogue in `## Implementation notes` is the single source of truth.

CHECKs in V4 with **no** existing test method (need no deletion, but the V5 introspection sweeps must assert their absence): `ck_dlv_delivery_number_positive`, `ck_dli_position_positive`, `ck_dcti_position_positive` / `_quantity_nonnegative` / `_unit_price_nonnegative` / `_discount_range`, `ck_arv_max_30_days`, `ck_pln_planning_date_reasonable`, `ck_pdat_required_nr_nonnegative`, `ck_arf_sort_indicator_nonnegative`, `ck_cdnc_next_number_positive`.

`TenantCatalogConsistencyTest`: verified by read — carries no CHECK assertions; **no changes**.

### Tests retained

- `aircraft_reservation_end_after_start_check` (line 573) — retained because `ck_arv_end_after_start` is the one structural-deviation retention (per design notes). Drop only if operator picks "drop" in Open design questions.
- `aircraft_reservation_has_generated_tstzrange_column` (line 583) — tstzrange column is structural; V5 doesn't touch it.
- All other FK / partial-UNIQUE / seed / column-type / comment tests — structural; unaffected.

### New tests V5 lands

**`MigrationFolderConventionsTest`:**

1. `v5_drop_business_logic_checks_migration_present` — mirrors lines 104-117 (V4 conventions test). Asserts a `V5__*__drop_business_logic_checks.sql` file under `db/migration` with version prefix > V4.
2. `v5_drop_business_logic_checks_migration_is_non_empty` — mirrors lines 119-133 (comment-strip + whitespace-strip non-empty).
3. `forbidden_check_patterns_caught` (per AC8):
   ```java
   @Test
   void forbidden_check_patterns_caught() throws IOException, URISyntaxException {
       String synthetic = "ALTER TABLE foo ADD CONSTRAINT ck_bar CHECK (x > 0);";
       URL url = getClass().getClassLoader()
               .getResource("security/forbidden-migration-patterns.txt");
       List<Pattern> forbidden = new ArrayList<>();
       try (var lines = Files.lines(Paths.get(url.toURI()), StandardCharsets.UTF_8)) {
           lines.forEach(raw -> {
               String line = raw.strip();
               if (line.isEmpty() || line.startsWith("#")) return;
               forbidden.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
           });
       }
       String stripped = synthetic.replaceAll("(?m)--[^\\n]*", "");
       assertThat(forbidden)
               .as("forbidden-migration-patterns.txt must catch `CHECK (...)` in new migrations per ADR 0022 directive 2")
               .anyMatch(p -> p.matcher(stripped).find());
   }
   ```
   Re-loads the fixture in the test to avoid changing the visibility of the private `loadForbiddenPatterns()` helper.

**`ReservationsBaselineIntegrationTest`** — post-V5 introspection:

4. `delivery_no_business_logic_checks_after_v5`:
   ```sql
   SELECT conname FROM pg_constraint
   WHERE conrelid = 'delivery'::regclass AND contype = 'c'
     AND conname NOT IN ( <retained-list-if-any> );
   ```
   Assert empty. `<retained-list-if-any>` = the three `ck_dlv_booked_requires_*` names if operator picks retain; otherwise omit the clause.
5. `delivery_item_no_business_logic_checks_after_v5`:
   ```sql
   SELECT conname FROM pg_constraint
   WHERE conrelid = 'delivery_item'::regclass AND contype = 'c';
   ```
   Assert empty.
6. `all_tables_no_orphan_business_logic_checks_after_v5`:
   ```sql
   SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
   WHERE conrelid = ANY(ARRAY[
       'accounting_rule_filter', 'planning_day_assignment_type',
       'planning_day', 'aircraft_reservation',
       'delivery_creation_test_item', 'club_delivery_number_counter'
     ]::regclass[])
     AND contype = 'c'
     AND conname NOT IN ('ck_arv_end_after_start');
   ```
   Assert empty.
7. `delivery_item_total_amount_column_absent_after_v5`:
   ```sql
   SELECT 1 FROM information_schema.columns
   WHERE table_schema='public' AND table_name='delivery_item' AND column_name='total_amount';
   ```
   Assert `rs.next() == false`.
8. `ix_dli_delivery_does_not_include_total_amount_after_v5`:
   ```sql
   SELECT indexdef FROM pg_indexes
   WHERE schemaname='public' AND tablename='delivery_item' AND indexname='ix_dli_delivery';
   ```
   Assert index exists and `indexdef` does not contain `total_amount`.

### Denylist deny-pattern test design

The existing global test (`no_forbidden_patterns_in_migrations`) gets the V1-V4 skip per design notes. Verify:
- V1-V4 still pass the **existing** denylist (PASSWORD / GRANT / etc.) — unchanged behavior.
- A synthetic V5+ migration with a `CHECK (...)` literal triggers the deny (covered by `forbidden_check_patterns_caught` + the global test's V5+ scope).

### Parity strategy

`parity_test: none` — schema-shape change with no observable user-facing behavior delta. Legacy enforced these rules in C# service-layer code (`DeliveryService.cs`, `AccountingRuleFilterService.cs`); new-stack moves them to aggregate methods. Behavioral parity is tested at S-022 / S-064 when those methods land — not here.

### CI execution

- Build / test command: `bash -l -c "cd /c/Users/roman/IdeaProjects/fls/next/server && ./gradlew test"`.
- Migration-folder tests are static-asset (no Docker).
- `ReservationsBaselineIntegrationTest` is gated on Testcontainers (`SharedPostgresContainer#available`); Docker is present in this environment per CLAUDE.md.
- No CI workflow file changes.

### Coverage delta

~10 tests deleted (CHECK assertions in `ReservationsBaselineIntegrationTest`); ~8 tests added (2 V5 conventions, 1 denylist catch, 5 post-V5 introspection). Net ≈ neutral. Equivalent domain coverage lands at S-022 / S-064 as aggregate-method unit tests; no real coverage gap because the enforcement mechanism itself moves layers.

## Performance plan

### Migration lock profile

| Operation | Lock | Duration | Concurrent-DML impact |
|---|---|---|---|
| `ALTER TABLE delivery DROP CONSTRAINT ck_dlv_*` ×3-6 | AccessExclusive on `delivery` | instant (catalog-only) | writers blocked microseconds |
| `ALTER TABLE delivery_item DROP CONSTRAINT ck_dli_*` ×4 | AccessExclusive | instant | same |
| `ALTER TABLE delivery_creation_test_item DROP CONSTRAINT ck_dcti_*` ×4 | AccessExclusive | instant | same |
| `ALTER TABLE aircraft_reservation DROP CONSTRAINT ck_arv_max_30_days` | AccessExclusive | instant | same |
| `ALTER TABLE planning_day DROP CONSTRAINT ck_pln_planning_date_reasonable` | AccessExclusive | instant | same |
| `ALTER TABLE planning_day_assignment_type DROP CONSTRAINT ck_pdat_required_nr_nonnegative` | AccessExclusive | instant | same |
| `ALTER TABLE accounting_rule_filter DROP CONSTRAINT ck_arf_sort_indicator_nonnegative` | AccessExclusive | instant | same |
| `ALTER TABLE club_delivery_number_counter DROP CONSTRAINT ck_cdnc_next_number_positive` | AccessExclusive | instant | same |
| `DROP INDEX ix_dli_delivery` | AccessExclusive on index | instant | table-level DML unblocked once index gone |
| `ALTER TABLE delivery_item DROP COLUMN total_amount` | AccessExclusive on `delivery_item` | instant (`attisdropped=true` metadata-only; no rewrite) | writers blocked microseconds |
| `CREATE INDEX ix_dli_delivery ON delivery_item (delivery_id) INCLUDE (article_id, article_number, quantity, unit_price)` | ShareLock on table | proportional to row count; empty at S-132 time → sub-second | writers blocked for build; readers unaffected |

Recommend `SET lock_timeout = '5s'` at the top of V5 — fails fast if a stuck reader queues AccessExclusive.

### Index rebuild concurrency

Plain `CREATE INDEX` is correct for S-132. V4 has not shipped to any populated environment; build is sub-second. `CREATE INDEX CONCURRENTLY` would require `-- ##executeInTransaction off` (Flyway 11 directive) — splits atomicity, leaves an `INVALID` index on failure. Reserve CONCURRENTLY for S-016's cutover runbook on populated data, document inline in V5 for future maintainers.

### `ix_dli_delivery` size delta

Per-entry footprint (B-tree leaf):
- **Before:** key (UUID 16B) + INCLUDE (UUID 16 + VARCHAR(50) ~30 + NUMERIC ~10 + NUMERIC ~10 + NUMERIC ~10) + ~28B tuple overhead ≈ **~104 B/entry**
- **After:** drops the 10B `total_amount` payload ≈ **~94 B/entry**
- Delta: ~10% smaller. At 1M rows: ~10 MB saved (and ~10 MB less peak during rebuild). Negligible at AlpenFlight scale; tracked as marker that the change isn't free.

### `total_amount` query impact

Legacy grep of `flsserver/Server/Service/Accounting/Delivery/` + `flsserver/Server/Service/Accounting/` for `TotalAmount` in WHERE/ORDER/sort/filter: **zero hits**. Display-only field. Dropping it from the INCLUDE clause never costs an index-only-scan-to-heap fallback for any existing query. DTO assembly recomputes from `quantity * unit_price * (100 - discount) / 100.0` on already-fetched heap pages — no extra I/O.

If a future "sort by amount" use case lands: do not re-add the GENERATED column. Either compute-on-fly in Java (free at < 1M items per club) or persist a denormalised `delivery.total_amount_chf` populated by `Delivery.book()` (aligns with the legal-record snapshot pattern). Revisit only on demand.

### Generated column drop — table bloat

`DROP COLUMN` marks the column dropped in `pg_attribute.attisdropped`; does **not** rewrite the table. Pre-existing rows keep ~10B of payload pinned until `VACUUM FULL` / `pg_repack`. At S-132 time the table is empty in every environment, so bloat is zero. Post-cutover projection at 10M rows: ~100 MB pinned across the whole table — rounding error against the ~250B row width, trivial to reclaim at the next maintenance window. **Do not** schedule `VACUUM FULL` from V5 (AccessExclusive for the rewrite duration would dominate the migration's lock profile). Document for next `pg_repack` window in implementation notes.

### CHECK evaluation cost (retained vs aggregate-method)

Nanoseconds either direction. CHECK is a constant predicate over fixed columns; aggregate-method validation runs on the same JVM already constructing the entity. Invisible to end-to-end latency. The retain decisions for `ck_arv_end_after_start` + the Booked-requires-* trio are correctness / defense-in-depth calls, not perf calls.

### Budget

- **Migration runtime:** empty tables → end-to-end < 1 second for all 17+ DROP CONSTRAINTs + DROP INDEX + DROP COLUMN + CREATE INDEX. Acceptance threshold: V5 Testcontainers application < 500 ms wall-clock delta vs V4-only baseline.
- **Runtime latency post-S-132:** no production load yet; downstream aggregate-method validation at S-022/S-064 adds microseconds to user-triggered booking already budgeted sub-second.
- **Future rebuild on populated production:** informational only — at 1M `delivery_item` rows, non-concurrent `CREATE INDEX` runs in seconds and blocks writers for that window; CONCURRENTLY runs ~2× longer but is online. S-016 cutover runbook concern, not S-132.

## Open design questions

### Q1 — Retain or drop `ck_arv_end_after_start`?

**Default (design-notes recommendation):** retain as a documented deviation. Pins the generated `reservation_range tstzrange` shape — `tstzrange(start, end, '[)')` rejects `start > end` but accepts `start == end` (returns an empty range, valid value the GiST conflict probe silently misses). Without the CHECK, an empty-range reservation never blocks another reservation → effectively no-op rows.

**Alternative:** drop and let `AircraftReservation` constructor at S-064 enforce `end > start`. Aligns with directive-2 purity (no business logic in schema), but pushes the empty-range catch out of the persistence layer.

**Operator decision needed:** retain (default; one `COMMENT ON CONSTRAINT` documents the rationale inline) or drop (cleaner per directive 2; aggregate method becomes the only catch).

### Q2 — Retain or drop the Booked-requires-* trio?

The three CHECKs (`ck_dlv_booked_requires_number`, `_delivered_on`, `_recipient`) enforce "Booked invoices have a number, a delivered-on date, and a recipient snapshot." ADR 0022:61-62 names this as the OR Art. 957a defense-in-depth exception zone (legal record).

**Default (design-notes recommendation):** drop all three. Gap-free numbering is already structurally enforced by `ux_dlv_club_number_partial`; keeping three half-state-machine CHECKs scatters policy across layers and confuses future readers about where the rule lives. Aggregate-method `Delivery.book()` at S-064 enforces all three preconditions.

**Alternative (requirements-engineer-preferred):** retain all three with `COMMENT ON CONSTRAINT … 'ADR 0022 defense-in-depth per OR Art. 957a'` annotations. The legal-record guarantee is regulatory, not policy; defense-in-depth catches direct-SQL writes that bypass the aggregate during the unsafe window before S-064 lands.

**Operator decision needed:** drop all three (purer directive 2; aggregate-method only catch) or retain all three (defense-in-depth for the legal record; clearly marked deviation).

### Q3 — `total_amount` re-home: confirm compute-on-read?

**Default (design-notes recommendation):** pure compute-on-read via `DeliveryItem.totalAmount() : Money` at S-022. No persisted column. Evidence: legacy grep returns zero `TotalAmount` filter/sort hits — display-only field, no consumer depends on persisted shape.

**Alternative:** persist denormalised `delivery.total_amount_chf` populated by `Delivery.book()` for invoice-print parity / future "sort by amount" use cases.

**Operator decision needed:** confirm (a) compute-on-read as default (per story body, design notes, and legacy evidence) or commit to (b) persist on `delivery` header. Trivially reversible in a later V<n> migration if (a) proves insufficient; choosing (a) now is the cheapest path.

<!-- modernize-refine: end -->

## Implementation notes

### Scope pivot — in-place V1-V4 cleanup, no V5

The frontmatter ACs describe a corrective V5 migration. Mid-implementation the operator widened the scope to **clean up every business-logic CHECK in V1-V4 in place — no grandfather exceptions** (rather than a forward-only V5 that leaves V1-V3 CHECKs in source but absent at migration time). The change:

- Editing V1-V4 in place breaks the Flyway-checksum-locked-after-shipping convention, but only Testcontainers consume the baseline today — no populated environment pays a cost.
- The denylist applies globally (no `^V[1-4]__.*\.sql$` filename skip in `MigrationFolderConventionsTest.no_business_logic_check_constraints_in_migrations`).
- V5 is not shipped. The story title "V5 — drop business-logic CHECKs" is retained for the audit trail but the actual change is in V2/V3/V4.

### Operator decisions on refinement Open design questions

| # | Question | Operator pick | Refinement default |
|---|---|---|---|
| Q1 | Retain or drop `ck_arv_end_after_start`? | **Drop** | Retain (deviation) |
| Q2 | Retain or drop the Booked-requires-* trio? | **Drop all three** | Drop all three |
| Q3 | `total_amount` re-home: compute-on-read vs persist? | **Compute-on-read** | Compute-on-read |

Net: zero retained CHECKs from V4. The empty-tstzrange degenerate (lower=upper produces a GiST-invisible empty range) catches at `AircraftReservation` constructor + `validateDuration()` at S-064.

### Retentions across V1-V4 (3 explicitly carved out)

Three CHECKs survive — all input-shape / security defense-in-depth that ADR 0022 didn't explicitly enumerate under the OR Art. 957a exception. Each is paired with a co-located `COMMENT ON CONSTRAINT … 'ADR 0022 retained: …'` marker; the denylist test allow-lists by name iff the marker is present.

| Constraint | Table | Migration | Rationale |
|---|---|---|---|
| `ck_person_email_private_shape` | `person` | V2 | Input-shape defense-in-depth — direct-SQL writes bypassing the `Email` VO must not silently persist malformed e-mail. |
| `ck_person_email_business_shape` | `person` | V2 | Same rationale; pairs with `ck_person_email_private_shape`. |
| `ck_aircraft_spot_link_https` | `aircraft` | V3 | A10 SSRF defense-in-depth — a non-`https://` URL slipping past the `SpotLink` VO via direct SQL must not persist; the URL is later rendered as a clickable link in the UI. |

### Dropped-rules catalogue

Where each dropped invariant re-homes. Aggregate methods + value-object constructors land at the indicated downstream story.

| Migration | Dropped (constraint or column) | Re-homes at |
|---|---|---|
| V2 | `ck_country_iso2_upper` / `ck_country_iso3_upper` | `Iso2Code` / `Iso3Code` VOs (S-022) |
| V2 | `ck_language_bcp47` | `LanguageCode` VO (S-022) |
| V2 | `ck_person_birthday_not_future` | `Birthday` VO (S-022) |
| V3 | `ck_fcbt_at_least_one_flag` | `FlightCostBalanceType` constructor (S-058) |
| V3 | `ck_location_icao_uppercase` | `IcaoCode` VO (S-068) |
| V3 | `ck_location_latitude_shape` / `ck_location_longitude_shape` | `Latitude` / `Longitude` VOs (S-068) |
| V3 | `ck_flight_type_min_seats_positive` | `AircraftSeatsCount` VO (S-022) |
| V3 | `ck_aircraft_year_of_manufacture_sane` | `Year` VO (S-058) |
| V3 | `ck_aircraft_mtom_sane` | `Mtom` VO (S-058) |
| V3 | `ck_aircraft_nr_of_seats_positive` | `SeatsCount` VO (S-058) |
| V3 | `ck_aircraft_flarm_id_regex` | `FlarmId` VO (S-058) |
| V3 | `ck_flight_aircraft_type_discriminator` | `FlightAircraftType` enum (`@Enumerated(STRING)`) + `Flight.linkTow()` (S-058) |
| V3 | `ck_flight_tow_not_self` / `ck_flight_tow_only_for_glider` | `Flight.linkTow()` precondition (S-058) |
| V3 | `ck_flight_ldg_at_or_after_start` / `ck_flight_block_end_at_or_after_start` | `TimeWindow` VO + `Flight` constructor (S-058) |
| V3 | `ck_flight_date_reasonable` | `FlightDate` VO (S-058) |
| V3 | `ck_flight_nr_of_ldgs_nonnegative` / `_on_start_le_total` | `LandingCount` VO + `Flight.recordLanding()` (S-058) |
| V3 | `ck_flight_engine_counters_monotonic` | `EngineCounterSeconds` VO (S-058) |
| V3 | `ck_flight_nr_of_passengers_nonnegative` | `PassengerCount` VO (S-058) |
| V3 | `ck_flight_start_position_range` | `StartPosition` VO (S-058) |
| V3 | `ck_flight_start_runway_shape` / `_ldg_runway_shape` | `RunwayCode` VO (S-058) |
| V3 | `ck_flight_coupon_number_shape` | `CouponNumber` VO (S-058) |
| V3 | `ck_flight_crew_nr_of_ldgs_nonnegative` / `_nr_of_starts_nonnegative` | `LandingCount` / `StartCount` VOs (S-058) |
| V3 | `ck_aas_valid_to_at_or_after_valid_from` | `AircraftStatePeriod` VO (S-058) |
| V3 | `ck_aoc_at_date_time_not_too_future` + 7 `*_nonnegative` | `AircraftOperatingCounter` VOs + constructor (S-058) |
| V4 | `ck_dlv_process_state_in_set` | `Delivery.ProcessState` enum (`@Enumerated(STRING)`) (S-022) |
| V4 | `ck_dlv_delivery_number_positive` | `DeliveryNumber` VO (S-022) |
| V4 | `ck_dlv_batch_id_nonnegative` | `BatchId` VO (S-022) |
| V4 | `ck_dlv_booked_requires_number` / `_delivered_on` / `_recipient` | `Delivery.book()` preconditions (S-064) |
| V4 | `ck_dli_position_positive` / `_quantity_nonnegative` / `_unit_price_nonnegative` / `_discount_range` | `Position` / `Quantity` / `Money` / `DiscountPercent` VOs (S-022) |
| V4 | `ck_dcti_*` (4) | same VOs on `delivery_creation_test_item` (S-022) |
| V4 | `ck_arv_end_after_start` | `AircraftReservation` constructor (S-064) — pins the empty-range degenerate (`lower=upper`) the GiST conflict probe silently misses |
| V4 | `ck_arv_max_30_days` | `AircraftReservation.validateDuration()` (S-064) |
| V4 | `ck_pln_planning_date_reasonable` | `PlanningDay` constructor (S-022) |
| V4 | `ck_pdat_required_nr_nonnegative` | `AssignmentCount` VO (S-022) |
| V4 | `ck_arf_sort_indicator_nonnegative` | `SortIndicator` VO (S-022) |
| V4 | `ck_cdnc_next_number_positive` | `DeliveryNumberCounter` VO (S-064) |
| V4 | `delivery_item.total_amount` (GENERATED column) | `DeliveryItem.totalAmount() : Money` compute-on-read (S-022); `ix_dli_delivery` INCLUDE clause re-shaped without `total_amount` |

### AC mapping

The frontmatter ACs were written assuming a forward-only V5 migration. Mapping how each was satisfied by the in-place pivot:

- **AC1 / AC2** (V5 drops business-logic CHECKs incl. enumerated list) — satisfied in spirit: every named CHECK enumerated in the AC drops in V4 in place. Plus the pivot added V2 (4 drops) and V3 (~33 drops) to the same change set.
- **AC3** (retentions) — pivoted: zero retentions in V4 (Q1/Q2 dropped per operator). Retentions exist at V2 (`ck_person_email_*_shape`) and V3 (`ck_aircraft_spot_link_https`) for non-V4 input-shape concerns the original AC didn't anticipate.
- **AC4** (`total_amount` drop + `DeliveryItem.totalAmount()` re-home) — satisfied: column dropped, re-home contracted at S-022 per Q3.
- **AC5** (`ix_dli_delivery` INCLUDE without `total_amount`) — satisfied: index recreated in V4 directly (no V5 intermediary).
- **AC6** (forbidden-migration-patterns entry with allow-list for retentions) — re-shaped: the CHECK pattern lives in a dedicated Java test (`no_business_logic_check_constraints_in_migrations`) with a `COMMENT ON CONSTRAINT … 'ADR 0022 retained: …'` allow-list mechanism. The flat-pattern denylist file gets a documentation block but not a regex line (pattern moved into the dedicated test).
- **AC7** (test deletions in `ReservationsBaselineIntegrationTest` + `TenantCatalogConsistencyTest`) — satisfied for `ReservationsBaselineIntegrationTest`; `TenantCatalogConsistencyTest` had **no** CHECK assertions to begin with (false positive in the original task list — confirmed by direct read).
- **AC8** (`MigrationFolderConventionsTest extends with forbidden_check_patterns_caught`) — satisfied semantically by `no_business_logic_check_constraints_test_catches_synthetic_violation`, which guards the same regex-bitrot concern.
- **AC9** (tenant-rules.yaml unchanged) — confirmed: `git diff main...HEAD -- next/database/tenant-rules.yaml` is empty.

### Test impact

Deleted ~20 CHECK-assertion test methods + 4 live SQLSTATE-23514 provocations across `IdentityBaselineIntegrationTest`, `FlightBaselineIntegrationTest`, `ReservationsBaselineIntegrationTest`. Added 5 post-baseline introspection tests (3 retentions present, schema CHECK-free otherwise, `total_amount` column absent, `ix_dli_delivery` INCLUDE clean) + 1 source-file CHECK denylist scan + 1 sanity gate. Net coverage delta: neutral. Domain coverage of the moved invariants lands at S-022 / S-058 / S-064 / S-068 when the aggregate methods + value objects ship.

### ADR 0022 follow-up self-close

Worth marking S-132 as done in ADR 0022's `## Follow-ups` list during the next docs sweep (not in this PR — boyscout for a future touch). Cited by story-ID, not SHA.
