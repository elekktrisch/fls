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

**Gate-shape decision (T-05, CONFIRMED 2026-06-01, e2e-driver).** The full
chain is a **dedicated workflow** — `alpenflight-proof-fanout.yml`,
`workflow_dispatch` + nightly `cron`, NO `pull_request` trigger — and is
deliberately NOT added to `ci.yml`'s `required` aggregator. Why: the legacy
half is Mono `flsserver` (xbuild + NuGet restore) + Node8 `flsweb` (webpack-dev-
server) + MSSQL seed, which `nightly.yml` already isolates from the per-PR lane;
stacking it on top of the AlpenFlight half (Postgres + Keycloak + backend
bootJar + 2× `ng serve`) far exceeds the ~8-min PR budget (the job's own
`timeout-minutes: 45`). The per-PR gate for J-0c's CODE stays inside `ci.yml`
at unit/IT level (T-01 `LocationRealProducerRoundTripIT` drives the CLUB-pgcopy
fix; T-02's Keycloak slice is IT-covered; `alpenflight-proof` keeps proving the
clean-seed UI half). A cross-workflow job can't be a `needs:` dependency anyway,
so even if we wanted it required, it would have to live inside `ci.yml` — which
would re-import the heavy legacy stack into every PR. The dedicated workflow is
the only shape that keeps PR feedback fast AND proves the full chain on a
schedule.

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
- [x] **T-02 — Keycloak-provision-on-migrate slice (minimal).** On ingest, after
  `provisionDeployment`, provision a **Keycloak club-admin identity per migrated
  club** (`clubId` attr = provisioned club UUID, `CLUB_ADMINISTRATOR` realm role,
  `UPDATE_PASSWORD` action) reusing `KeycloakAdminClient`/`KeycloakDeploymentDirectory
  Adapter`. NOT full S-028 (no bulk endpoint/UI/dry-run/mail/audit/role-map S-026).
  *(seam: migration ingest service + Keycloak adapter; gap-hunter checks it stays a slice)*
