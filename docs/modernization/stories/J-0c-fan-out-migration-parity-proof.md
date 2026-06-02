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
- [x] **T-10 — legacy login in the T-04 spec.** The full chain now reaches "Run T-04
  legacy create-flow spec" (MSSQL up ✓) but fails fast: `Error: expected myClub.ClubId in
  ngStorage-user after UI login` (`locations-fanout-J0c.spec.ts:180`). The legacy
  `loginViaUi` either doesn't populate the expected club for the seed user, the
  assertion/wait is wrong, or the FLSTest seed user lacks the club. `e2e-driver` — debug
  via CI (legacy stack can't run on this box). *(seam: T-04 spec/login)*
  Root cause: a login-chain TIMING race, NOT the seed (both `testclubadmin`/`@testClubId`
  and `othertestadmin`/`@otherClubId` carry a `Users.ClubId` in `_test-fixture.sql`, so
  `/api/v1/clubs/my` resolves for each). The SPA's `AuthService.login` (AuthService.js:67-93)
  is a 4-call promise chain: POST /Token → `storage.loginResult`; GET /users/my →
  `storage.user`; GET /userroles → `storage.userRoles`; then `Clubs.getMyClub()` (GET
  /clubs/my) → `storage.user.myClub = club` — written LAST. But `waitForLoggedInState`
  only polls `ngStorage-loginResult` for the `access_token` (the FIRST step). So the
  `myClubId()` read fired immediately after the token landed, ~3 async round-trips before
  `myClub` was written — racing to empty (`ngStorage-user` absent or present-without-myClub),
  hence the fast ~2.4s fail. The passing locations-crud/clubs-crud specs never hit this:
  they use the injected-sessionStorage `loggedInPage` fixture, which seeds a fully-formed
  `ngStorage-user` (with `myClub` set by `fetchAuthData`'s own /clubs/my call) before the
  first read. Fix: `myClubId()` now `page.waitForFunction`-polls `sessionStorage` until
  `ngStorage-user.myClub.ClubId` lands (15s, same philosophy as `waitForLoggedInState`)
  before reading — the shape is unchanged (`myClub.ClubId`), it just had to be awaited.
  Validated STRUCTURAL-only (legacy stack can't run on this box): `npx tsc --noEmit` clean
  (no new errors for the file), `npx playwright test --list` discovers the single test.
  First LIVE green is the next manager-triggered `alpenflight-proof-fanout.yml` run.

**Order:** T-09 (done) → T-10 → re-run both gates.

### Gate-run round 3 (2026-06-02) → T-11

T-10's login-race fix WORKED — the spec now gets past login (10.5s vs the old
2.4s fast-fail) into the Location-create step. New red, two failure modes across
the attempt + retry:

- [x] **T-11 — duplicate-name on retry + context-closed readback.** The reported
  failure was `waitForURL '**/#/masterdata/locations'` 15s timeout after the
  create submit — looking like a selectize/validation miss. **The artifact
  (run 26790752624) disproves that:** the retry page snapshot shows ALL required
  fields correctly set (Name `J0C-1081F9`, ICAO `J108`, **Typ `Wegpunkt`, Land
  `Schweiz`** — the `$scope` selectize poke DID take), and a red banner "Failed
  to insert location / {{Error_GeneralDatabaseException}}". The legacy server log
  is conclusive: insert `J0C-1081F9` at 00:34:04 **succeeded**, re-insert at
  00:34:15 hit `Violation of UNIQUE KEY constraint 'UNIQUE_Locations_LocationName'`.
  Root cause chain: `J0C_LOCATION_NAME` pins ONE name for the whole CI run and
  that pin survives Playwright retries; **attempt 1 created the Location fine and
  ran the entire flow through both club saves, then died at the post-`ctxB.close()`
  readback** (`pageB.request.post(/Token)` → "Target page, context or browser has
  been closed" — the API readback raced the browser-context teardown); Playwright
  retried the WHOLE test against the SAME pinned name → duplicate INSERT → DB
  exception → no navigation → the 15s `waitForURL` timeout. So the create looked
  like a validation problem but was a duplicate-name problem *caused by* the
  context-closed bug forcing a retry. NOT a selectize/validation issue, NOT a
  seed gap, NOT a legacy bug. Fix (both seams): (1) added `ensureLocationDeleted`
  (mirrors locations-crud.spec.ts — list-by-name → soft-DELETE via
  `X-HTTP-Method-Override`) called BEFORE the UI create, so the create is
  idempotent and a retry can't collide on `UNIQUE_Locations_LocationName`; (2)
  moved every API readback onto a standalone `playwright.request` context created
  up front and disposed in `finally`, and do ALL readbacks (club B homebase +
  re-token A + club A homebase) BEFORE `ctxB.close()` — the readback no longer
  races any browser-context teardown. Validated STRUCTURAL-only (legacy stack
  can't run on this box): `npx tsc --noEmit` clean for the file (only the
  pre-existing repo-wide `moduleResolution` deprecation warning, exit 0), `npx
  playwright test --list` discovers the single test. First LIVE green is the next
  manager-triggered `alpenflight-proof-fanout.yml` run.

**Order:** T-11 → re-run the full chain.

### Gate-run round 4 (2026-06-02) → T-12

**Code gate GREEN** ✅ (`alpenflight build` + `required` pass — server ITs + full-glob
prettier). Chain cleared the ENTIRE legacy half (create + video, T-11 ✓) + AlpenFlight
stack bring-up + handshake mint, then failed at the export:

- [x] **T-12 — `alpenflight-export` mappers vs real FLSTest schema.** `alpenflight-export`
  connected to the real legacy MSSQL and began `Streaming 7 registered entities...` but
  failed on the first: `ERROR: Failed streaming entity COUNTRY: null` (exit 6) — a swallowed
  exception (likely NPE) in the COUNTRY producer reading a real `Countries` row. This is the
  export producer meeting real legacy data for the first time. **Fix COUNTRY + proactively
  audit the other 6 registered mappers' SELECTs/`writeNdjson` against the real FLSTest schema
  in one pass** (avoid an entity-by-entity round grind), and improve the export's error
  surfacing so `: null` becomes the real cause. *(seam: migration-tool/bundle producer vs
  FLSTest; general-purpose)*
  Landed (2 seams, batched per the task's "one fix-class across ≤7 entities" mandate):
  **(1) Error surfacing — the primary fix, done first.** `BundleWriter.streamOne` caught only
  `SQLException | IOException` and logged `e.getMessage()`, which is `null` for an NPE — hence
  the opaque `: null`. Now it ALSO catches `RuntimeException` (so a mapper NULL-deref /
  `UUID.fromString` on a NULL legacy GUID surfaces with per-entity context instead of crashing
  bare), reports the failing ROW NUMBER, and renders the cause via a new package-private
  `BundleWriter.describe(Throwable)` that walks the cause chain printing `class: message` at
  each level (null-message throwables still name their type). Under `--verbose` (the workflow
  runs `--verbose`) it also dumps the full stack trace to stderr. So the next chain run names
  COUNTRY's exact class + stack instead of `: null`.
  **(2) COUNTRY root cause — NOT in the mapper; it is below it (JDBC/driver/cursor layer).**
  Audited exhaustively against the real FLSTest schema (the EF `DbEntities/Country.cs` is the
  column-name/nullability source of truth + the seed `3 Insert Static Data.sql`): COUNTRY's
  `writeNdjson` does only two `getString` reads (`CountryId`, `CountryCodeIso2`) — neither can
  NPE, and `writeStringField(name, null)` is null-safe in Jackson. All 196 real `Countries`
  rows carry a non-NULL `CountryCodeIso2`; no other seed/fixture/alter file inserts or mutates
  a Country (verified), and the `ParityOracleHarnessTest` round-trips COUNTRY against the SAME
  seed in MSSQL with zero deltas. The failure is ~110 ms (before any row materialises), so it
  is at `executeQuery`/cursor-open under the export's connection hardening — which the parity
  harness does NOT replicate: `ApplicationIntent=ReadOnly` + `responseBuffering=adaptive` +
  `TYPE_FORWARD_ONLY`/`CONCUR_READ_ONLY` + `setFetchSize(1000)` + `closeOnCompletion()` (vs the
  parity harness's plain `prepareStatement(sql)`). This is invisible to static analysis and to
  the authoring box (no live MSSQL). Surfacing (1) is the correct, honest unblock for COUNTRY's
  exact class; the connection-hardening strategy is a SEPARATE seam not speculatively touched
  here (it carries the read-only guarantees). If the next chain run confirms a driver/cursor
  fault, that is a one-line cursor-strategy follow-up.
  **(3) NPE-class audit across all 7 registered mappers — the real, fixable NPE-class bug.**
  `Coercions.writeRequiredTimestamp(target, col, value)` did `value.toInstant()` with NO null
  guard → an NPE with `getMessage() == null` (exactly the COUNTRY symptom shape) the FIRST time
  any registered entity hits a NULL `CreatedOn` in real data. Called by ClubMapper / UserMapper
  / LocationMapper / InOutboundPointMapper (`CreatedOn`). The new-stack destination `created_on`
  is `NOT NULL` (V2/V3), so a NULL cannot silently round-trip — the helper now throws a
  diagnostic `IllegalStateException` NAMING the column + the NOT-NULL contract instead of a bare
  NPE, so error-surfacing (1) reports a clear cause. (COUNTRY/LANGUAGE/CLUB_STATE carry no
  timestamp, so this is not COUNTRY's blocker, but it is the same null-message-NPE class the
  audit was asked to sweep — fixed across all callers via the shared helper.) Other reads were
  already null-safe: every nullable string/int/timestamp goes through `Coercions.writeOptional*`
  / typed `getObject(.., Integer.class)`; `getString` on a NULL emits a null JSON field
  (consumer-side `UUID.fromString` validates at ingest, a different layer).
  **Validated** (legacy MSSQL can't run on this box): `./gradlew build` green in BOTH
  `migration-bundle` (incl. the gated-off `parityTest` excluded) and `migration-tool`. New
  regression tests: `CoercionsTest` (NULL required-timestamp → diagnostic `IllegalStateException`
  naming the column, + the present-value ISO-instant path) and `BundleWriterFanOutIdMapTest`
  (`describe` names the class for a null-message NPE + renders the full cause chain). First LIVE
  green is the next manager-triggered `alpenflight-proof-fanout.yml` run — which will now print
  COUNTRY's real exception class + stack trace if it still fails at the driver layer.

**Order:** T-12 (done) → re-run the full chain (will now surface COUNTRY's real cause).

### Gate-run round 5 (2026-06-02) → T-13

T-12 diagnostics worked: COUNTRY now reports the REAL error.

- [x] **T-13 — export `ClosedChannelException` mid-stream (row 115).** `alpenflight-export`
  streams COUNTRY fine for 114 rows then dies: `java.nio.channels.ClosedChannelException`
  at `BundleWriter.streamOne(BundleWriter.java:112)` — an I/O/cursor fault, NOT a mapper bug.
  Suspect the read-side JDBC connection hardening in `LegacyJdbcReader` (`ApplicationIntent`
  =ReadOnly + `responseBuffering=adaptive` + `TYPE_FORWARD_ONLY` + `setFetchSize(1000)` +
  `closeOnCompletion()`) closing the socket channel mid-iteration, or a write-side channel
  close. `e2e-driver`/general — diagnose `BundleWriter.java:112` + the reader config, fix so
  COUNTRY (196 rows) + all entities stream fully. *(seam: migration-tool JDBC streaming)*
  Root cause: **WRITE-side, NOT the JDBC reader.** The `:112` line was the per-row
  `try (JsonGenerator gen = JSON_FACTORY.createGenerator(digestOut)) { … }` close. `streamOne`
  creates a **new `JsonGenerator` per row** wrapping ONE shared per-entity
  `DigestOutputStream` → `BufferedOutputStream` → NIO file channel, and `JSON_FACTORY` was a
  default `new JsonFactory()` with `AUTO_CLOSE_TARGET` ON. So the **first** row's `gen.close()`
  closed `digestOut` → `fileOut` → the file channel. The closed-channel write then surfaced
  not on row 2 but at the next 8 KB `BufferedOutputStream` flush — a buffer-flush boundary
  (~row 115 for COUNTRY's wide rows; ~row 693 in the test's narrow rows), which is exactly why
  it looked like a consistent-boundary cursor/buffering fault. The `ClosedChannelException`
  comes from `java.nio.channels` (the NIO file output channel) — a SQL Server socket fault
  would surface as `SQLException` — so the `LegacyJdbcReader` hardening (`ApplicationIntent`
  =ReadOnly, `closeOnCompletion`, fetchSize 1000, adaptive buffering) was a **red herring**;
  it is left untouched, the read-only export contract preserved. Fix (smallest correct):
  build `JSON_FACTORY` with `StreamWriteFeature.AUTO_CLOSE_TARGET` **disabled** so each per-row
  `gen.close()` FLUSHES but does not close the shared stream (the stream is owned + closed once
  by `streamOne`'s try-with-resources). Also extracted the drain loop into a package-private
  `BundleWriter.drainCursor(entity, mapper, rs)` so the multi-fetch-window / buffer-boundary
  path is unit-testable without live MSSQL (the `SQLException` from `rs.next()` now surfaces
  inside `drainCursor`; only cursor open/close `SQLException` reaches `streamOne`). Validated
  (no live MSSQL on box): new `BundleWriterStreamTest` drives a `Proxy`-backed forward-only
  fake cursor of **5000 rows** (>> the 8 KB buffer and the 1000-row fetch window) through
  `drainCursor` — RED before the fix with the EXACT production stack (`UTF8JsonGenerator.close`
  → `_flushBuffer` → `BufferedOutputStream.flushBuffer` → `FileChannelImpl.ensureOpen` →
  `ClosedChannelException`, failing at row 693), GREEN after (all 5000 rows + 5000 NDJSON
  lines, last row intact). Full `migration-tool` `./gradlew build` green (shadowJar + all
  tests incl. the existing `BundleWriterFanOutIdMapTest`). First LIVE green is the next
  manager-triggered `alpenflight-proof-fanout.yml` run.

**Order:** T-13 → re-run the full chain.

### Gate-run round 6 (2026-06-02) → T-14

T-13 (AUTO_CLOSE_TARGET) worked: COUNTRY/CLUB(4)/USER(4) now stream. LOCATION fails
at row 1 — a real **parity finding** (exactly J-0c's purpose):

- [x] **T-14 — LocationType FK is a legacy GUID, not int.** `LocationMapper.writeNdjson:143`
  reads `getInt("LocationTypeId")` but legacy `Locations.LocationTypeId` is a `uniqueidentifier`
  (GUID FK to `LocationTypes`) → `SQLServerException: conversion from uniqueidentifier to
  INTEGER is unsupported`. J-0b's data-layer IT missed it (fed synthetic int-encoded NDJSON
  matching the wrong assumption). **Root cause + fix (verified):** the new-stack
  `t_location_type.legacy_int_id` (1,2,3,4,5,99) == legacy `LocationTypes.LocationTypeCupId`
  (1,2,3,4,5,...), so J-0b's int-resolution is CORRECT — only the producer mis-projected the
  GUID. Fix `MapperLegacyBindings.LOCATION` to JOIN `LocationTypes` ON `LocationTypeId` and
  project `LocationTypeCupId` (int) so `writeNdjson` keeps `getInt`+`legacyIntIdToUuidString`.
  The unit-type FKs (`ElevationUnitTypeId`, `RunwayLengthUnitType`) ARE `int?` in legacy — fine.
  **Also audit the other registered mappers (esp. INOUTBOUND_POINT, not yet reached) for the
  same GUID-read-as-int class.** *(seam: LOCATION binding + sibling audit; migration-bundle)*

**Order:** T-14 → re-run the full chain.

### Gate-run round 7 (2026-06-02) → T-15

Export half COMPLETE (all 7 entities stream). Chain reached the FINAL step (AlpenFlight
parity spec); real-bundle migrate POST 500s:

- [x] **T-15 — COUNTRY reference-data resolution on real bundle.** Real-bundle ingest fails:
  `ConstraintViolationException: t_club violates fk_club_country_id — country_id=(77cc3be6-…)
  not present in t_country`. CLUB references a legacy Country GUID that does not resolve into
  `t_country`. Investigate COUNTRY's `EntityPolicy` in the real export manifest (FULL_PORT vs
  SYSTEM_GLOBAL_RESOLVE), the bundle entity/tar ordering (COUNTRY before CLUB?), and how
  `CLUB.country_id` is resolved — the new-stack `t_country` seed is a fixed set; real legacy has
  ~196 countries with their own GUIDs. Fix so CLUB.country_id resolves. **If this is a genuine
  country-reconciliation DESIGN question (map legacy country GUID→seed by ISO code, etc.) rather
  than a wiring bug, ESCALATE** with the precise options. *(seam: migrate-ingest COUNTRY resolution)*
  Secondary: a failed ingest seals the upload FAILED so the spec retry 409s — the spec needs a
  fresh uploadId/handshake per attempt (test-robustness, fold in or note).

**Order:** T-15 → re-run the full chain.

**T-15 DECISION (operator, 2026-06-02): Option 2 — producer recomputes seed UUIDs (ISO-keyed map).**
The export resolves CLUB.countryId (legacy GUID→seed PK by ISO2) + clubStateId (synthetic int→seed
by code) by replicating the deterministic seed-UUID derivation, so the manifest carries resolved
seed PKs (provisioning inserts valid FKs) + the bundle emits COUNTRY/CLUB_STATE id-maps for the
NDJSON FK path. **Brittleness mitigation (required, since the producer now couples to the seed):**
a guard test asserts the recomputed iso2/code→UUID map equals the actual Flyway t_country/
t_club_state seed — a seed reorder fails CI loudly, never silently breaks migration. Operator chose
this over server-side provisioning resolution (option 1).
  Landed (producer-only, Option 2): new shared `SeedReferenceUuids` (migration-bundle)
  replicates the `GenerateCanonicalUuids` derivation for `t_country` (by ISO2) +
  `t_club_state` (by V2 code). `ManifestBuilder.readClubDeclarations` resolves legacy
  Country GUID → ISO2 → seed PK and legacy ClubState INT → code → seed PK (fail-closed),
  so the manifest's `ClubDeclaration` carries valid FKs → `provisionDeployment`'s `t_club`
  INSERT no longer violates `fk_club_country_id`/`fk_club_club_state_id`. `BundleWriter`
  emits `legacy_id_map/COUNTRY.pgcopy` + `CLUB_STATE.pgcopy` (legacy ref → seed PK, derived
  from the SYSTEM_GLOBAL NDJSON it reads) so the server `ForeignKeyResolver` resolves the
  CLUB NDJSON's `country_id`/`club_state_id`; and it DROPS the pre-seeded SYSTEM_GLOBAL
  NDJSON (COUNTRY/CLUB_STATE/LANGUAGE) from the tar — re-inserting `t_country` would
  NOT-NULL-violate `iso3_code` (the next bug the resolved provisioning exposed). `ClubStateMapper`
  exposes its 1/2/3→code map so producer + mapper can't drift. **Guard test**
  `SeedReferenceUuidsSeedParityTest` parses the V2 `t_country`/`t_club_state` INSERTs off the
  classpath and asserts `SeedReferenceUuids` reproduces every seeded row EXACTLY (count + each
  natural-key→UUID) — verified RED on an offset bump, GREEN on the real seed; a seed reorder
  now fails CI loudly. **Regression-guarded without live MSSQL:**
  `LocationRealProducerRoundTripIT` gains a method driving a CLUB with an UNRESOLVED legacy
  Country GUID + synthetic club-state through the real `BundleWriter`→server ingest, asserting
  `t_club` resolves to `SEED_COUNTRY_CH`/`SEED_CLUB_STATE_ACTIVE` (RED 500 pre-fix, GREEN after).
  Secondary: `fan-out-parity-fixture.ts`'s `seedFanOutParity` takes `testInfo.retry` so the synth
  path mints a fresh handshake/uploadId per attempt (a retry re-handshakes instead of 409-ing the
  sealed-FAILED upload). **Validated** (no live MSSQL): `migration-bundle` + `migration-tool`
  `./gradlew build` green; server ITs green (RealProducer 2/2 incl. new method, Parity/Migration/
  NegativePath/Ingest suite green); guard test green; web specs tsc-clean + Playwright-discover.
  First LIVE green is the next manager-triggered `alpenflight-proof-fanout.yml` run.

### Gate-run round 8 (2026-06-02) → T-16

T-15 worked (export streams all 7 entities incl. 196 countries; provisioning resolves). New
real-data gap from T-15's fail-closed club-state map:

- [x] **T-16 — legacy ClubState `System`(0) has no new-stack destination.** A real legacy club
  has `ClubStateId=0` (`ClubState.System`, per `FLS.Data.WebApi/Club/ClubState.cs`: System=0,
  Active=1, Inactive=3); T-15's `ClubStateMapper.v2CodeForLegacyId` maps only 1/2/3. New seed
  `t_club_state` = ACTIVE/SUSPENDED/CLOSED. Complete the full legacy-enum→new-state mapping
  (incl. 0 and 2) with a documented parity decision, extend the guard/mapping test, and audit
  the other registered lookups for the same unmapped-legacy-value class. Escalate only if
  `System(0)`’s target is genuinely ambiguous. *(seam: ClubState mapping + lookup audit)*
  Landed (mapping completed, NOT escalated — System(0) was unambiguous). **Full
  legacy ClubState enum (`ClubState.cs`: System=0, Active=1, Passive=2, Inactive=3) → V2 code,
  value-bound in `ClubStateMapper.LEGACY_ID_TO_V2_CODE`:**
  - `Active(1) → ACTIVE` (operating tenant).
  - `Passive(2) → CLOSED` — legacy seed comment for id 2 ("Passiv club"): "Club without tenant
    activities and no users (just information about the club)" → permanently non-operational ⇒ CLOSED.
  - `Inactive(3) → SUSPENDED` — legacy seed comment for id 3 ("Inactive club"): "Club tenant which
    was active before" → reactivatable dormant ⇒ SUSPENDED.
  - **`System(0) → ACTIVE`** — the FLSTest seed's single `ClubStateId=0` club is `System-Verein`
    / ClubKey `SystemClub` (`PRINT 'INSERT SystemClub'`), the FLS internal system tenant owning the
    default system user (`s`) + workflow user. Seed comment "System used club (will not be shown in
    club entities)" is a UI presentation rule, NOT lifecycle-dead. V2 has no SYSTEM lifecycle; a
    migrated system club must stay a usable tenant (its owned users depend on it) — CLOSED/SUSPENDED
    would break it. ACTIVE is the only defensible target, so not genuinely ambiguous → no escalation.
  Two producer paths fixed (both consumed the partial 1/2/3 map): (1) `ManifestBuilder.resolveClubStateSeedPk`
  (the failing chain step) now resolves id 0 → ACTIVE → seed PK; (2) `MapperLegacyBindings.CLUB_STATE`
  SELECT dropped its `WHERE ClubStateId <> 0` filter so the System row enters the catalogue stream —
  otherwise `legacy_id_map_club_state` would lack `legacyIntIdToUuidString(0)` and a System club's CLUB
  NDJSON `club_state_id` FK would fail to resolve at server ingest. `v2CodeForLegacyId` now returns null
  ONLY for a value outside the known enum (a data-corruption signal the callers fail-closed on, with a
  clearer error). **Guard/mapping test:** `ClubStateMapperTest` flipped — was asserting id 0 THROWS, now
  asserts every enum value 0/1/2/3 emits its V2 code + `v2CodeForLegacyId` covers all 4 (and returns null
  for 99); contract `legacyRow` widened to include System/0. **Full-chain regression (no live MSSQL):**
  new `LocationRealProducerRoundTripIT.real_producer_migrates_legacy_system_club_state_zero_to_active_through_chain`
  drives a `ClubStateId=0` System club end to end through the real `BundleWriter` → server ingest,
  asserting `t_club.club_state_id` resolves to the ACTIVE seed PK (would 500 pre-fix). **Sibling
  unmapped-legacy-value audit (the other registered lookups):** ClubState is the ONLY registered mapper
  doing a producer-side legacy-int → semantic-*code* translation via a hand-curated partial `Map.of` — the
  exact class that silently produced null. The siblings differ structurally and were NOT vulnerable to the
  same gap: LANGUAGE (`USER.language_id`), LOCATION_TYPE (`LocationTypeCupId`, the T-14 JOIN), and the unit
  types (`elevation_unit_type_id`/`runway_length_unit_type_id`) all encode the legacy int *structurally*
  via `Coercions.legacyIntIdToUuidString` (no value map, no producer-side null) and fail **closed** at the
  SERVER ingest layer with named errors (`BUNDLE_LANGUAGE_NOT_SEEDED`, `legacy_int_id`-resolution miss) if a
  value isn't seeded — they never swallow an unmapped value. COUNTRY resolves by ISO2 and already fails-closed
  (T-15). No sibling gap to fix. **Validated:** no live MSSQL; `migration-bundle` + `migration-tool`
  `./gradlew build` green; server `LocationRealProducerRoundTripIT` 3/3 (incl. new System case) +
  `SeedReferenceUuidsSeedParityTest` 2/2 + `ClubStateMapperTest` 13/13, all on real Testcontainers Postgres.
  First LIVE green is the next manager-triggered `alpenflight-proof-fanout.yml` run.

**Order:** T-16 → re-run the full chain.

### Gate-run round 9 (2026-06-02) → T-17

T-16 worked: provisioning + ClubState resolve. Migrate now reaches T-02's Keycloak provisioning,
which fails:

- [ ] **T-17 — backend Keycloak admin-base unreachable in the fanout workflow.** Real-bundle
  ingest 500s: `ResourceAccessException: I/O error on POST http://keycloak:8080/realms/alpenflight/
  .../token`. T-02's `provisionMigratedClubAdmins` (KeycloakAdminClient/KeycloakDeploymentDirectory
  Adapter) uses the backend's Keycloak **admin-base** URL, which defaults to the compose-internal
  `keycloak:8080` — unreachable from the host-run bootJar (Keycloak is host-mapped to `localhost:8090`).
  Fix: in `alpenflight-proof-fanout.yml`, set the backend's Keycloak admin-base (+ any client-creds)
  env to the host-reachable address, mirroring how `alpenflight-e2e-real-idp.yml` wires backend→
  Keycloak (issuer + admin). Latent until T-02 first exercised the admin client. *(seam: workflow
  backend Keycloak env)* Once green, the parity UI assertions finally run.

**Order:** T-17 → re-run the full chain.
