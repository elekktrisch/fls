---
id: J-0b
title: Migration fan-out foundation — (legacy_guid, club_id) → distinct new_id
epic: E-02
status: in_progress
started_at: 2026-06-01
journey0: false
carved: true
depends_on: [J-0]
rolls_up: [S-016, S-189]   # S-016 done (re-assert only); S-189 = audit-tenancy secondary slice
acceptance:
  - LocationMigrationRoundTripIT re-enabled (@Disabled removed) and GREEN against real Postgres ingest. [happy]
  - One shared legacy Location referenced by 2 clubs ingests to 2 t_location rows, one per club_id, with DISTINCT ids — no 23505 PK collision. [edge — the collision J-0 escalated on]
  - Each fanned-out row's reference-FK columns (location_type_id / elevation_unit_type_id / runway_length_unit_type_id) resolve to the real V3/V22 seed PK, not the synthetic UUID. [happy] (T-02a regression)
  - The child InOutboundPoint attaches to a fanned-out parent Location, inheriting that replica's tenancy. [happy] (T-02b regression)
  - A downstream FK referencing the shared legacy Location resolves to the replica in the referencer's OWN club (not an arbitrary replica). [key-behavior]
  - J-0's existing Locations Playwright proof (tests/real-idp/locations-crud-tenant-isolation.spec.ts) still passes on clean-seed — no regression to the green gate. [happy]
screen: none — migration foundation (infra journey, like J-24/J-25); proof is a green server IT, not a new SPA screen
headless_pulled_in: the (legacy_guid, club_id) → distinct new_id fan-out keying — the shared mechanism every tenant-scoped masterdata migration reuses (Location is the first to exercise it)
migration: the fan-out subsystem itself + a t_location.legacy_guid Flyway column. Location is the proving entity; the keying generalizes to every fanned-out tenant-scoped entity.
parity_test: alpenflight/server/src/test/java/ch/alpenflight/migrations/web/LocationMigrationRoundTripIT.java (primary); tests/real-idp/locations-crud-tenant-isolation.spec.ts (regression guard)
adr_refs: [0008, 0003, 0022]
---

## Context

J-0 dragged the full proof chain into existence but its *live* migrate proof
surfaced that the core migration primitive is unbuilt: legacy tenant-scoped
masterdata is **shared** (one `Locations` row referenced by many clubs), but the
new stack is **tenant-partitioned** (`t_location` carries `club_id` per ADR
0008). So one legacy Location must *fan out* into N rows — one per referencing
club, each with a distinct `id` — while a downstream FK (a Flight's
`start_location_id`) must resolve to **its own club's** replica. The current
ingest maps the legacy id verbatim to `t_location.id`, so the 2nd replica
PK-collides (`sqlstate=23505`). J-0 narrowed to a clean-seed real chain and
deferred the fan-out subsystem here. **Every later journey's migrated-data
fidelity depends on this journey** — it builds the shared keying mechanism, and
Location is just the first (lowest-risk: tenant-scoped, no inbound FKs) entity
to exercise it.

## Spec must assert

The contract is the already-written, currently-`@Disabled` server IT
`LocationMigrationRoundTripIT` — it builds an encrypted bundle and POSTs it
through the **real** `/api/v1/migrations/{uploadId}/bundle` endpoint against real
Postgres (not the parity `ConsumerHarness` stand-in), so the fan-out keying,
`ForeignKeyResolver`, and `ReferenceLookupResolver` all run live. Re-enabling it
green is the journey's gate. It proves three load-bearing invariants:

1. **(a) Fan-out distinct ids.** The shared legacy Location (`legacy_guid`
   identical, `club_id` distinct across two NDJSON rows — exactly what the
   `LOCATION` producer SELECT emits when `Clubs.HomebaseId` references it from 2
   clubs) lands as **2 `t_location` rows, one per `club_id`, with distinct
   `id`s**. Asserted at `LocationMigrationRoundTripIT.java:200-243`. This is the
   exact case that fails today with `500 INGEST_INTERNAL_ERROR sqlstate=23505`.
2. **(b) Reference-FK resolve.** Each replica's `location_type_id` /
   `elevation_unit_type_id` / `runway_length_unit_type_id` equals the real V3/V22
   seed PK, not the synthetic `new UUID(0, legacyIntId)` (`:245-258`). This
   already works (T-02a `ReferenceLookupResolver`); the IT re-asserts it survives
   fan-out.
