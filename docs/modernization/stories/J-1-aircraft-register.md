---
id: J-1
title: Aircraft register
epic: E-06
status: in_progress
started_at: 2026-06-02
journey0: false
carved: true
depends_on: [J-0, J-0b]
rolls_up: [S-161, S-163, S-164]  # S-162 descoped at ship time (no legacy parity — own admin journey; see Parity decisions)
acceptance:
  - Club admin opens /aircrafts and sees their club's aircraft, sorted by immatriculation, showing immatriculation + competition sign + aircraft type. [happy]
  - Add a new aircraft via the form (immatriculation, type, manufacturer/model, seats, club-vs-private owner, owner club/person, homebase, spot link); it appears in the list. [happy]
  - Edit an existing aircraft; changes persist and re-render. [happy]
  - Delete an aircraft; it leaves the list. [happy]
  - A caller whose club ≠ the aircraft's managing club is denied edit/delete (403) — `AircraftAccess.canEdit` parity (managingClubId == callerClubId). [key-error]
  - `latestCounter` is present in the Aircraft detail for a manager caller and redacted for a non-manager caller (S-164). [edge]
  - Real legacy Aircraft data migrates into AlpenFlight and the migrated aircraft renders in the owning club's /aircrafts list under its immatriculation — full legacy→migrate→Keycloak→UI chain green (reuses J-0c's harness). [happy, real-data]
screen: /aircrafts (feature folder masterdata/aircrafts/) — replacing legacy flsweb/src/masterdata/aircrafts/
headless_pulled_in: per-entity AIRCRAFT migration mapper (+ AircraftAircraftState history, AircraftOperatingCounter) — pulled in by this screen; AircraftType/AircraftState reference data already have controllers
migration: Aircraft (+ aircraft_aircraft_state, aircraft_operating_counter)
parity_test: alpenflight/web/e2e/tests/real-idp/aircraft-migration-parity.spec.ts
adr_refs: [0008, 0022]
---

## Context

The aircraft register is the masterdata screen every flight/reservation/accounting
journey depends on (J-2/J-5/J-8 list `depends_on: J-1`). A club admin manages the
club's fleet — immatriculation, type, ownership, homebase. It's the first journey
after the migration-fan-out trio, so it runs the **full real legacy→migrate→Keycloak
→UI chain** as its done bar (mapper- *and* domain-touching — retro Q1).

## Spec must assert

Grounded in legacy `flsweb/src/masterdata/aircrafts/` (list `aircrafts-table.html`,
form `aircraft-form-fields.html`, modal `add-aircraft.html`) and the existing backend
`AircraftsController` (`/api/v1/aircraft`: GET list/`/picker`/`/{id}`, POST, PUT/{id},
DELETE/{id}, POST `/{id}/transfer-ownership`) + `AircraftAccess`:

- **List + CRUD** (happy): list sorted by immatriculation; create via form; edit; delete.
  Fields from `aircraft-form-fields.html`: immatriculation, competition sign, aircraft
  type, manufacturer, model, seats, owner type (club vs private), owner club/person,
  homebase, spot link.
- **Tenant edit-isolation** (key-error): `canEdit` admits only the **managing club**
  (`AircraftAccess.canMutate`: callerClubId == resolveManagingClubId) → cross-club
  edit/delete is 403. Mirrors J-0's cross-tenant 404/403 pattern.
- **latestCounter redaction** (edge, S-164): present in `AircraftDetail` for a manager
  caller, redacted (null) for a non-manager.
- **Real-data parity** (happy): a migrated legacy Aircraft renders in the owning club's
  list under its immatriculation, post-migration.

**Parity to confirm at ship time (do NOT guess — dispatch `legacy-oracle`):**
- **S-163** — extend `canEdit` to admit the person matching `aircraft_owner_person_id`.
  The current code comment (`AircraftAccess` ~line 37) says owner-person is **intentionally
  NOT** admitted, but S-163 (todo) + the `_ORDER` † note ("backend already implemented")
  disagree. Resolve against legacy `AircraftService`/access behavior before asserting.
- **S-162** — sysadmin variant `/api/v1/admin/aircraft` with explicit `managingClubId`:
  confirm whether it exists + assert parity, or descope to its own admin journey.
