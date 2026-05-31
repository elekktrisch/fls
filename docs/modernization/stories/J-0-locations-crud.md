---
id: J-0
title: Locations CRUD — chain bootstrap
epic: E-06
status: in_progress
started_at: 2026-05-31
journey0: true
carved: true
depends_on: []
rolls_up: [S-062g, S-110]
acceptance:
  - Two CLUB_ADMINISTRATORs in different clubs each log in via Keycloak and see ONLY their own club's Locations (`@TenantId` auto-filter). [happy]
  - Create / edit / soft-delete a Location round-trips through the real UI and persists. [happy]
  - A Location created in club A is not returned to club B; cross-tenant GET by id 404s. [key-error]
  - ICAO is validated server-side `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$`; a bad code is rejected at the form. [edge]
  - The same physical airport (e.g. LSZH) exists independently in two clubs' catalogs (per-club `(club_id, icao_code)` uniqueness, not global). [edge]
  - The run passes FIRST on a clean Flyway seed, THEN on real legacy `Location` data migrated via the `Location` mapper. [happy]
screen: /locations  # replacing legacy masterdata/locations/
headless_pulled_in: Location migration mapper → the migrate step of the proof chain (first per-journey mapper; proves the pattern)
migration: Location (+ child InOutboundPoint) — legacy shared row FANS OUT into one row per referencing club, keyed (legacy_id, club_id) → new_id
parity_test: e2e/tests/masterdata/locations-crud.spec.ts
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023, 0026]
---

## Context

J-0 is the **chain-bootstrap journey**. Locations CRUD is already built end-to-end
(S-049 / S-049b / S-049c are in `implemented/`), so it carries no feature risk —
its entire job is to drag the **full proof chain** into existence for every later
journey: legacy-up → run the `Location` mapper to seed real migrated data →
Keycloak login → a real Playwright run + pass-video against the new stack → wire
that run into CI as a required gate. `Location` is the safest first mapper:
tenant-scoped, low row count, no inbound FKs, and a simple (if fan-out-shaped)
mapping. Once green, J-1…J-22 each extend the chain with their own seed + per-
entity mapper + spec on top of a proven gate.

## Spec must assert

The happy path is **two-club tenant isolation through the real UI**: a
`CLUB_ADMINISTRATOR` in club A and one in club B each log in via Keycloak; each
`GET /api/v1/locations` returns only their own club's rows (Hibernate `@TenantId`
filter — `LocationsController` authz per S-049b: CLUB_ADMIN CRUDs own club). The
spec creates/edits/soft-deletes a Location and re-reads it; it asserts a club-A
Location is absent from club B's list and that a cross-tenant GET-by-id 404s
(parity: tenant leakage is the load-bearing invariant — `S-024` cross-tenant test
lives here too).

Key error / edge:
- ICAO rejected unless `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$` (tightened beyond legacy, which was lax — legacy accepted any string; cutover cleanup tracked in the mapper).
- The same ICAO (e.g. `LSZH`) is creatable in both clubs independently — global ICAO uniqueness was dropped for per-club partial unique `(club_id, icao_code) WHERE icao_code IS NOT NULL AND deleted_on IS NULL`.
- `InOutboundPoint` is managed only via the Location edit screen (child of the aggregate; no top-level CRUD).

**The chain proof is the real acceptance** (do-suite done-bar): the spec must pass
once on a clean seed and once on **real legacy data migrated in**, with the AlpenFlight
pass-video as the artifact and a paired legacy `flsweb` video of the same journey
on the seeded data for human parity-checking.

## Notes

**Migration shape (load-bearing — this is what J-0 proves for everyone).** Legacy
`Location` is shared (one row referenced by many clubs); the new schema is
tenant-scoped. The mapper **fans out**: each legacy `Location` row becomes N new
rows, one per club that references it, keyed `(legacy_id, club_id) → new_id`. The
child `InOutboundPoint` inherits tenancy via its parent (no separate club column).
`LocationType` stays shared reference data (Flyway-seeded; not migrated per-club).
ICAO rows failing the tightened regex need one-time cleanup at map time. This
fan-out keying convention (`(legacy_id, club_id) → new_id`) is the template every
later tenant-scoped mapper copies — getting it right here is the point of J-0.

**Likely task seams (non-binding, for `/do-ship` to size at ship time):**
- *Proof-chain harness* — legacy-up + migrate + Keycloak + Playwright wired as the CI gate (S-062g, S-110); the bulk of J-0's new work, nothing feature-shaped.
- *`Location` migration mapper* — one mapper in `alpenflight/migration-tool/` (currently a bare gradle scaffold), with the fan-out keying + ICAO cleanup.
- *Spec + parity-video* — `e2e/tests/masterdata/locations-crud.spec.ts` extended to drive the two-club isolation path and emit the paired videos.
- *(Locations CRUD itself is `implemented/` — re-assert parity, do not rebuild.)*

**Sacred cows:** Location renames have cross-club blast radius in legacy; the new
model sidesteps this by per-club rows. Coordinates stay opaque `VARCHAR(10)` (no
spatial validation — legacy never enforced it). Legacy URL shape
(`/page/0/100`, `X-HTTP-Method-Override`, `{Items:[...]}` envelope) is intentionally
NOT preserved (ADR 0022) — assert observable behavior only.

## Assumptions made

- `implemented/` Locations CRUD (S-049/b/c) is authoritative and correct; J-0
  re-asserts its parity in the spec and does **not** rebuild it. The net-new work
  is the proof chain + the `Location` mapper.
- The cross-tenant leakage assertion (originally horizontal S-024) is folded into
  J-0's spec rather than tracked separately — J-0 is the first tenant-scoped screen
  with a real repository to leak-test against.

