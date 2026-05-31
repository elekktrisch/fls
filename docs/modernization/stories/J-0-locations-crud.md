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
  - The run passes on a clean Flyway seed (real Keycloak + real backend; two clubs seeded via API). [happy] — *migrated-data fidelity DEFERRED to J-0b (fan-out foundation), per ship-time scope narrowing 2026-05-31.*
screen: /locations  # replacing legacy masterdata/locations/
headless_pulled_in: none in J-0 — Location migration-mapper foundation (resolve/IOP/bindings) landed, but the fan-out keying is deferred to J-0b
migration: N/A for J-0's green — clean-seed only. Migrate-half foundation (T-02a/b/c) landed; fan-out keying (legacy_id, club_id)→new_id deferred to J-0b.
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
- [x] **T-02a — Structural FLIGHT-group reference-FK resolve (ingest pipeline).** In `alpenflight/server/.../migrations/application/`, add a structural-resolve step that rewrites the synthetic `legacyIntIdToUuidString` UUID to the real seed PK by joining the target seed table's `legacy_int_id` column (the `ux_*_legacy_int_id` indexes exist for exactly this) — for Location's three lookup FKs (`t_location_type`, `t_elevation_unit_type`, `t_length_unit_type`). Generic mechanism + a way for a mapper to declare its reference-lookup columns + `LocationMapper`'s declaration + a focused IT. *(seam: one ingest-pipeline change; reusable infra for J-1/J-2)* **Built (verified):** `Mapper.referenceLookups()` (default empty) + `ReferenceLookupResolver` (decodes `new UUID(0,intId)`, joins `<seed>.legacy_int_id`, fail-closed) wired into `EntityStreamIngestor.ingestEntityNdjson` after FK rewrite; `LocationMapper` declares its 3 lookups. **Schema-enabler correction:** the T-02 finding/brief assumed `ux_*_legacy_int_id` already existed on all 3 seed tables — only `t_location_type` (V3) had it; `t_elevation_unit_type`/`t_length_unit_type` (V2) had NO `legacy_int_id` column at all, so `V22__unit_type_legacy_int_id.sql` backfills the column + unique index + legacy ints (Meter=1/Feet=2 per FLSTest static seed). Resolver IT green vs real Flyway Postgres.
- [x] **T-02b — InOutboundPoint child mapper.** New `InOutboundPointMapper` (migration-bundle, `flight/` package) + `EntityType.INOUTBOUND_POINT` + `KnownMappers` registration + ingest dispatch + mapper test. Inherits tenancy via parent `Location` (no own club column); keyed under its parent. *(seam: one entity mapper)*
- [x] **T-02c — Location + InOutboundPoint producer SELECT bindings.** Add `LOCATION` + `INOUTBOUND_POINT` `Binding`s to `MapperLegacyBindings` (legacy-export half — today only the 5 IDENTITY entities are bound; T-02b found Location/IOP unbound, S-187a deferred them). The SELECT against the legacy MSSQL `Locations` / `InOutboundPoints` tables emitting exactly the columns `LocationMapper.writeNdjson` / `InOutboundPointMapper.writeNdjson` read; consumed by the export jar (S-139) + the parity ProducerHarness. *(seam: one bindings registry edit; export half, J-1/J-2 inherit the pattern)*
- ⏸️ **T-02 — Migrate proof IT — DEFERRED to J-0b** (ship-time scope narrowing 2026-05-31, operator-chosen). The proof IT is written + committed `@Disabled` as the live reproduction; it goes green once J-0b builds the fan-out subsystem. NOT part of J-0's green. *(seam: one server IT, the migrate link — moves to J-0b)*
  - **ESCALATED (2026-05-31, do-task) — the fan-out distinct-id minting is unimplemented; AC (a) is unmeetable with current T-02a/b/c.** Proof IT written + run LIVE against real Postgres ingest: `alpenflight/server/src/test/java/ch/alpenflight/migrations/web/LocationMigrationRoundTripIT.java` (red, kept as the reproduction). **Live failure:** real ingest returns `500 errorCode=INGEST_INTERNAL_ERROR, sqlstate=23505` (PK collision) when the shared legacy Location fans out to 2 clubs — asserted at `LocationMigrationRoundTripIT.java:208` (status 200 expected, got 500 before AC (a)/(b)/(c) assertions are even reached). **Root cause (file:line):** the producer `LocationMapper.writeNdjson` (`migration-bundle/.../flight/LocationMapper.java:123`) writes `legacy_guid = LocationId` — the SAME legacy GUID for every fan-out replica; the ingest maps `legacy_guid → id` verbatim (`server/.../migrations/application/EntityStreamIngestor.java:256-264`), so the 2nd replica's INSERT collides on the `t_location.id` PK. The journey's load-bearing `(legacy_guid, club_id) → distinct new_id` keying (journey lines 20, 64-68) has **no implementation**: (1) the producer mints no per-(Location,club) replica id, (2) `t_location` has no `legacy_guid` column to hold the shared legacy key separate from a distinct `id` (`V3__flights_aircraft_locations.sql` / `V7__location_tenant_scoped.sql`), and (3) the ingest id-map temp table is single-keyed `(legacy_guid)` not composite `(legacy_guid, club_id)` (`EntityStreamIngestor.java:66`). Fixing this spans the producer mapper + a Flyway schema column + composite id-map keying — multiple seams; **a new task**, per the do-task escalation rule (do not patch from T-02). T-02b's child-nesting + T-02a's reference-FK resolve could not be reached live because ingest aborts on the parent collision first.