- **S-161** — cross-club aircraft visibility (charter case): what the managing club vs a
  using club sees. Confirm the legacy read-visibility rule before asserting list contents.

## Notes

- **Backend largely exists** — `AircraftsController` + `AircraftAccess` + `Aircraft`
  aggregate + DTOs are implemented; S-162/163/164 are flagged "backend done, re-assert
  parity" in `_ORDER` (but see the S-163 contradiction above). **Net-new is the SPA
  `/aircrafts` screen** + the AIRCRAFT migration wiring + the parity assertions.
- **Done bar = real chain** (retro Q1: mapper + domain touching). Reuse J-0c's
  legacy→migrate→Keycloak→AlpenFlight harness + per-club video; don't settle for
  synthetic-IT green.
- **Boyscout riders to fold into the task list** (from `_BOYSCOUT.md` — they ride this
  journey, not separate stories):
  - **Gallery ✅-ordering fix + guard test** — J-1's gate regenerates the canonical
    gallery, so fixing `generate-gallery.mjs`'s roadmap regex here restores J-0's
    ordering *with visible proof* (the operator's standing gallery complaint).
  - **Migrated-admin profile completion** — J-1's real chain logs a migrated admin into
    the UI, so production `provisionClubAdminIdentity` must set firstName/lastName from
    the legacy Person (and the test-side fixup is removed). Without it the migrated admin
    hits Keycloak `VERIFY_PROFILE` — the journey can't reach /aircrafts.
  - **Binding-completeness guard** — assert the AIRCRAFT mapper's `foreignKeys()` targets
    all have `MapperLegacyBindings` entries (the J-0c T-21 "authored-but-unwired" class).
  - **Keycloak fail-closed contract doc** + **Docker Hub image-pull retry** — fold if a
    task touches that surface; else leave for a later gated journey.
- **Seam hints for `/do-ship`** (non-binding, one seam each): AIRCRAFT migration mapper +
  legacy seed · `/aircrafts` list component + store · aircraft add/edit form component ·
  Playwright spec stub · spec thicken (real parity assertions) · each boyscout rider above.
- **Migration shape:** Aircraft fans out per-club like Location (J-0b infra); the migrated
  admin must own the migrated aircraft's managing club to pass the edit-isolation assertion.

## Parity decisions (ship-time, from legacy-oracle + operator 2026-06-02)

The four flagged questions, resolved against legacy (`flsserver` `AircraftService`/`BaseService`)
+ operator adjudication:

- **S-161 (cross-club read visibility) — PARITY-CONFIRMED, asserted.** Legacy has **no ClubId
  filter on any aircraft read** (`AircraftService.cs` GetAircrafts* — unlike FlightService).
  Aircraft are globally readable; charter/cross-club aircraft show read-only in a using club's
  list (`CanUpdateRecord=false`). New code matches (cross-tenant `AircraftRepository`, S-058
  reversion of S-159). Spec asserts list shows other-club aircraft, edit/delete gated per-row.
- **S-162 (sysadmin variant) — DESCOPED (operator).** No legacy sysadmin endpoint / no
  `managingClubId` param anywhere in legacy `AircraftsController`; a pure sysadmin gets no edit
  rights in legacy. No parity to assert. Removed from `rolls_up`; **note for /do-plan** to carve
  a dedicated admin journey later.
- **S-163 (owner-person edit) — BUILD NOW (operator).** Oracle finding: legacy `IsOwner`
  (`BaseService.cs:73-86`) gates on the **creating club** (`OwnerId`/`OwnershipType`) and never
  reads `AircraftOwnerPersonId`, so admitting the owner-person is **net-new**, not parity. Operator
  chose to build it this journey anyway (T-03). Needs caller-Person resolution (legacy had none;
  S-052 User→Person is not yet built) — **T-03 investigates the JWT→Person link and escalates if
  it's a hard blocker**. The `AircraftAccess:37` comment flips from "intentionally NOT" to admitting
  owner-person.
- **S-164 (latestCounter redaction) — BUILD NOW (AC line 16).** Net-new policy (legacy never
  redacted the separate counter). T-02 adds caller-aware redaction in `AircraftMapper.toDetail`
  (present for managing-club caller, null otherwise).

**Parity exclusion (recorded, not built here):** `AircraftMapper` derives `managing_club_id` from
legacy **`AircraftOwnerClubId`**, but legacy's *edit gate* used **`OwnerId`** (creating club) —
these differ only when an aircraft's form owner-club ≠ its creating club. Does **not** affect J-1's
ACs (real-data AC is read/render; edit-isolation AC is clean-seed where we control the value).
**Flag for /do-plan** to weigh `OwnerId`-fidelity when carving J-21 (full migration) / J-2 (flights→aircraft).

## Tasks

Verify-wire-prove journey — backend, frontend, and all 3 migration mappers already exist
(explorers 2026-06-02); net work is the missing bindings, the two parity policies (S-163/S-164),
the migration proof, the real chain, and folded boyscout riders.

- [x] **T-01** — Frontend `/aircrafts` align: verify the existing `features/aircraft/` screen
  (list+edit+store) meets the journey ACs, wire the nav entry + an `aircraft:` i18n section
  (de/en/fr/it), align `e2e/tests/masterdata/aircraft-crud.spec.ts` selectors/test-ids to the ACs
  with thin assertions. *(seam: features/aircraft + i18n + nav + mock spec)*
- [x] **T-02** — S-164 caller-aware `latestCounter` redaction in `AircraftMapper.toDetail` (present
  for managing-club caller, null otherwise) + `AircraftsAuthorizationIT` assertion (manager sees /
  non-manager null). *(seam: AircraftMapper + IT)*
- [x] **T-03** — S-163 owner-person edit predicate: extend `AircraftAccess.canMutate` to admit the
  caller whose Person matches `aircraft_owner_person_id`; resolve the JWT→Person link (investigate;
  escalate if S-052 is a hard blocker), flip the `:37` comment, add `AircraftsAuthorizationIT` case.
  *(seam: AircraftAccess + person resolution + IT)*
- [x] **T-04** — Register AIRCRAFT (+ AIRCRAFT_AIRCRAFT_STATE, AIRCRAFT_OPERATING_COUNTER) in
  `MapperLegacyBindings` with the producer SELECT (managing_club_id cascade + homebase Location
  fan-out source); flip `MapperLegacyBindingsTest.unregisteredEntityStillFailsLoudly`; add the
  **binding-completeness guard** (boyscout: every mapper `foreignKeys()` target has a binding).
  *(seam: MapperLegacyBindings + AircraftMapper producer + tests)*
- [x] **T-05** — `AircraftMigrationRoundTripIT` + `AircraftRealProducerRoundTripIT` mirroring the
  Location IT templates (FK resolve CLUB/PERSON/LOCATION + counter-unit-type reference FK; real
  `BundleWriter` tar ordering). *(seam: 2 migration ITs)* — deps T-04. **DONE: both ITs are now
  ENABLED + GREEN against real Postgres** (T-05a/b/c landed). They assert managing_club_id→migrated
  club, aircraft_type_id + counter-unit-type→real seed PKs, owner-person→migrated Person,
  homebase_id→club Location replica (fan-out disambiguator), children nested with resolved values.
  Authored `@Disabled("S-187a")` as the executable end-state spec; T-05c flipped them on.

  **S-187a absorbed as J-1 tasks (implementation-architect 2026-06-02: task-sized + additive,
  NOT a foundation journey — it reuses the proven composite fan-out lookup + reference-lookup
  resolver; the contract change mirrors the shipped `referenceLookups()` default-empty opt-in,
  so no existing mapper changes):**
- [x] **T-05a** — Resolver contract generalization: add a `default Map<String,EntityType>
  foreignKeyColumns()` (or `List<ForeignKeyColumn>`) to `Mapper`; teach
  `ForeignKeyResolver.rewriteForeignKeys` to resolve a target's column from the declaration when
  present, else fall back to `conventionalForeignKeyField` (`:244-246`); support two-columns-one-
  target (CLUB ← managing_club_id + owner_club_id) by iterating column→target pairs. Default empty
  → all 30 existing mappers unchanged. *(seam: Mapper + ForeignKeyResolver)*
  **DONE — contract shape for T-05b:** chose the record form (not `Map`) so two-columns-one-target
  composes and the fan-out disambiguator lands without another contract change.
  `Mapper.foreignKeyColumns(): List<ForeignKeyColumn>` (default empty). Record
  `ForeignKeyColumn(String column, EntityType target, @Nullable String disambiguatorColumn)` with a
  2-arg `(column, target)` convenience ctor (disambiguator = null). Resolver iterates declared
  bindings first, then convention-fills any `foreignKeys()` target NOT declared. For a fan-out
  target, `disambiguatorColumn` overrides the hardcoded `REFERENCER_CLUB_FIELD="club_id"` — so T-05b
  declares `new ForeignKeyColumn("homebase_id", LOCATION, "managing_club_id")` and the other three as
  the 2-arg form; no further resolver/contract change needed.
- [x] **T-05b** — AIRCRAFT FK column declarations + fan-out homebase disambiguator: override
  `foreignKeyColumns()` on `flight/AircraftMapper` (managing_club_id→CLUB, owner_club_id→CLUB,
  aircraft_owner_person_id→PERSON, homebase_id→LOCATION); make the fan-out disambiguator column
  configurable so `homebase_id` resolves via AIRCRAFT's own `managing_club_id` instead of the
  hardcoded `REFERENCER_CLUB_FIELD="club_id"` (`ForeignKeyResolver:68`). No producer wire change
  (managing_club_id already emitted). *(seam: AircraftMapper + resolver disambiguator plumbing)* — deps T-05a.
- [x] **T-05c** — counter-unit-type `legacy_int_id` seed + reference-lookup: `V25__counter_unit_type_legacy_int_id.sql`
  (ADD COLUMN, UPDATE legacy 1→HOURS_MINUTES / 2→HOURS_DECIMAL, UNIQUE index; column nullable —
  LANDINGS/STARTS have no legacy origin, diverging from V22's NOT NULL). Added the two counter-unit
  FKs to `AircraftMapper.referenceLookups()` (producer already emitted them). **Flipped both
  `@Disabled("S-187a")` ITs to enabled → GREEN.** Legacy keys confirmed from
  `flsserver/database/FLSTest/3 insert/3 Insert Static Data.sql` (CounterUnitTypes: 1=Minutes,
  2=2-decimals-per-hour) + `CounterUnitExtensions.cs` semantics. Enabling the ITs surfaced TWO
  more authored-but-never-run gaps the proof had been masking, fixed here (all the real cause, no
  assertion-weakening): (1) the T-05b fan-out homebase disambiguator read `managing_club_id` AFTER
  it was rewritten to the new-stack id, but the composite LOCATION map is legacy-keyed —
  `ForeignKeyResolver` now snapshots the disambiguator's pre-rewrite legacy value; (2)
  `AIRCRAFT_AIRCRAFT_STATE` (aggregate-internal leaf, no `legacy_guid`, surrogate `id` with no DB
  DEFAULT) was never given an `id` at ingest — `EntityStreamIngestor` now mints a UUID v7 surrogate
  for such leaves, and `BundleWriter`/`EntityType.emitsIdentityMap()` skip its identity pgcopy.
  *(seam: V25 + AircraftMapper.referenceLookups + the fan-out/surrogate-id ingest fixes the proof exposed)*
- [x] **T-06** — Migrated-admin profile completion (boyscout, blocks reaching /aircrafts):
  `KeycloakDeploymentDirectoryAdapter.provisionClubAdminIdentity` now sets firstName/lastName (5-arg
  signature; service supplies deterministic synthetic `Migrated`/`Admin` — the migrated admin is a
  per-Club *service identity*, not a legacy Person row, and Person streams drain AFTER provisioning,
  so no real Person name is available at the call site); removed the e2e `makeMigratedAdminLoginable`
  name fixup; reconciled the `KeycloakDeploymentDirectory` contract javadoc to scope best-effort+reconcile
  to the self-service-signup methods and document the migration path as fail-closed (no ADR governs it —
  contract lives in the port javadoc). *(seam: KeycloakDeploymentDirectoryAdapter + interface + service
  call site + e2e helper + doc)*
- [x] **T-07** — Aircraft parity bundle seeder (mirror `FanOutParityBundleSeeder`) + real-idp spec
  thicken: clean-seed real chain (Keycloak login, CRUD, cross-club edit 403, owner-person edit OK,
  S-164 redaction) + migrated-data render. *(seam: seeder + real-idp spec)* — deps T-02,T-03,T-04,T-05,T-06.
  **DONE (authored + typechecked + Java seeders compile; CI proof job is the gate — this Alpine/musl
  box cannot launch Playwright browsers).** Deliverables:
  `migrations/web/AircraftParityBundleSeeder.java` (synth migrated-aircraft bundle, byte-aligned with
  `AircraftMigrationRoundTripIT`) + `AircraftOwnerLinkSeeder.java` (S-163 owner-person DB-fixture seam —
  fixture state, NOT a mocked seam; access decision runs fully real); Gradle tasks `seedAircraftParityBundle`
  + `seedAircraftOwnerLink`; real-idp spec `aircraft-migration-parity.spec.ts` (clean-seed CRUD + 403 +
  S-163 + S-164, then synth-migrated render); wired into ci.yml `alpenflight-proof` (one playwright
  invocation alongside the J-0 Locations spec so the shared proof-manifest carries both journeys' videos).
  Real-bundle mode (`J1_BUNDLE_SOURCE=real` + `J1_REAL_*`) is honored end-to-end; the full legacy→export
  aircraft chain is a nightly fan-out-workflow follow-up (synth-migrated render at the PR gate, mirroring
  J-0c's synth-at-PR / real-at-nightly split).
- [x] **T-08** — Gallery roadmap `✅ `-prefix ordering fix + generator guard (boyscout:
  `generate-gallery.mjs` parseRoadmap regex + a generator spec). *(seam: generate-gallery.mjs + spec)*
- [x] **T-09** — Docker Hub image-pull bounded retry in the fanout + nightly workflows (boyscout).
  *(seam: .github/workflows/alpenflight-proof-fanout.yml + nightly.yml image-pull steps)*
- [x] **T-10** — Gate-revealed: fix the Spring Modulith boundary violation T-03 introduced.
  `ApplicationModulesTest.verifyModuleStructure()` fails — `aircraft` depends on non-exposed
  `users.domain.UserRepository`/`User`. No `@NamedInterface` convention in this repo → root-package
  types are exposed, sub-packages internal. Expose a caller-person-resolution API from `users`
  (root-package interface impl'd by `users.application`) and rewire `AircraftAccess.isOwnerPerson`
  to it (or match the codebase's dominant cross-module read pattern — cf. `me` module). Verify
  `verifyModuleStructure()` green + the S-163 `AircraftsAuthorizationIT` still passes.
  *(seam: users exposed API + AircraftAccess rewire + modulith test)* — my targeted local runs
  (aircraft tests only) never ran ApplicationModulesTest; the gate caught it.
- [ ] **T-11** — Gate-revealed: the real-idp proof job fails with
  `duplicate key value violates unique constraint "ux_user_username_lower_alive"` on the clean-seed
  `…club-b-admin@example.com`. The aircraft real-idp spec's club provisioning collides on username
  (T-07 fixture `provisionTwoClubs`) — likely a per-run uniqueness/idempotency gap when the aircraft
  spec runs in the same playwright invocation as the J-0 Locations spec, or a beforeAll re-provision
  on retry. Make the provisioned admin usernames per-spec/per-run unique (or idempotent), so both
  specs' clean-seed chains coexist. *(seam: aircraft-migration-parity.spec.ts + parity fixture)*

Then **§4 gate** (e2e-driver): full legacy→migrate→Keycloak→Playwright chain, both fidelities,
video retained. **§5**: prune body, flip done, post video.

## Assumptions made

- The AIRCRAFT migration mapper exists from J-0b's per-entity authoring but is **unproven
  end-to-end** ([[verify-infra-is-run-not-just-authored]]) — sized as build/verify, not wire.
- S-162/163/164's "backend done" is taken as provisional; the S-163 contradiction is a
  ship-time oracle question, not a carve-time guess.
