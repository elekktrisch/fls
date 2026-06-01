---
id: J-0c
title: Fan-out migration parity proof — legacy→migrate+Keycloak→AlpenFlight (UI + video)
epic: E-02
status: in_progress
started_at: 2026-06-01
journey0: false
carved: true
depends_on: [J-0, J-0b]
rolls_up: []   # pulls a Location-scope SLICE of S-028 + the T-10 CLUB-pgcopy fix (neither fully — see Notes); no story fully absorbed
acceptance:
  - In the legacy flsweb UI, a Location with a RANDOM unique name is created and referenced by 2 clubs; the create flow is recorded as a legacy parity video. [happy]
  - The real `alpenflight-export` CLI reads that legacy MSSQL read-only and emits an ALPF-encrypted bundle (incl. CLUB) that the server ingests WITHOUT the CLUB-pgcopy collision. [happy] (T-10 fix)
  - Migration provisions 2 clubs + the fanned-out Location (2 t_location rows, distinct ids per club) + a loginable Keycloak identity per migrated club admin. [happy]
  - In AlpenFlight, club-A admin logs in via real Keycloak → /locations shows the migrated Location under its RANDOM name; club-B admin logs in → sees its OWN copy with the same name. [happy]
  - Editing club-A's copy (rename) leaves club-B's copy unchanged — proving the two are DISTINCT fanned-out rows, not one shared row. [key-behavior]
  - Cross-tenant GET of the other club's migrated Location 404s (tenant isolation holds on migrated data). [key-error]
  - Per-club AlpenFlight videos + the legacy create video land in the gh-pages proof gallery, captioned + J-0c-tagged (the human-parity half of the done bar). [happy]
screen: reuses J-0's /locations (replacing legacy masterdata/locations/) — driven against MIGRATED data; NO new SPA screen
headless_pulled_in: legacy-stack-up + legacy create-flow + real export + migrate + Keycloak-provision-on-migrate — all homed on THIS proof journey's CI chain
migration: Location (the FULL real chain — legacy MSSQL → export → ingest → AlpenFlight; J-0b proved the keying at the data layer, J-0c proves it through the real UI on real legacy data)
parity_test: alpenflight/web/e2e/tests/real-idp/fan-out-migration-parity.spec.ts (AlpenFlight side) + a legacy flsweb create-flow spec under the e2e legacy suite (records the legacy video)
adr_refs: [0008, 0007, 0003, 0022]
---

## Context

J-0b proved the fan-out keying at the **data layer** (a server IT: shared legacy
Location → N distinct per-club rows, club-aware FK). But a server IT isn't
glanceable and doesn't exercise the real UI — under-delivering on the do-ship
done bar ("…then on real legacy data migrated into AlpenFlight") and leaving the
proof gallery showing J-0b "pending." J-0c closes that: it drives the **whole
chain through real UIs** — create a Location in the legacy `flsweb` UI, migrate
it (real export + real ingest + Keycloak), and show in AlpenFlight that each club
sees its own migrated copy, edit-isolated — capturing **side-by-side legacy +
AlpenFlight videos** as the acceptance artifact. Operator priority (2026-06-01):
this runs **before J-1**. It depends on **Location only**, so it does NOT wait on
J-1..J-10; J-21's all-entity wizard later reuses J-0c's harness. See
[[feedback-demonstrable-proof-prefer-ui]].

## Spec must assert

The contract is the full legacy→AlpenFlight chain, end to end, with a
**random-named** Location as the freshness guarantee (proves data actually flowed,
not pre-seeded):

1. **Legacy create (video).** In `flsweb` (`masterdata/locations/`), create a
   Location with a random unique name (e.g. `J0C-<rand>`) and make it referenced
   by **2 clubs** — legacy shares one global `Location` row across clubs via
   `Clubs.HomebaseId` (the exact union the `LocationMapper` SELECT fans out on:
   `Clubs.HomebaseId` ∪ `Flights.Start/LdgLocationId` ∪ `Aircrafts.HomebaseId`).
   Record the legacy create flow as the parity video. *(Exact field-by-field
   legacy create flow + how to set homebase on 2 clubs → ship-time `legacy-oracle`.)*
2. **Real export.** `alpenflight-export --jdbc-url …` (read-only) → ALPF-encrypted
   bundle containing CLUB + LOCATION (+ deps). This is the real producer path —
   it WILL emit a `legacy_id_map/CLUB.pgcopy`, so the **T-10 CLUB-pgcopy collision
   must be fixed** for the bundle to ingest (see Notes).
3. **Migrate + Keycloak.** POST the bundle to `/api/v1/migrations/{id}/bundle` →
   provisions 2 clubs + the fanned-out Location (2 `t_location` rows, distinct ids,
   one per club) + a **loginable Keycloak identity per migrated club admin**.