3. **(c) Child nesting + tenancy.** The child `InOutboundPoint` attaches to a
   fanned-out parent replica, inheriting its `club_id` (`:260-280`).

Plus the **club-aware FK-resolution** invariant the IT's fan-out shape implies
(and which `/do-ship` should add an assertion for if not already covered): a
referencer in club A pointing at the shared legacy Location resolves to **club
A's** replica id, not club B's.

The J-0 Playwright proof stays the regression guard — it must remain green on the
clean-seed gate (J-0b does not wire legacy-MSSQL→migrate into the Playwright CI
job; see Notes).

## Notes

**Current-state gap (the three unbuilt pieces, file:line) —** confirmed against
HEAD; the `LocationMapper` doc lines 29-32 describe the composite keying as if it
exists ("S-141 temp-table change") but it is **not wired**:

1. **No `legacy_guid` column on `t_location`.** The wire `legacy_guid` maps
   verbatim to `t_location.id` via `destinationColumnNames`
   (`EntityStreamIngestor.java:256-263`). There is no column to hold the shared
   legacy key separate from a distinct minted `id`. No migration adds one
   (V3 creates `t_location`, V7 adds `club_id`).
2. **id-map temp table is single-key.** `createTemporaryIdMapTables` builds
   `(legacy_guid uuid PRIMARY KEY, new_uuid uuid NOT NULL)`
   (`EntityStreamIngestor.java:65-66`) — one `legacy_guid` can map to only **one**
   `new_uuid`, so a fanned-out entity cannot record its N replica ids.
3. **`LocationMapper.writeNdjson:123` mints no per-replica id** — it emits the
   shared `LocationId` as `legacy_guid` for every replica. Combined with the
   verbatim `legacy_guid → id` mapping, the 2nd replica collides on the
   `t_location.id` PK.

**The hidden fourth piece — club-aware FK resolution.** `ForeignKeyResolver.
rewriteForeignKeys` (walked from `EntityStreamIngestor.ingestEntityNdjson:191`)
resolves FK columns through the flat `legacy_id_map_<entity>` table. Today a
downstream FK to a Location needs no lookup at all — `legacy_guid == id`, an
identity. Once fan-out mints distinct replica ids that identity **breaks**: the
`legacy_id_map_location` must be (a) *populated* as a side-effect of LOCATION's
own INSERT (it currently never is — only CLUB via `seedClubLegacyIdMap` and
SYSTEM_GLOBAL via pgcopy are seeded; see `MigrationBundleIngestService.java:450-
451`), and (b) *keyed composite* so a referencer resolves `(legacy LocationId,
referencer's club) → that club's replica id`. This is the load-bearing new
behavior and the riskiest part of the design.

**⚠ Design pass before build (per roadmap).** This journey wants an
`implementation-architect` consult on the keying approach *before* `/do-ship`
decomposes it — the fork is real:
- **Mint-vs-derive:** producer mints a distinct replica `id` and emits a composite
  id-map row, *vs.* ingest derives `id = uuidv5(legacy_guid, club_id)`
  deterministically (no producer mint; reproducible re-ingest).
- **All-entities-vs-fan-out-only:** make the id-map composite for *every* entity
  (uniform, but invasive — pgcopy maps, `LegacyIdMapWriter`, `LegacyIdMapTables`
  all change shape), *vs.* only for entities flagged fan-out (needs per-entity
  metadata on `EntityType`/`EntityPolicy`; CLUB/SYSTEM_GLOBAL/identity entities
  stay 2-column). The narrower option keeps `LegacyIdMapWriter`'s 2-column pgcopy
  format (used by SYSTEM_GLOBAL bundle entries + the IT's `pgcopyMap` helper)
  untouched — likely lower blast radius.
The architect picks; `/do-plan` does not resolve it.

**Likely task seams (non-binding, seam granularity for `/do-ship`):**
- *Flyway:* one migration adding `t_location.legacy_guid` (+ the
  `(legacy_guid, club_id)` identity-bearing partial UNIQUE per CLAUDE.md directive
  2 — structural identity invariant, not business logic).
- *Producer:* `LocationMapper` + `MapperLegacyBindings.LOCATION` + `LegacyIdMapWriter`/
  `LegacyIdMapTables` — replica-id minting + composite id-map emission (one seam).
- *Ingest keying:* `EntityStreamIngestor.createTemporaryIdMapTables` +
  `destinationColumnNames` — composite temp-table + `legacy_guid` as its own
  destination column (one seam).
- *FK resolution:* `ForeignKeyResolver` — club-aware replica selection for
  fan-out targets + post-INSERT population of the fan-out entity's id-map (one
  seam; the architecturally load-bearing one).
