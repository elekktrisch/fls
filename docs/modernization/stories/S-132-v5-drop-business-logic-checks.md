---
id: S-132
title: V5 — drop business-logic CHECKs + generated total_amount from V4 per ADR 0022
epic: E-02
status: todo
depends_on: [S-014]
acceptance:
  - V5 migration drops business-logic CHECK constraints from V4 that encode domain rules (state machine values, range guards, required-when-state-X, sanity caps, calculated values). Aggregate-method enforcement lands at S-022/S-064. Schema retains only structural invariants (PKs, FKs, structural NOT NULL, identity-bearing partial UNIQUE, performance indexes) per [ADR 0022 directive 2](../adrs/0022-modernization-primary-directives.md).
  - Specific drops — `ck_dlv_process_state_in_set` (state-machine value set; aggregate `Delivery.transitionTo()` owns); `ck_dlv_delivery_number_positive` (range; value object owns); `ck_dlv_batch_id_nonnegative` (range; value object owns); `ck_dlv_booked_requires_number` / `ck_dlv_booked_requires_delivered_on` / `ck_dlv_booked_requires_recipient` (state-machine guards; `Delivery.book()` owns); `ck_dli_quantity_nonnegative` / `ck_dli_unit_price_nonnegative` / `ck_dli_discount_range` / `ck_dli_position_positive` (value-object ranges); `ck_dcti_*` (snapshot ranges); `ck_arv_max_30_days` (operational cap; `AircraftReservation` aggregate owns); `ck_pln_planning_date_reasonable` (sanity; aggregate owns); `ck_pdat_required_nr_nonnegative` (value-object range); `ck_arf_sort_indicator_nonnegative` (value-object range); `ck_cdnc_next_number_positive` (value-object range).
  - Retain — `ck_arv_end_after_start` (structural for the generated `reservation_range tstzrange`; tsrange constructor rejects inverted ranges anyway), `ck_dlv_process_state` if reframed as defense-in-depth for the legal-record invariant (OR Art. 957a); decision per refinement.
  - Drop `delivery_item.total_amount NUMERIC(14,4) GENERATED ALWAYS AS (...) STORED`. Calculation moves to `DeliveryItem.totalAmount()` method (or value-object) at S-022. V5 migration drops the generated column; service-layer computes on read (and on persistence if needed for audit / reporting).
  - V5 migration includes a `forbidden-migration-patterns.txt` entry for `CHECK \(.+\)` (with documented allow-list for the structural retentions) so future implementers can't accidentally re-add business-logic CHECKs. The deny list cites ADR 0022 in its rationale block.
  - Tests in `ReservationsBaselineIntegrationTest` that asserted the dropped CHECKs are removed; replaced (where applicable) with aggregate-method unit tests at S-022. `MigrationFolderConventionsTest` extends with `forbidden_check_patterns_caught` test.
  - `tenant-rules.yaml` carries no new entries (unchanged shape); story body documents the schema-shape shift in `## Implementation notes`.
estimate: S
adr_refs: [0018, 0019, 0022]
parity_test: none
refined: false
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

- [ ] Read [ADR 0022](../adrs/0022-modernization-primary-directives.md) end-to-end. Confirm the "stays in schema" list (PKs, FKs, structural NOT NULL, identity-bearing partial UNIQUE, indexes) covers what V5 must preserve.
- [ ] List every CHECK in V4 (`grep '^\s*CONSTRAINT ck_' next/server/src/main/resources/db/migration/V4__reservations_planning_accounting.sql`). Per-CHECK decision: drop (default) / retain with rationale (rare).
- [ ] Decide on `ck_arv_end_after_start` — structurally needed for generated tstzrange? Or drop + let the generated column blow up at INSERT?
- [ ] Decide on Booked-requires-* CHECKs — are they OR Art. 957a defense-in-depth (retain) or domain rules (drop)? Refine at story-refinement time; default to drop unless legal-record argument lands.
- [ ] Write V5 migration: `ALTER TABLE … DROP CONSTRAINT …` for each dropped CHECK + `ALTER TABLE delivery_item DROP COLUMN total_amount` + (if total_amount is needed for query/reporting) a different storage strategy (read-side computed, or materialised view, or stored-on-DeliveryItem.book() via aggregate method).
- [ ] Update `next/server/src/test/resources/security/forbidden-migration-patterns.txt` — add CHECK-pattern denylist with documented exceptions block.
- [ ] Strip the now-removed CHECK assertions from `ReservationsBaselineIntegrationTest.java` + `TenantCatalogConsistencyTest.java`. Replace with `// Moved to aggregate at S-022 — see <reference>` comments.
- [ ] Run full server test suite green.
- [ ] Story `## Implementation notes` documents the schema-shape shift, the rationale, and the test deletions.

## Notes

This is the first story to be filed *because* ADR 0022 landed. It's a deliberate guinea-pig for the directive's enforcement story — if the workflow can't easily ship this kind of "remove the business logic from schema" change, the directive isn't load-bearing.

Estimate: S. A focused 2-4 hour pass; mostly mechanical edits + test deletions.

Refinement note: `## Open design questions` should surface the Booked-requires-* CHECKs and the `ck_arv_end_after_start` question for the operator before drop decisions are baked in.
