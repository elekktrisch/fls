---
id: J-10
title: Deliveries — read-only screen (clean-seed)
epic: E-09
status: in_progress
started_at: 2026-06-15
journey0: false
carved: true
depends_on: [J-9]
rolls_up: [S-078]
acceptance:
  # ≥feature — the /deliveries read surface: invoice drafts rendered tenant-scoped, read-only (clean-seed)
  - "[happy] /deliveries list renders the club's deliveries (delivery number, recipient, batch, state) tenant-scoped, paged + sortable; reachable via the masterdata nav (spec ENTERS via the dropdown)"
  - "[happy] view a delivery: read-only line items (position/article/itemText/quantity/unitType), the 9-field frozen recipient snapshot, and the linked flight info (all read-only — no edit/book/delete actions this iteration)"
  - "[edge] cross-tenant GET of another club's delivery → 404 (tenant isolation)"
screen: /deliveries (list + view, READ-ONLY) — replacing legacy masterdata/deliveries/ (read path)
headless_pulled_in: none — read-only clean-seed screen; the Delivery migration is deferred to J-10b (needs J-11's ARTICLE first), no engine/write path this iteration
migration: N/A — Delivery migration deferred to J-10b (needs J-11 ARTICLE first)
parity_test: alpenflight/web/e2e/tests/real-idp/deliveries-parity.spec.ts
mock_test: alpenflight/web/e2e/tests/accounting/   # per-push mock-e2e runs ONLY these; prior journeys' mock specs run at the §4 gate + nightly
adr_refs: [0008, 0020, 0022, 0027]
---

## Context

The invoice-draft read surface. A **Delivery** is the immutable output of the J-9 rules engine — the
billing line items + frozen recipient snapshot for a flight. This iteration ships the **read-only**
`/deliveries` screen over **clean-seed** data. The **Delivery migration is deferred to J-10b** (operator
decision, 2026-06-15): migrating Delivery requires migrating ARTICLE (`DeliveryItem.article_id` is NOT
NULL RESTRICT), but ARTICLE is **J-11's** entity — binding it unscoped here orphans non-migrated-club
articles in the fanout export (`fk_article_operating_club_id` 23503), failing the whole bundle ingest and
regressing EVERY migration journey's parity. So J-10b carries the Delivery migration, after J-11 migrates
Articles. The **write side** — creating, booking (gap-free numbering), deleting, the state machine, and
persisting fresh engine output — is also in **J-10b**. Downsized at operator request to free capacity for
the boyscout backlog: this journey runs **tech-debt-heavy** (thin read feature + extra `_BOYSCOUT`
burndown).

## Spec must assert

Grounded in the legacy READ path — `flsweb/src/masterdata/deliveries/` (list `DeliveriesEditController.js`,
view `deliveries-edit.html`) + `flsserver` `DeliveriesController.cs` GET endpoints + `Delivery.cs`:

- **The read screen** — list (delivery_number, recipient, batch_id, state; paged, sort batch desc /
  recipient asc, tenant-scoped) + view (read-only line items, the frozen 9-field recipient snapshot,
  read-only flight link). **No edit/book/delete actions** this iteration — the screen is a viewer.
  Line items are engine output, never hand-editable.
- **State is displayed, not transitioned** — `process_state_id` 10 Prepared / 20 Booked / 30 Error / 99
  Cancelled renders as a column/badge; the screen does not mutate it (transitions are J-10b).
- **Clean-seed is the data source** — the screen renders deliveries materialized via the `DeliverySeeder`
  Gradle task (mock-IdP) / `seedDelivery` (real-idp), a Delivery + line items + the frozen 9-field
  recipient snapshot under the test tenant. The MIGRATION of legacy deliveries is deferred to **J-10b**
  (needs J-11 ARTICLE first), so there is no migrated done-bar this iteration.
- Dispatch `legacy-oracle` at ship time only if the read DTO shape / list-projection columns can't be
  derived from `DeliveriesController.cs` + the design (none exists — inherit legacy parity).

## Notes

**Scope — READ-ONLY, CLEAN-SEED this iteration (operator downsizing, 2026-06-15).** J-10 = the Delivery
read path over clean-seed; **J-10b** carries the migration AND the write side. Concretely:
- **J-10 builds:** the `Delivery` + `DeliveryItem` JPA entities (read-mapped, @TenantId), a read
  `DeliveryRepository`, the GET list/view REST resource, the read-only `/deliveries` SPA screen, and the
  clean-seed `DeliverySeeder` / `seedDelivery` Gradle tasks that materialize the read fixtures.
- **J-10b builds (deferred):** the **Delivery + DeliveryItem migration binding** (the migrated done-bar —
  needs J-11's ARTICLE migrated first, see below), PLUS the write behavior on the `Delivery` aggregate —
  create, **book()** with the gap-free `delivery_number` counter (`t_club_delivery_number_counter`, atomic
  `UPDATE…RETURNING`), delete (resets the linked flight to Locked), the Prepared→Booked terminal state
  machine + 409-on-Booked, and the `AccountingDeliveryEngine → Delivery` persist orchestration. Plus the
  write endpoints + the screen's book/delete actions.

**Why the migration deferred (the ARTICLE dependency, operator 2026-06-15).** `DeliveryItem.article_id` is
NOT NULL with a RESTRICT FK to `t_article` — migrating Delivery requires migrating ARTICLE. But ARTICLE is
**J-11's** entity. Binding ARTICLE unscoped here (as T-05 did) makes the fanout export orphan every
non-migrated-club article → `fk_article_operating_club_id` 23503 → the whole bundle ingest fails → EVERY
migration journey's parity (J-0c/J-5/J-6/J-8/J-9 + J-10) regresses. So the Delivery migration rides
**J-10b**, AFTER J-11 migrates Articles in a tenant-scoped way. The T-05/T-11/T-12/T-13 migration parts
(the ARTICLE/DELIVERY/DELIVERY_ITEM mapper bindings, the legacy seed, the fanout wiring, the producer-SELECT
fix) are **reverted** in T-14; the three entities are back in `MapperBindingContractTest.KNOWN_UNBOUND` so
the fanout never exports them. **Use the freed capacity for additional `_BOYSCOUT` burndown** (the ≤70%
tech-debt window).

**Going-in substrate (V4, verified):** `t_delivery` / `t_delivery_item` / `t_club_delivery_number_counter`
schemas exist (`process_state_id` SMALLINT 10/20/30/99; `ux_dlv_club_number_partial`; FK `flight_id`,
`recipient_person_id` SET NULL, `article_id` RESTRICT). `DeliveryMapper` + `DeliveryItemMapper` are
authored + registered in `migration-bundle` but **UNBOUND (no `MapperLegacyBindings` entry — back in
KNOWN_UNBOUND) and migration-only**; J-10 builds the server-side read half (@Entity / repo / service /
controller); J-10b binds the migration + builds the write half.

**Seam hints (non-binding, one seam each):** the `Delivery` + `DeliveryItem` JPA entities (read-mapped);
the read `DeliveryRepository` (@TenantId, JPA-first per ADR 0027); the delivery GET resource (list + view,
tenant 404); the read-only `/deliveries` SPA screen (list + view); the clean-seed `DeliverySeeder`; the
clean-seed parity spec.

## Tasks

Per do-ship §2 (one seam each). J-10 ships the read-only `/deliveries` screen over **clean-seed**; it runs
**tech-debt-heavy** (operator downsizing → fold `_BOYSCOUT` riders generously). The Delivery migration is
deferred to J-10b (needs J-11 ARTICLE first), so T-14 reverts the migration parts of T-05/T-11/T-12/T-13.

- [x] **T-01** — spec stub `e2e/tests/accounting/deliveries.spec.ts` (read-only: list / view / migrated / cross-tenant 404; ENTERS via the Masterdata nav dropdown) + scaffold the per-journey gallery page (current-journey-only). *(e2e + gallery)*
- [x] **T-02** — gate scoping: J-10 `mock_test`/`parity_test` frontmatter so per-push runs only J-10's specs; prior journeys run mock-IdP. *(ci.yml + frontmatter)*
- [x] **T-03** — `Delivery` + `DeliveryItem` JPA entities (read-mapped, `@TenantId`; `process_state` enum 10/20/30/99 display-only; frozen recipient VO; the DeliveryItem child + position) + read `DeliveryRepository` (tenant-scoped paged query + find-by-id) + domain/repo tests. *(accounting/domain + infra)*
- [x] **T-04** — the delivery READ resource: `DeliveriesService` (paged list + view) + DTOs (`DeliveryOverview` list-row, `DeliveryDetail`) + `DeliveriesController` (GET list/page + GET `/{id}`) + ControllerAuditCoverage + cross-tenant 404 IT. *(accounting/application + web)*
- [x] **T-05** — bind the `Delivery` + `DeliveryItem` migration mappers (`MapperLegacyBindings`) + the legacy Delivery/DeliveryItem seed + the **real-producer collision/orphan round-trip IT**: delivery_number parse-collision → 23505 on `ux_dlv_club_number_partial`; `delivery_item.article_id` / `delivery.flight_id` RESTRICT orphan; `recipient_person_id` SET NULL — reds in `check` (minutes), not the ~20-min fanout. *(migration-bundle + IT)* — **migration parts REVERTED by T-14** (bindings + collision IT removed; entities back in KNOWN_UNBOUND).
- [x] **T-06** — FE read-only screen: orval regen + `features/accounting/deliveries` route + store + list page (paged/sortable, tenant-scoped) + view page (read-only line items + frozen recipient + flight link) + **nav entry under the Masterdata dropdown (chrome-reachable)**. *(web)*
- [x] **T-07** — *(BLOCKING rider, J-9-retro)* fix the migration-bundle-ingest 409: `ensureSharedMigrationBundle` polls the deployment to `COMPLETED` after ingest + treats `409 DEPLOYMENT_EXISTS` as reuse-`existingDeploymentId` (never re-ingest/throw). Unblocks the real-bundle parity for J-0c/J-5/J-6 + J-10. *(e2e real-idp helper)*
- [x] **T-08** — *(BLOCKING rider, J-9-retro)* resolve the J-9 migrated done-bar **article-5001** (verify T-07's deployment-timing fix resolves it; else fix the migrated FlightTime-filter availability / the TestClub export inputs) + strengthen the migrated assertion toward bit-exact. *(e2e real-idp spec + maybe TestClub seed)*
- [x] **T-09** — *(boyscout fold, ≤70% window)* surface-touching `_BOYSCOUT` riders: the `[DOC-DRIFT]` `FilterConfig.java` stale javadoc; per-touch IT-seeding conversion on the ITs this journey adds; COMMENT-STRIP per-touch on touched files. *(per-touch accounting/migration)*
- [x] **T-10** — thicken the spec to full real assertions (migrated deliveries render with the right line items + frozen recipient + state badge; cross-tenant 404) — mock inner-loop + the real-idp parity spec; **drive the real-idp spec green LOCALLY first (LAN PG via env/`.npmrc`, never Docker PG)** per the J-9 retro; point `parity_test` at the real-idp spec. *(spec)*
- [x] **T-11** — *(gap-revealed: T-05 assumed it existed; it doesn't)* add the legacy **Delivery + DeliveryItem fixture seed** to FLSTest (`flsserver/database/FLSTest/3 insert/` + `_test-fixture.sql` aggregation — the per-journey legacy-seed pattern, like J-8's `100 Insert AccountingRuleFilters.sql`): a Delivery + items referencing an existing legacy flight + article lines, so the fanout migrates them and the `[migration/parity]` done-bar renders them. Without it the migrated done-bar can't be proven. *(legacy fixture — the fanout source seed)* — **REVERTED by T-14** (seed SQL + seed.sh wiring removed; rides J-10b).
- [x] **T-12** — *(gap-hunter-revealed blocker)* wire `deliveries-parity.spec.ts` into the fanout's real-bundle spec list (`alpenflight-proof-fanout.yml`) — T-10 authored the spec but its `[migration/parity]` block (`test.skip(!useRealBundle())`) ran the real bundle NOWHERE, so the migrated done-bar was un-gated. *(fanout workflow)* — **REVERTED by T-14** (spec dropped from the fanout list; the `[migration/parity]` block removed).
- [x] **T-13** — *(fanout-revealed: producer SELECT vs real MSSQL)* the DeliveryMapper producer SELECT used `TRY_CONVERT(INT, …)`, which the legacy MSSQL compat level rejects (`'TRY_CONVERT' is not a recognized built-in function`) → export aborts. Replace with a compat-safe digit-guard + `CAST`. The hard-fanout gate (J-9 retro) caught it — local tests structurally can't validate the producer SELECT vs the real schema. *(migration-bundle producer SELECT)* — **REVERTED by T-14** (producer SELECT + the ExportCommandSmokeTest registration go with the un-bound mappers; rides J-10b).
- [x] **T-14** — *(operator-decided defer, 2026-06-15)* back the Delivery migration out of J-10 (it needs J-11 ARTICLE first — binding ARTICLE unscoped orphans non-migrated-club articles in the fanout → `fk_article_operating_club_id` 23503 → every migration journey's parity regresses). Revert the migration parts of T-05/T-11/T-12/T-13: un-bind ARTICLE/DELIVERY/DELIVERY_ITEM in `MapperLegacyBindings` (back to KNOWN_UNBOUND), remove the collision IT + the `ExportCommandSmokeTest` registration, the legacy seed + its seed.sh wiring, the `[migration/parity]` spec block + the fanout entry. KEEP the read vertical (entities/repo/resource/screen/clean-seed). *(multi-file migration revert)*

## Assumptions made

1. `depends_on: [J-9]` — the engine is done; but this iteration does NOT consume it (no create/persist).
   The read screen's data is clean-seed via a test seeder. The engine→persist is J-10b.
2. One screen/route (`/deliveries`, list + view, READ-ONLY). No write actions, no state transitions — the
   line-item table and state badge are display-only.
3. The Delivery migration is **deferred to J-10b** (operator, 2026-06-15): it needs J-11's ARTICLE migrated
   first (`DeliveryItem.article_id` NOT NULL RESTRICT); binding ARTICLE unscoped here regressed every
   migration journey's parity. J-10 ships read-only over clean-seed; no migrated done-bar this iteration.
4. The migration + write side (create/book/delete/state-machine/counter/engine-persist) splits to
   **J-10b** (which `depends_on` J-10 + J-11); the scheduled jobs (S-089/S-090→J-15), Proffix
   (S-029/S-080/S-150→follow-up), credit sub-engine (J-9b), and S-087 (wrong domain) stay deferred/re-homed.
   `_ORDER` updated.
5. Downsized to free boyscout capacity (operator, 2026-06-15) — the journey is intentionally tech-debt-heavy.