- *Proof:* re-enable `LocationMigrationRoundTripIT` (remove `@Disabled`); add the
  club-aware-FK assertion if not covered (one seam).

**Proof shape — green IT, not a new Playwright run.** Consistent with the infra
journeys J-24/J-25, J-0b's gate is the green server IT (it exercises the real
ingest pipeline end-to-end at the data layer, which is where fan-out lives) plus
no regression to J-0's clean-seed Playwright gate. Wiring legacy-MSSQL→migrate
into the *Playwright* CI chain (so the SPA spec runs against migrated rather than
clean-seed data) is heavier and belongs to J-21's migrate wizard, not here —
recorded as an assumption, not silently dropped.

**S-189 (secondary slice).** S-189 (back-fill `tenant_club_id` on
`LEGACY_MIGRATED` audit rows) is the migration-tenancy slice the roadmap rolled
up here. It's adjacent (audit-row tenancy under migration) but not on the
fan-out critical path. Per [[feedback-vertical-slices-first]]: build it with the
fan-out slice if it stays thin; if it bloats the journey, drop it to a named
follow-up rather than blocking the fan-out proof. S-016 is `implemented/` — its
parity-oracle harness is re-asserted by the IT, not rebuilt.

## Assumptions made

1. **Gate = green `LocationMigrationRoundTripIT` + J-0 Playwright regression.**
   J-0b is an infra/foundation journey (no SPA screen); its proof is the real-
   ingest server IT, matching how J-24/J-25 are CI-infra journeys. The "one green
   Playwright run" quality bar is satisfied by J-0's existing spec staying green.
2. **legacy-MSSQL→migrate→Playwright full chain stays deferred** (to J-21's
   migrate wizard). J-0b proves fan-out at the data layer via the IT, which
   synthesizes the NDJSON bundle directly — no MSSQL legacy-up needed in CI.
3. **Fan-out-only composite keying is the likely-lower-blast-radius option**, but
   the `implementation-architect` adjudicates mint-vs-derive and
   all-entities-vs-fan-out-only before `/do-ship` decomposes.
4. S-189 is a droppable secondary slice (see Notes); the fan-out proof is the
   journey's reason to exist.

## Implementation-architect decision (2026-06-01)

Both forks resolved before decomposition:

- **Fork 1 → Derive.** The producer computes `id = uuidv5(NS, legacy_guid + legacy
  club_id)` in `writeNdjson` (it already holds the legacy join — `fanout.ClubId`
  is in the `LocationMapper` SELECT) and emits a composite id-map row. Deterministic
  → idempotent re-ingest (re-POST UPSERTs, matching CLUB's `ON CONFLICT` at
  `EntityStreamIngestor.java:239`). **Do NOT derive in ingest** — ingest only sees
  the *provisioned* (new) club id, never the legacy club id, so it can't reproduce
  the namespace input. Key strictly on the **legacy** club id (stable producer↔
  referencer); pin the uuidv5 namespace as a constant.
- **Fork 2 → Fan-out-only**, gated by `EntityType.fansOut()` / a `Set<EntityType>
  FAN_OUT` in the bundle module. Fan-out entities get composite `(legacy_guid,
  club_id) PK` temp tables + 3-column pgcopy; CLUB/SYSTEM_GLOBAL/identity keep the
  existing 2-column format (`LegacyIdMapWriter` `FIELD_COUNT_PER_ROW=2`) untouched —
  S-183…S-189 already shipped against it. Decisive on blast radius.