4. **AlpenFlight view (videos).** Club-A admin logs in via real Keycloak →
   `/locations` shows the migrated Location under its random name. Club-B admin
   logs in → sees its OWN copy, same name. **Edit-isolation:** rename club-A's copy
   → club-B's is unchanged (distinct rows). **Cross-tenant:** GET club-B's Location
   id as club-A → 404. Record per-club videos.
5. **Gallery.** Legacy + AlpenFlight videos land captioned + J-0c-tagged in the
   gh-pages gallery — side-by-side parity, the human half of the done bar.

The J-0b server ITs stay the data-layer regression guard; J-0c is the UI/parity
layer on top.

## Notes

**This journey's proof = a green full-chain run + the gallery videos.** It reuses
J-0's `/locations` screen and `two-club-fixture` harness; the new work is the
*legacy half* + the *migrate-with-Keycloak half* + the *video capture*, not a new
screen.

**⚠ Heavy stack — likely NOT a per-PR required gate.** The legacy stack
(Mono/xbuild `flsserver` + Node8 `flsweb` + MSSQL) only runs in `nightly` today,
not per-PR. The full J-0c chain almost certainly blows the per-PR ~8-min budget.
Expect a **dedicated proof workflow** (manual-dispatch + nightly tier) that
publishes the video to the gallery, while the per-PR required gate for J-0c's
*code* (the CLUB-pgcopy fix + the Keycloak-provision slice) stays unit/IT-level.
`/do-ship` should confirm the gate shape with `e2e-driver` at ship time.

**Fork — "including Keycloak" (operator wording):** record both, decide at ship.
- *(a) Minimal provision-on-migrate slice (honors the wording).* On ingest, for
  each migrated club admin, create a Keycloak user (clubId attribute = the
  provisioned club UUID + `UPDATE_PASSWORD` action), reusing S-138's Keycloak
  admin-client wiring. This is a **thin slice of S-028** — NOT full S-028 (skip
  the bulk admin endpoint, dry-run, role-mapping S-026, mail S-082, UI, audit).
  Stamp nothing as rolled up; S-028 stays `todo` for J-21.
- *(b) Test-harness `createUser` (lighter, declared seam).* The proof spec mints
  the 2 Keycloak users via the existing `_helpers/keycloak-admin`
  `createUserWithAttributes` with the **migrated** club UUID as `clubId` — tenancy
  resolves off the claim (J-0's fixture already does exactly this; no `t_user` seed
  needed). The migration then isn't literally "including Keycloak," but the
  fan-out is still proven through real login on real migrated data.
  **Default: (a)** to match the operator's intent; fall back to (b) (declared
  `@mocked: keycloak-provision` seam) if the slice balloons toward full S-028.

**Likely task seams (non-binding, seam granularity for `/do-ship`):**
- *CLUB-pgcopy fix (the T-10 follow-up, now on the critical path):* either the
  producer skips the CLUB identity pgcopy in `BundleWriter.assembleTarGz`, or the
  ingest reconciles `copyLegacyIdMap` ↔ `seedClubLegacyIdMap` via `ON CONFLICT`
  on `legacy_id_map_club`. One seam (migration-tool OR server ingest).
- *Keycloak-provision-on-migrate slice:* one backend seam in the migration ingest
  service (reuse S-138 admin-client wiring) — fork (a) above.
- *Legacy-stack proof workflow:* one CI seam — bring up MSSQL + `flsserver` +
  `flsweb` (reuse `nightly.yml`'s legacy build + `extract.yml`'s MSSQL patterns),
  run the chain, retain videos.
- *Legacy create-flow spec:* one Playwright spec against `flsweb`
  (`masterdata/locations/` + set homebase on 2 clubs) — records the legacy video.
- *Export step:* one CI step — run `alpenflight-export` against the seeded legacy
  MSSQL → bundle.
- *AlpenFlight parity spec:* one real-idp Playwright spec — per-club login + view
  migrated Location + edit-isolation + cross-tenant 404, annotated
  (`proof-journey: J-0c`, `proof-caption: …`) for the gallery.
- *Gallery legacy-video source:* the gallery may need to accept a **legacy**
  parity video alongside the AlpenFlight one (a J-24/J-25 touch) — confirm whether
  `generate-gallery.mjs` can caption a non-AlpenFlight video, else a thin extension.

## Assumptions made

1. **Proof = full-chain green + gallery videos**, reusing J-0's `/locations`
   screen — no new SPA screen. The "one green Playwright run" bar is met by the
   AlpenFlight parity spec; the legacy spec is the parity-video aid.
2. **Default to the real Keycloak-provision-on-migrate slice** (fork (a)); fall
   back to the declared test-harness seam (b) only if (a) balloons. Either way the
   fan-out is proven through real Keycloak login on real migrated data.
3. **The full chain runs on a dedicated/nightly proof workflow**, not the per-PR
   required gate (legacy stack is too heavy for ~8 min). J-0c's per-PR gate is the
   code (CLUB-pgcopy fix + Keycloak slice) at unit/IT level. `e2e-driver` confirms
   the gate shape at ship time.
