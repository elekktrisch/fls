---
id: J-10
title: Deliveries — read-only screen + migration
epic: E-09
status: in_progress
started_at: 2026-06-15
journey0: false
carved: true
depends_on: [J-9]
rolls_up: [S-078]
acceptance:
  # ≥feature — the /deliveries read surface: migrated invoice drafts rendered tenant-scoped, read-only
  - "[happy] /deliveries list renders the club's deliveries (delivery number, recipient, batch, state) tenant-scoped, paged + sortable; reachable via the masterdata nav (spec ENTERS via the dropdown)"
  - "[happy] view a delivery: read-only line items (position/article/itemText/quantity/unitType), the 9-field frozen recipient snapshot, and the linked flight info (all read-only — no edit/book/delete actions this iteration)"
  - "[edge] cross-tenant GET of another club's delivery → 404 (tenant isolation)"
  - "[migration/parity] migrated legacy deliveries render under their migrated TestClub tenant, identity-matched to legacy (the migrated done-bar — binds the Delivery + DeliveryItem mappers; HARD fanout gate per the J-9 retro)"
screen: /deliveries (list + view, READ-ONLY) — replacing legacy masterdata/deliveries/ (read path)
headless_pulled_in: the Delivery + DeliveryItem migration mappers (the producer SELECT — the read screen's data source); no engine/write path this iteration
migration: Delivery + DeliveryItem (legacy Delivery/DeliveryItem → V4 t_delivery/t_delivery_item) — binds the authored-but-unbound DeliveryMapper + DeliveryItemMapper. LOAD-BEARING: the migrated done-bar IS the proof.
parity_test: alpenflight/web/e2e/tests/real-idp/deliveries-parity.spec.ts
adr_refs: [0008, 0020, 0022, 0027]
---

## Context

The invoice-draft read surface. A **Delivery** is the immutable output of the J-9 rules engine — the
billing line items + frozen recipient snapshot for a flight. This iteration ships the **read-only**
`/deliveries` screen + the legacy `Delivery`/`DeliveryItem` **migration** (the first journey to bind the
V4 Delivery mappers, so it carries the migrated done-bar + the J-9-retro HARD fanout gate). The **write
side** — creating, booking (gap-free numbering), deleting, the state machine, and persisting fresh engine
output — is split to **J-10b** (next iteration). Downsized at operator request to free capacity for the
boyscout backlog: this journey runs **tech-debt-heavy** (thin read feature + the blocking riders + extra
`_BOYSCOUT` burndown).

## Spec must assert

Grounded in the legacy READ path — `flsweb/src/masterdata/deliveries/` (list `DeliveriesEditController.js`,
view `deliveries-edit.html`) + `flsserver` `DeliveriesController.cs` GET endpoints + `Delivery.cs`:

- **The read screen** — list (delivery_number, recipient, batch_id, state; paged, sort batch desc /
  recipient asc, tenant-scoped) + view (read-only line items, the frozen 9-field recipient snapshot,
  read-only flight link). **No edit/book/delete actions** this iteration — the screen is a viewer.
  Line items are engine output, never hand-editable.
- **State is displayed, not transitioned** — `process_state_id` 10 Prepared / 20 Booked / 30 Error / 99
  Cancelled renders as a column/badge; the screen does not mutate it (transitions are J-10b).
- **The migration is the data source** — bind `DeliveryMapper` + `DeliveryItemMapper`; the screen renders
  MIGRATED legacy deliveries (the V4 cutover collapses legacy `flight.ProcessStateId` + `delivery.
  IsFurtherProcessed` into the one `process_state_id` enum; recipient_* frozen per OR Art. 957a; legacy
  delivery-number text → integer column). The migrated done-bar proves the producer SELECT.
- Dispatch `legacy-oracle` at ship time only if the read DTO shape / list-projection columns can't be
  derived from `DeliveriesController.cs` + the design (none exists — inherit legacy parity).

## Notes

**Scope — READ-ONLY this iteration (operator downsizing, 2026-06-15).** J-10 = the Delivery read path +
migration; **J-10b** carries the write side. Concretely:
- **J-10 builds:** the `Delivery` + `DeliveryItem` JPA entities (read-mapped, @TenantId), a read
  `DeliveryRepository`, the GET list/view REST resource, the read-only `/deliveries` SPA screen, and the
  **Delivery/DeliveryItem migration binding** (the migrated done-bar).
- **J-10b builds (deferred):** the write behavior on the `Delivery` aggregate — create, **book()** with
  the gap-free `delivery_number` counter (`t_club_delivery_number_counter`, atomic `UPDATE…RETURNING`),
  delete (resets the linked flight to Locked), the Prepared→Booked terminal state machine + 409-on-Booked,
  and the `AccountingDeliveryEngine → Delivery` persist orchestration. Plus the write endpoints + the
  screen's book/delete actions.

**Riders to fold (BLOCKING — `_BOYSCOUT.md`, J-9-retro):** J-10 binds a mapper → migration journey → its
`fan-out parity` job is a HARD merge gate (J-9 retro). It cannot go green until:
- the **migration-bundle-ingest 409** is fixed (`ensureSharedMigrationBundle` polls the deployment to
  `COMPLETED` + treats `409 DEPLOYMENT_EXISTS` as reuse) — reds every migrated-parity spec today;
- the **J-9 migrated done-bar article-5001** is resolved (migrated FlightTime filter not applying / the
  TestClub export lacks the inputs).
Both MUST be `T-NN`s, not deferred. **Use the freed write-side capacity for additional `_BOYSCOUT`
burndown** (the ≤70% tech-debt window) — `/do-ship` should fold riders touching the deliveries/migration
surface generously.

**Going-in substrate (V4, verified):** `t_delivery` / `t_delivery_item` / `t_club_delivery_number_counter`
schemas exist (`process_state_id` SMALLINT 10/20/30/99; `ux_dlv_club_number_partial`; FK `flight_id`,
`recipient_person_id` SET NULL, `article_id` RESTRICT). `DeliveryMapper` + `DeliveryItemMapper` are
authored + registered in `migration-bundle` but **migration-only — NO server-side Delivery @Entity / repo
/ service / controller exists yet** (J-10 builds the read half; J-10b the write half).

**Seam hints (non-binding, one seam each):** the `Delivery` + `DeliveryItem` JPA entities (read-mapped);
the read `DeliveryRepository` (@TenantId, JPA-first per ADR 0027); the delivery GET resource (list + view,
tenant 404); the read-only `/deliveries` SPA screen (list + view); the **Delivery + DeliveryItem migration
binding** (the migrated done-bar); the `ensureSharedMigrationBundle` 409 fix; the article-5001 fix +
bit-exact migrated assertion; the parity spec.

## Tasks

Per do-ship §2 (one seam each). J-10 LEADS with the read-only `/deliveries` screen + migration; it runs
**tech-debt-heavy** (operator downsizing → fold `_BOYSCOUT` riders generously). The two blocking riders
(T-07/T-08) are MANDATORY — the fanout is a HARD gate. Migrated done-bar + fanout proven at the §4 gate.

- [ ] **T-01** — spec stub `e2e/tests/accounting/deliveries.spec.ts` (read-only: list / view / migrated / cross-tenant 404; ENTERS via the Masterdata nav dropdown) + scaffold the per-journey gallery page (current-journey-only). *(e2e + gallery)*
- [ ] **T-02** — gate scoping: J-10 `mock_test`/`parity_test` frontmatter so per-push runs only J-10's specs; prior journeys run mock-IdP. *(ci.yml + frontmatter)*
- [ ] **T-03** — `Delivery` + `DeliveryItem` JPA entities (read-mapped, `@TenantId`; `process_state` enum 10/20/30/99 display-only; frozen recipient VO; the DeliveryItem child + position) + read `DeliveryRepository` (tenant-scoped paged query + find-by-id) + domain/repo tests. *(accounting/domain + infra)*
- [ ] **T-04** — the delivery READ resource: `DeliveriesService` (paged list + view) + DTOs (`DeliveryOverview` list-row, `DeliveryDetail`) + `DeliveriesController` (GET list/page + GET `/{id}`) + ControllerAuditCoverage + cross-tenant 404 IT. *(accounting/application + web)*
- [ ] **T-05** — bind the `Delivery` + `DeliveryItem` migration mappers (`MapperLegacyBindings`) + the legacy Delivery/DeliveryItem seed + the **real-producer collision/orphan round-trip IT**: delivery_number parse-collision → 23505 on `ux_dlv_club_number_partial`; `delivery_item.article_id` / `delivery.flight_id` RESTRICT orphan; `recipient_person_id` SET NULL — reds in `check` (minutes), not the ~20-min fanout. *(migration-bundle + IT)*
- [ ] **T-06** — FE read-only screen: orval regen + `features/accounting/deliveries` route + store + list page (paged/sortable, tenant-scoped) + view page (read-only line items + frozen recipient + flight link) + **nav entry under the Masterdata dropdown (chrome-reachable)**. *(web)*
- [ ] **T-07** — *(BLOCKING rider, J-9-retro)* fix the migration-bundle-ingest 409: `ensureSharedMigrationBundle` polls the deployment to `COMPLETED` after ingest + treats `409 DEPLOYMENT_EXISTS` as reuse-`existingDeploymentId` (never re-ingest/throw). Unblocks the real-bundle parity for J-0c/J-5/J-6 + J-10. *(e2e real-idp helper)*
- [ ] **T-08** — *(BLOCKING rider, J-9-retro)* resolve the J-9 migrated done-bar **article-5001** (verify T-07's deployment-timing fix resolves it; else fix the migrated FlightTime-filter availability / the TestClub export inputs) + strengthen the migrated assertion toward bit-exact. *(e2e real-idp spec + maybe TestClub seed)*
- [ ] **T-09** — *(boyscout fold, ≤70% window)* surface-touching `_BOYSCOUT` riders: the `[DOC-DRIFT]` `FilterConfig.java` stale javadoc; per-touch IT-seeding conversion on the ITs this journey adds; COMMENT-STRIP per-touch on touched files. *(per-touch accounting/migration)*
- [ ] **T-10** — thicken the spec to full real assertions (migrated deliveries render with the right line items + frozen recipient + state badge; cross-tenant 404) — mock inner-loop + the real-idp parity spec; **drive the real-idp spec green LOCALLY first (LAN PG via env/`.npmrc`, never Docker PG)** per the J-9 retro; point `parity_test` at the real-idp spec. *(spec)*

## Assumptions made

1. `depends_on: [J-9]` — the engine is done; but this iteration does NOT consume it (no create/persist).
   The read screen's data is migrated (+ clean-seed via a test seeder). The engine→persist is J-10b.
2. One screen/route (`/deliveries`, list + view, READ-ONLY). No write actions, no state transitions — the
   line-item table and state badge are display-only.
3. Migration is load-bearing: J-10 binds the Delivery/DeliveryItem mappers, so the **real fanout is a hard
   gate** — the synth bundle aliases columns and won't prove the producer SELECT.
4. The write side (create/book/delete/state-machine/counter/engine-persist) splits to **J-10b**; the
   scheduled jobs (S-089/S-090→J-15), Proffix (S-029/S-080/S-150→follow-up), credit sub-engine (J-9b), and
   S-087 (wrong domain) stay deferred/re-homed. `_ORDER` updated.
5. Downsized to free boyscout capacity (operator, 2026-06-15) — the journey is intentionally tech-debt-heavy.