- **Club-aware FK resolution (the load-bearing part).** ⚠ **Carve/`InOutboundPoint
  Mapper` javadoc (lines 28-31) correction:** fanning out the child row alone does
  NOT disambiguate the parent — the legacy GUID is *shared* across replicas, so the
  child still can't say which replica id it means. Fix: **the child carries its own
  `club_id` on the wire** (producer fans out the child too, one row per (legacy IOP,
  legacy club)); `ForeignKeyResolver.rewriteForeignKeys` keys the composite lookup
  on `(location_id=legacy LocationId, club_id=child's own legacy club)`. The LOCATION
  id-map is populated via a **producer-emitted 3-column `legacy_id_map/LOCATION`
  pgcopy**, ordered before `INOUTBOUND_POINT.ndjson` by the existing topo order —
  no post-INSERT `RETURNING` plumbing. Fail-closed on a composite miss (mirror the
  SYSTEM_GLOBAL path `ForeignKeyResolver.java:87-94`); the aircraft-homebase
  lowest-UUID fallback the `LocationMapper` javadoc mentions is **out of scope**
  (Aircraft is J-1).
- **Most load-bearing single line:** `EntityStreamIngestor.destinationColumnNames`
  (`:256-263`) currently aliases wire `legacy_guid → id`. For fan-out entities it
  must emit **both** `legacy_guid` (verbatim) **and** `id` (the derived value) as
  separate destination columns. Audit every reader that assumes a LOCATION row's
  `id == legacy GUID` (parity harness, `MapperVsSchemaCompatibilityTest`).

## Tasks

Ordered, one seam each (architect's strict dependency order 1→…→8). Workers commit
directly to `integration/J-0b`. Sized per the do-ship gate.

- [x] **T-01 — Proof IT → correct target shape (red contract), keep `@Disabled`.**
  Edit `LocationMigrationRoundTripIT`: `inoutboundPointNdjson` emits **two** club-
  tagged child rows (add `club_id` field), and add the explicit **club-aware-FK
  assertion** (a referencer in club A resolves to club A's replica id, not B's).
  Correct the `InOutboundPointMapper` javadoc (lines 28-31). Keep `@Disabled` — this
  commits the contract shape, T-08 makes it green. *(seam: the proof IT + 1 javadoc)*
- [x] **T-02 — Flyway: `t_location.legacy_guid` + composite identity UNIQUE.**
  One `V-next` migration: add `legacy_guid UUID` + identity-bearing partial UNIQUE
  `(legacy_guid, club_id) WHERE deleted_on IS NULL` (structural, ADR 0022 directive
  2). *(seam: one migration)*
- [ ] **T-03 — Fan-out primitives (migration-bundle).** `EntityType.fansOut()` /
  `FAN_OUT` set; `Coercions` uuidv5 helper (pinned namespace const); `LegacyIdMapWriter`
  3-arg `write(legacyGuid, clubId, newUuid)` overload (keep 2-arg) + a 3-column pgcopy
  round-trip unit test. *(seam: shared producer primitives)*
- [ ] **T-04 — LocationMapper fan-out producer.** `writeNdjson` derives `id`, emits
  `legacy_guid` + `club_id`; `MapperLegacyBindings.LOCATION` SELECT carries
  `legacy_guid`; emit the LOCATION 3-column id-map entry. *(seam: the Location producer)*
- [ ] **T-05 — InOutboundPointMapper fan-out producer.** Child `writeNdjson` emits its
  own `club_id` + per-club fan-out; `MapperLegacyBindings` IOP SELECT joins the same
  fan-out partner set. *(seam: the child producer)*
- [ ] **T-06 — Ingest fan-out keying.** `EntityStreamIngestor`: `destinationColumnNames`
  de-alias (emit both `legacy_guid` + `id` for fan-out entities); composite
  `(legacy_guid, club_id)` temp-table DDL in `createTemporaryIdMapTables`; 3-column
  COPY in `copyLegacyIdMap` — all gated on the fan-out flag. *(seam: ingest side; deps
  T-02, T-03)*
- [ ] **T-07 — Club-aware FK resolution.** `ForeignKeyResolver.rewriteForeignKeys` +
  `lookupOrNull`: composite `(legacy_guid, club_id)` branch for fan-out targets, reading
  the referencer row's own `club_id`; fail-closed on a composite miss. *(seam:
  ForeignKeyResolver — the load-bearing one; deps T-06)*
- [ ] **T-08 — Enable + green the proof IT.** Remove `@Disabled`; run
  `LocationMigrationRoundTripIT` green against real Postgres; close any integration
  gap surfaced. *(seam: the proof; deps all)*
- [ ] **T-09 — (optional, droppable) S-189 audit tenant-backfill.** Build only if it
  stays thin per [[feedback-vertical-slices-first]]; else defer to a follow-up story.

**Order:** T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08 → (T-09).