- [x] **T-03 — AlpenFlight parity spec (synth bundle first).** `e2e-driver`: real-idp
  Playwright `fan-out-migration-parity.spec.ts` — seed a **synthesized** fan-out
  bundle via the real `/api/v1/migrations` endpoint (reuse J-0b's bundle-build) →
  club-A admin logs in, sees migrated Location (random name); club-B sees its own;
  rename A → B unchanged; cross-tenant GET 404. Annotated `proof-journey: J-0c`.
  Proves the UI half WITHOUT the legacy stack (fast inner loop). *(seam: one spec)*
- [x] **T-04 — Legacy create-flow spec (flsweb).** `e2e-driver` (+ `legacy-oracle`
  for the exact flow): Playwright against legacy `flsweb` `masterdata/locations/` —
  create a random-named Location, set as `Clubs.HomebaseId` on 2 clubs; record the
  legacy parity video. *(seam: one legacy spec)*
  Landed: `e2e/tests/masterdata/locations-fanout-J0c.spec.ts` in the existing
  legacy e2e suite (top-level `e2e/`, `masterdata` project) — reuses `loginViaUi`
  / `waitForLoggedInState` / `gotoRoute` from `e2e/fixtures.ts`, no new harness.
  Flow: login as `testclubadmin` → create `J0C-<rand>` Location via
  `#/masterdata/locations/new` (form `locationForm`, selectizes set via $scope per
  TEST_WRITING.md §6) → set it as TestClub's `HomebaseId` on the club edit form
  (`#HomebaseId`) → login as `othertestadmin` → set the SAME global Location as
  OtherClub's `HomebaseId` (2 clubs, 1 Location = the fan-out trigger) → API
  readback asserts BOTH `Clubs.HomebaseId` point at it. `test.use({ video: 'on' })`
  records the parity video; per-context `recordVideo` for the continuous flow.
  STRUCTURAL-only: tsc clean + `playwright test --list` discovers it; first LIVE
  green is T-05's legacy proof workflow (Mono+Node8+MSSQL stack only runs in CI).
- [x] **T-05 — Legacy-stack proof workflow + export + full-chain wire.** `e2e-driver`:
  a dedicated/nightly CI workflow — bring up MSSQL + `flsserver` + `flsweb` (reuse
  `nightly.yml` builds + `extract.yml` MSSQL) → run T-04 (create + video) → run
  `alpenflight-export` against the legacy MSSQL → POST the real bundle to AlpenFlight
  migrate (+ T-02 Keycloak) → run T-03 against the REAL migrated data → retain videos.
  **Likely to split** (workflow-scaffold / export-step / chain-wire) — heaviest seam;
  e2e-driver confirms the gate shape (nightly vs per-PR). *(seam: the proof chain)*
  Landed as ONE seam (the chain + its bundle-source contract — see T-05 note):
  - `.github/workflows/alpenflight-proof-fanout.yml` — dedicated workflow,
    `workflow_dispatch` + nightly `cron: '0 5 * * *'`, NOT a `pull_request`
    trigger and NOT wired into `ci.yml`'s `required` aggregator (gate-shape
    decision below). Three jobs: `legacy-server-build` + `legacy-web-build`
    (inlined from `nightly.yml` — a workflow can't `needs:` a cross-file job)
    → `fanout-proof` (`needs:` both). The proof job runs the full chain on ONE
    runner (no port collisions: MSSQL 1433 / legacy API 25567 / flsweb 3000 /
    Postgres 5432 / Keycloak 8090 / AlpenFlight backend 8080 / SPA 4201): pin
    a chain-wide random name → legacy up + seed + T-04 (legacy video) →
    AlpenFlight up → mint handshake (SPA-driven) → `alpenflight-export`
    (read-only) sealed to that handshake → T-03 in real-bundle mode (per-club
    videos) → gallery + retain ALL videos → main-only gh-pages deploy.
  - `fan-out-parity-fixture.ts` — bundle source made swappable, keyed off
    `J0C_BUNDLE_SOURCE`: `synth` (default, Gradle seeder — fast inner loop /
    T-03) vs `real` (reads the workflow's handshake JSON + `.enc` from
    `J0C_REAL_HANDSHAKE_FILE` / `J0C_REAL_BUNDLE_FILE`, asserts on
    `J0C_REAL_LOCATION_NAME`). The shared tail (principal bearer → bundle POST
    → fan-out + Keycloak provision → `makeMigratedAdminLoginable`) is identical
    across both modes. Exported `mintRealHandshakeToFile` for the workflow.
  - `mint-handshake.spec.ts` (new real-idp spec) — workflow-only: mints the
    handshake to `J0C_HANDSHAKE_OUT`; `test.skip` when the env var is absent so
    it never gates ordinary real-idp runs. Needed because the `alpenflight-web`
    SPA client has no direct-access grant (no curl-token path to a
    verified-email principal JWT), and the export must seal the bundle to a
    handshake BEFORE the parity spec ingests the same `uploadId`.
  - `locations-fanout-J0c.spec.ts` (T-04) — now reads `J0C_LOCATION_NAME` (the
    workflow's pinned random name) so the legacy-created name == the name the
    AlpenFlight parity spec asserts; falls back to a fresh local random for
    standalone runs.
  STRUCTURAL-only (neither stack runs on the authoring box): workflow YAML
  parses (3 jobs, `fanout-proof needs: [legacy-server-build, legacy-web-build]`,
  triggers = schedule + workflow_dispatch); every referenced path resolves; the
  3 web files + the legacy spec typecheck clean (zero NEW errors) and all
  Playwright-discover (`--list`); the gallery generator runs against its fixture
  manifest. **First LIVE green is this workflow's own first CI run** (the
  J-0/J-0b pattern, [[feedback-verify-infra-is-run-not-just-authored]]) — no
  live pass is claimed.
- [x] **T-06 — Gallery: legacy + AlpenFlight videos, J-0c-captioned.** Ensure both
  videos land captioned + `J-0c`-tagged; extend `generate-gallery.mjs` if it can't
  caption a non-AlpenFlight (legacy) video. *(seam: gallery generator + fixture)*
  Landed: the AlpenFlight per-club videos already flow through the manifest path
  (`proof-journey: J-0c` via `proofVideo()`) — verified they render under a J-0c
  section. The LEGACY parity video gets a **declared sidecar source**:
  `generate-gallery.mjs` grows an optional `--legacy-video <dir>` (param
  `legacyVideoDir`) that reads a `legacy-video.json` sidecar declaring legacy
  videos keyed to a journey (`{journey, file, acTag, caption}`); `extractLegacyVideos`
  returns the same proof shape flagged `legacy:true`, under the SAME AC5 link-check
  (caption required + `.webm` must exist). The generator renders the legacy video
  FIRST within the J-0c section, labelled `legacy parity` (CSS `.legacy-proof`) so
  a reviewer reads legacy → AlpenFlight side by side. AlpenFlight-video + "pending"
  (J-0/J-0b) paths untouched; missing dir/sidecar is a no-op. The proof workflow
  now stages the recorded legacy `.webm` + writes the sidecar (with the chain's
  random Location name) BEFORE the generator runs (`--legacy-video`). Fixture +
  spec extended: a J-0c AlpenFlight proof in `proof-manifest.json` + a
  `fixtures/legacy-video/` sidecar; new spec case asserts the J-0c section shows
  BOTH videos, captioned, legacy clearly labelled. STRUCTURAL-only on the box for
  the two browser-driven spec cases (musl/glibc chromium relocation + `/home/agent`
  inaccessible — J-0/J-0b "first live green is CI" pattern); verified: generator
  green against the fixture via the exact CI invocation, AC5 (no-browser) case
  passes, all 3 cases Playwright-discover, spec has no NEW tsc error, emitted HTML
  is well-formed with the J-0c legacy + AlpenFlight entries.

**Order:** T-01 → T-02 → T-03 → T-04 → T-05 → T-06.

### Gate-run findings (2026-06-01, first live runs) → new tasks

- [x] **T-07 — Fix migration-ingest IT regression (T-02 Keycloak call).** The per-PR
  `alpenflight build` went RED: T-02 wired a **real Keycloak HTTP call**
  (`provisionMigratedClubAdmins` → `KeycloakDeploymentDirectoryAdapter`) into the
  **shared** ingest path, but the server-IT env has no Keycloak. T-02 only mocked the
  `KeycloakDeploymentDirectory` in `MigrationBundleIngestIT`; the other ingest ITs
  (`LocationRealProducerRoundTripIT` ← J-0c's own gate, `MigrationBundleNegativePathIT`,
  `LocationMigrationRoundTripIT`, `MigrationBundleParityRoundTripIT`) now 500 with
  `ResourceAccessException: …keycloak:8080`. **Fix:** hoist the mock
  `KeycloakDeploymentDirectory` into a **shared `@TestConfiguration`** applied across
  all migration-ingest ITs (they test ingest, not Keycloak — the boundary should be
  consistently mocked). Also decide: should a Keycloak-provision failure hard-fail the
  whole data ingest (current) or be best-effort? Surface if changing it. *(seam: shared
  test-config across the ingest IT suite; blocks the per-PR required gate)*
  Landed: new shared `@TestConfiguration`
  `ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig` — a `@Primary`
  Mockito mock of `KeycloakDeploymentDirectory` with a `lenient` default stub
  (`provisionClubAdminIdentity` → random `sub`) so the real ingest path completes
  without a realm. `@Import`-ed into every `migrations/web` IT that drives ingest:
  `MigrationBundleIngestIT` (its own nested `MockDirectoryConfig` deleted — now
  consumes the shared one; `reset` + re-stub + `verify` unchanged so T-02's
  behavior assertion still holds), `LocationRealProducerRoundTripIT`,
  `LocationMigrationRoundTripIT`, `MigrationBundleParityRoundTripIT`,
  `MigrationBundleNegativePathIT`, plus `MigrationBundleConcurrencyIT` +
  `MigrationBundlePlaintextLeakIT` (both reach the OK/provision path too). The mock
  generalizes the exact pattern T-02 used and `DeploymentProvisioningServiceIT`
  uses — no new mock style. Real Postgres (Testcontainers) run: 22/22 green
  (RealProducer 1, Migration 1, Parity 4, NegativePath 10, Ingest 4, Concurrency 1,
  PlaintextLeak 1; 0 failures/errors/skips).
  **Design question (hard-fail vs best-effort) — surfaced, NOT changed.** T-02
  makes a Keycloak-provision failure roll back the whole data ingest (a 500),
  yet `KeycloakDeploymentDirectory`'s own Javadoc states the directory port "runs
  post-commit, best-effort, and is retried by the hourly reconcile job when it
  fails mid-flight." So the *intended* contract is best-effort + reconcile; the
  T-02 wiring contradicts it by coupling provisioning into the ingest transaction's
  success. **Recommendation: make club-admin provisioning best-effort** — let data
  ingest COMMIT, enqueue/leave provisioning for the existing reconcile path, and
  surface a partial-success status — to match the documented contract and avoid a
  transient Keycloak outage failing an otherwise-good migration. NOT done here: the
  honest test fix is purely the shared mock (the ITs now pass with provisioning
  invoked, mirroring the real wiring), so changing the coupling is out of T-07's
  seam. Filed as a follow-up call for `/do-ship`/operator.
- [x] **T-08 — Fix fanout-workflow MSSQL network ordering.** The full-chain run hung at
  "Wait for MSSQL healthcheck" → 45-min timeout: `alpenflight_shared` is `external: true`
  and MSSQL references it, but the workflow **creates the network (step 19) AFTER starting
  MSSQL (step 5)**, so the compose `up` fails and MSSQL never starts. **Fix:** create the
  `alpenflight_shared` network BEFORE the "Start MSSQL" step (move the `docker network
  create` up). *(seam: one workflow ordering fix)*
  Landed: moved the "Create alpenflight_shared network" step out of the AlpenFlight-half
  section (where it ran ~30 steps too late) to immediately BEFORE "Start MSSQL (fls-e2e
  compose, default profile)" in `.github/workflows/alpenflight-proof-fanout.yml`. Made it
  idempotent — `docker network inspect alpenflight_shared >/dev/null 2>&1 || docker network
  create alpenflight_shared --driver bridge` — so a second create can't fail the job. The
  external network now exists before any compose `up` references it (MSSQL early + the
  AlpenFlight-half services later, which already ran after this step). YAML-only ordering
  fix; nothing else depended on the old position. STRUCTURAL-only (legacy stack can't run
  on the authoring box): validated YAML parses (node `yaml`), the parsed `fanout-proof`
  step list has network-create (index 2) preceding MSSQL-start (index 3) with exactly one
  network-create step, and the create is idempotent. First LIVE green is the workflow's
  next run (manager-triggered).