4. **Legacy "Location referenced by 2 clubs" = set it as `Clubs.HomebaseId` on 2
   clubs** (the fan-out trigger the `LocationMapper` SELECT already unions). Exact
   flsweb create flow → ship-time `legacy-oracle`.
5. The CLUB-pgcopy fix is **owned here** (J-0c) because J-0c is the first journey
   to ingest a real full bundle (incl. CLUB); J-21 inherits the fix.

## Ship-time grounding (2026-06-01, decided)

- **Keycloak approach = fork (a), feasible.** `USER` is a migrated `EntityType` and
  there's a **JIT materializer** (`JitUserMaterializer`/`JitUserMaterializationFilter`,
  S-169): a Keycloak identity carrying the migrated club's `clubId` claim
  JIT-projects a `t_user` on first login — no pre-seeded user row needed. The server
  has a reusable **`KeycloakAdminClient`** + `KeycloakDeploymentDirectoryAdapter`
  (S-138), so provisioning a club-admin identity on migrate is a real slice, not a
  full-S-028 rebuild.
- **Build order de-risks the heavy legacy stack last:** land the IT-provable code
  (CLUB-pgcopy, Keycloak slice) → prove the AlpenFlight UI half on a *synthesized*
  migrated bundle (no legacy stack) → then the heavy legacy half (stack workflow +
  create-flow spec + export) → wire the full chain + gallery. The legacy stack is
  nightly-tier; `e2e-driver` owns the chain tasks and confirms the gate shape.

## Tasks

Ordered, one seam each. Code tasks → general-purpose `/do-task`; proof-chain/
Playwright tasks → `e2e-driver`. Workers commit to `integration/J-0c`.

- [x] **T-01 — CLUB-pgcopy collision fix.** `BundleWriter.assembleTarGz`
  (`migration-tool`) emits a `legacy_id_map/CLUB.pgcopy` that collides with the
  orchestrator's `seedClubLegacyIdMap` on `legacy_id_map_club_pkey` (T-10 finding).
  Fix: producer **skips** the CLUB identity pgcopy (CLUB reconciles via
  `seedClubLegacyIdMap`, not a bundle map) — or `copyLegacyIdMap` reconciles via
  `ON CONFLICT`. Extend `LocationRealProducerRoundTripIT` to drive **CLUB** through
  the real `assembleTarGz` green. *(seam: BundleWriter + IT; unblocks the real bundle)*
- [ ] **T-02 — Keycloak-provision-on-migrate slice (minimal).** On ingest, after
  `provisionDeployment`, provision a **Keycloak club-admin identity per migrated
  club** (`clubId` attr = provisioned club UUID, `CLUB_ADMINISTRATOR` realm role,
  `UPDATE_PASSWORD` action) reusing `KeycloakAdminClient`/`KeycloakDeploymentDirectory
  Adapter`. NOT full S-028 (no bulk endpoint/UI/dry-run/mail/audit/role-map S-026).
  *(seam: migration ingest service + Keycloak adapter; gap-hunter checks it stays a slice)*
- [ ] **T-03 — AlpenFlight parity spec (synth bundle first).** `e2e-driver`: real-idp
  Playwright `fan-out-migration-parity.spec.ts` — seed a **synthesized** fan-out
  bundle via the real `/api/v1/migrations` endpoint (reuse J-0b's bundle-build) →
  club-A admin logs in, sees migrated Location (random name); club-B sees its own;
  rename A → B unchanged; cross-tenant GET 404. Annotated `proof-journey: J-0c`.
  Proves the UI half WITHOUT the legacy stack (fast inner loop). *(seam: one spec)*
- [ ] **T-04 — Legacy create-flow spec (flsweb).** `e2e-driver` (+ `legacy-oracle`
  for the exact flow): Playwright against legacy `flsweb` `masterdata/locations/` —
  create a random-named Location, set as `Clubs.HomebaseId` on 2 clubs; record the
  legacy parity video. *(seam: one legacy spec)*
- [ ] **T-05 — Legacy-stack proof workflow + export + full-chain wire.** `e2e-driver`:
  a dedicated/nightly CI workflow — bring up MSSQL + `flsserver` + `flsweb` (reuse
  `nightly.yml` builds + `extract.yml` MSSQL) → run T-04 (create + video) → run
  `alpenflight-export` against the legacy MSSQL → POST the real bundle to AlpenFlight
  migrate (+ T-02 Keycloak) → run T-03 against the REAL migrated data → retain videos.
  **Likely to split** (workflow-scaffold / export-step / chain-wire) — heaviest seam;
  e2e-driver confirms the gate shape (nightly vs per-PR). *(seam: the proof chain)*
- [ ] **T-06 — Gallery: legacy + AlpenFlight videos, J-0c-captioned.** Ensure both
  videos land captioned + `J-0c`-tagged; extend `generate-gallery.mjs` if it can't
  caption a non-AlpenFlight (legacy) video. *(seam: gallery generator + fixture)*

**Order:** T-01 → T-02 → T-03 → T-04 → T-05 → T-06.