## State at ship time (mapped 2026-05-31)

The proof-chain pieces mostly **already exist** — J-0 is wiring, not building:
- **DONE:** `docker-compose.yml` (MSSQL legacy + Postgres + Keycloak + mailpit);
  real-idp Keycloak flow (`alpenflight-e2e-real-idp.yml` + `tests/real-idp/`);
  `LocationMapper.java` + test (fan-out `(legacy_guid, club_id)`); ingest pipeline
  (`MigrationBundleIngestService`, S-141); the Locations screen + a mocked
  `tests/masterdata/locations-crud.spec.ts` (CRUD/ICAO/soft-delete, but `testIgnore`d);
  `playwright.config.ts` projects (chromium mock-auth + real-idp) with `video`.
- **GAP (the work):** ① the mocked spec is disabled + lacks cross-tenant/uniqueness
  asserts; ② no end-to-end legacy→export→ingest→Postgres test for `Location`;
  ③ no real-idp two-club tenant-isolation spec against the real backend; ④ Playwright
  is NOT in `ci.yml`'s `required` aggregator + video drops on pass (S-062g).

## Tasks

- [x] **T-01 — Inner-loop mocked spec.** Un-ignore `locations-crud.spec.ts` in `alpenflight/web/e2e/playwright.config.ts` (testIgnore) and add the cross-tenant-404 + per-club ICAO-uniqueness assertions to `alpenflight/web/e2e/tests/masterdata/locations-crud.spec.ts`. Fast mocked green. *(seam: one spec + config)*
- ~~**T-02 — Migrate proof IT**~~ (split — verified at ship time that the Location end-to-end migrate path has two missing backend pieces; operator chose to absorb into J-0). **Finding (verified):** `LocationMapper` emits synthetic `new UUID(0, legacyIntId)` for the FLIGHT-group lookup FKs (`location_type_id`/`elevation_unit_type_id`/`runway_length_unit_type_id`); the Javadoc promises S-141 resolves these structurally from the V3 seed via `legacy_int_id`, but no such step exists (`EntityStreamIngestor` rewrites only `foreignKeys() = [CLUB, COUNTRY]`). V3 seeds `t_location_type(legacy_int_id=2)` as `019e2e15-2c00-72c9-…`, not `…-002` → a real Location ingest FK-violates. Same synthetic-int-id pattern is used by the Aircraft/Flight mappers, and the existing parity round-trip IT only exercises IDENTITY-group entities, so this was never hit. Also: no `INOUTBOUND_POINT` in `EntityType`, no mapper, no registration.
- [ ] **T-02a — Structural FLIGHT-group reference-FK resolve (ingest pipeline).** In `alpenflight/server/.../migrations/application/`, add a structural-resolve step that rewrites the synthetic `legacyIntIdToUuidString` UUID to the real seed PK by joining the target seed table's `legacy_int_id` column (the `ux_*_legacy_int_id` indexes exist for exactly this) — for Location's three lookup FKs (`t_location_type`, `t_elevation_unit_type`, `t_length_unit_type`). Generic mechanism + a way for a mapper to declare its reference-lookup columns + `LocationMapper`'s declaration + a focused IT. *(seam: one ingest-pipeline change; reusable infra for J-1/J-2)*
- [ ] **T-02b — InOutboundPoint child mapper.** New `InOutboundPointMapper` (migration-bundle, `flight/` package) + `EntityType.INOUTBOUND_POINT` + `KnownMappers` registration + ingest dispatch + mapper test. Inherits tenancy via parent `Location` (no own club column); keyed under its parent. *(seam: one entity mapper)*
- [ ] **T-02 — Migrate proof IT** *(after T-02a + T-02b).* Enhance `alpenflight/server/.../migrations/web/MigrationBundleIngestIT.java` (pattern: `MigrationBundleParityRoundTripIT`) to bundle ≥2 legacy `Location`s (one referenced by ≥2 clubs) + a child `InOutboundPoint` → encrypt → ingest → assert `t_location` `(legacy_guid, club_id)` fan-out rows (one per referencing club, each `@TenantId`=that club) + nested `InOutboundPoint`s attached to the right parent. *(seam: one server IT, the migrate link)*
- [ ] **T-03 — Real-idp two-club isolation spec.** New `alpenflight/web/e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts` + a two-club seed helper under `tests/real-idp/_helpers/`: two CLUB_ADMINISTRATORs (via `keycloak-admin.ts`), each sees only own club's Locations, cross-tenant GET 404 — against the REAL backend. *(seam: one real-idp spec + helper)*
- [ ] **T-04 — Proof-chain gate + required wiring.** Run legacy-up→migrate→real-idp locations spec as a CI job (extend `alpenflight-e2e-real-idp.yml` or new `alpenflight-proof.yml`); set `video: 'on'` for the proof project (retain pass-video); add the alpenflight Playwright job to `ci.yml`'s `required` aggregator (closes S-062g). *(seam: one CI gate)*
- [ ] **T-05 — Gate run (e2e-driver, not a /do-task worker).** Full chain both fidelities green (clean seed + migrated), pass-video retained, paired legacy `flsweb` video captured; `gap-hunter` ×2-3 against the diff + spec. Run by the manager at §4.

**Order:** T-01 ✓ → T-02a → T-02b → T-02 → T-03 → T-04, then T-05 gate. Run
sequentially (shared branch). No `legacy-oracle` pass — Location parity is already
captured in S-049/S-049b + the built `LocationMapper` + its test. T-02 split into
T-02a/T-02b/T-02 at ship time (operator-approved scope absorption, 2026-05-31);
the structural-resolve + InOutboundPoint mapper are shared migration infra that
J-1 (Aircraft) and J-2 (Flight) inherit.
