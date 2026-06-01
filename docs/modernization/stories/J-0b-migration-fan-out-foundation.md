---
id: J-0b
title: Migration fan-out foundation — (legacy_guid, club_id) → distinct new_id
epic: E-02
status: todo
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