**Order (rework):** T-07 (code gate) → re-run per-PR CI → T-08 (workflow) → re-run full chain.

### Gate-run round 2 (2026-06-02) → more reworks

T-07 fixed the server ITs (22/22 green) and T-08 fixed MSSQL (legacy stack now comes up — the chain got past the healthcheck into the legacy spec). Two new reds:

- [x] **T-09 — web prettier format (code gate).** With the server ITs green, the
  `alpenflight build` job then failed at the web lint/format step: `keycloak-admin.ts`
  + `fan-out-parity-fixture.ts` (e2e-driver edits) weren't prettier-clean. Fixed inline
  (`prettier --write`, locally `--check`-verified — trivial/mechanical, no CI round burned).
- [ ] **T-10 — legacy login in the T-04 spec.** The full chain now reaches "Run T-04
  legacy create-flow spec" (MSSQL up ✓) but fails fast: `Error: expected myClub.ClubId in
  ngStorage-user after UI login` (`locations-fanout-J0c.spec.ts:180`). The legacy
  `loginViaUi` either doesn't populate the expected club for the seed user, the
  assertion/wait is wrong, or the FLSTest seed user lacks the club. `e2e-driver` — debug
  via CI (legacy stack can't run on this box). *(seam: T-04 spec/login)*

**Order:** T-09 (done) → T-10 → re-run both gates.