- [x] **T-03 — Real-idp two-club isolation spec.** New `alpenflight/web/e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts` + a two-club seed helper under `tests/real-idp/_helpers/`: two CLUB_ADMINISTRATORs (via `keycloak-admin.ts`), each sees only own club's Locations, cross-tenant GET 404 — against the REAL backend. *(seam: one real-idp spec + helper)* **Built (verified compile + collection; live run gated on the unavailable real-idp stack — Alpine/musl box, runs on the T-04/T-05 CI gate):** new `two-club-fixture.ts` (club A = Flyway-seeded `seed-club-1`; club B created live via `POST /api/v1/clubs` as the seeded `sysadmin` — bearer captured from the SPA's own `/api/v1/*` call since no realm client grants ROPC; each club gets a fresh `e2e-…@example.com` CLUB_ADMINISTRATOR with the `clubId` user-attribute = real club UUID, so `ClubTenantIdentifierResolver` resolves the tenant straight off the JWT claim — no `t_user` seed needed) + `keycloak-admin.ts` extended with `createUserWithAttributes`/`findRealmRole`/`assignRealmRole`. Spec asserts: club-A admin creates a Location (UI) + it lists; club-B admin's list omits it; direct cross-tenant `GET /api/v1/locations/{clubA_id}` → **404 confirmed against `LocationsController` javadoc (404 not 403, IDOR gate structural)**; both clubs hold LSZH independently (per-club `(club_id, icao_code)`). `tsc -p e2e/tsconfig.json` clean on the 3 files, `playwright --list` collects all 3 tests under `real-idp`, eslint 0-errors, prettier clean.
- [x] **T-04 — Proof-chain gate + required wiring (clean-seed).** Run **clean Flyway seed → Keycloak → real-idp locations spec** as a CI job (extend `alpenflight-e2e-real-idp.yml` or new `alpenflight-proof.yml`); set `video: 'on'` for the proof project (retain pass-video); add the alpenflight Playwright job to `ci.yml`'s `required` aggregator (closes S-062g). The legacy-up→migrate step is DEFERRED to J-0b (drops from J-0's gate). *(seam: one CI gate)* **Built (lint/structure-validated; live run is the gate's own first CI run — the real-idp stack can't run on this Alpine/musl box):** new `alpenflight-proof` job lives INSIDE `ci.yml` (not the nightly workflow) because the `required` aggregator's `needs:` can only reference same-workflow jobs — that's the only shape that actually wires Playwright into the required gate per S-062g. Brings up Postgres + Keycloak + Mailpit + backend + `ng serve --configuration=development` (clean Flyway seed; NO MSSQL/legacy-up/migrate — deferred to J-0b), runs ONLY `tests/real-idp/locations-crud-tenant-isolation.spec.ts` (+ its auto-run `real-idp-setup` dep) to stay in the ~8-min budget; the heavier full real-idp suite stays nightly in `alpenflight-e2e-real-idp.yml`. Gated on `needs.changes.outputs.next == 'true'` so docs-only PRs skip→aggregator green. Added to the aggregator `needs:` + result-check (`R_PROOF`). `video: 'on'` override on the `real-idp` project in `playwright.config.ts` (chromium stays global `retain-on-failure`); `upload-artifact` archives the pass-video (`playwright-report` + `test-results`) as J-0's acceptance artifact. **Validated:** YAML well-formed (both workflows parse); aggregator references `alpenflight-proof` exactly; `video: 'on'` is the single code-level override + sits in the real-idp project (chromium has none); eslint + prettier clean on the config; realm-shape guard untouched. **Only CI can confirm:** the live clean-seed stack bring-up + green run + pass-video retention (T-05 gate).
- [x] **T-06 — Gate fix: proof job JDK setup.** First CI run of the gate (PR #190) failed at `Flyway migrate` with "Gradle requires JVM 17 or later… configured to use JVM 11" — the `alpenflight-proof` job set up Node but not Java, so its `./gradlew flywayMigrate`/`bootJar` steps used the runner default JVM 11. Added a `Set up Java 25 (temurin)` step (mirrors `next-build`). *(seam: one CI step)*
- [x] **T-07 — Gate fix: stale bindings smoke test.** `alpenflight build` failed at `ExportCommandSmokeTest.registeredEntitiesAreTheFiveSliceBindings` — T-02c legitimately grew `MapperLegacyBindings` from 5→7 (added LOCATION + INOUTBOUND_POINT). Updated the assertion to the 7 bound entities + renamed to `registeredEntitiesMatchTheBoundLegacyEntities`. Test green locally. *(seam: one test assertion)*
- [x] **T-08 — Gate fix: backend boot-jar path.** 2nd CI run got past T-06/T-07 (build green; Flyway+bootJar+backend-start green) but the proof gate failed at "Wait for backend /actuator/health" → `Unable to access jarfile build/libs/server-*.jar`. The bootJar archiveBaseName is `alpenflight-server` (= `rootProject.name`), not `server`, and a non-executable `*-plain.jar` co-exists — the glob never matched, so `java -jar` died and the backend never came up. Fixed both `ci.yml` (proof gate) and the nightly `alpenflight-e2e-real-idp.yml` (identical latent bug) to resolve the boot jar via `ls build/libs/*.jar | grep -v -plain | head -1`. *(seam: one CI step ×2)*
- [ ] **T-05 — Gate run (e2e-driver, not a /do-task worker).** Clean-seed fidelity green (real Keycloak + real backend, two clubs via API), AlpenFlight pass-video retained, paired legacy `flsweb` video captured for parity; `gap-hunter` ×2-3 against the diff + spec (done, real:true ×2). Run by the manager at §4. **Iterating on the live `alpenflight-proof` gate** (T-06 JDK → T-07 bindings test → T-08 boot-jar path; re-watching).

**Order:** T-01 ✓ → T-02a ✓ → T-02b ✓ → T-02c ✓ → ~~T-02~~ (deferred → J-0b) →
T-03 ✓ → T-04, then T-05 gate. Run sequentially (shared branch). No `legacy-oracle`
pass — Location parity is already captured in S-049/S-049b + the built `LocationMapper`.

**Ship-time scope narrowing (2026-05-31, operator-chosen).** J-0's migrate-half live
proof (T-02) surfaced that the core fan-out keying `(legacy_guid, club_id)→distinct
new_id` is entirely unbuilt (PK collision on the 2nd club's replica — see T-02
escalation). The carve premise ("Location is the simplest first mapper; migration
mostly exists") was disproven: the migration chain was never run end-to-end, and
Location is the first entity to exercise fan-out at all. Operator narrowed J-0 to a
**clean-seed** real chain; the migrate-half foundation built here (T-02a/b/c —
reference-FK resolve, InOutboundPoint mapper, producer bindings) **stays landed** as
reusable infra; the fan-out subsystem + the `@Disabled` `LocationMigrationRoundTripIT`
move to a new follow-on journey **J-0b — Migration fan-out foundation** (filed in
`_ORDER.md`), which J-1/J-2's migrate-fidelity then depend on. `/do-retro` should
capture the carve-premise lesson.
