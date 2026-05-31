---
id: S-141c
title: Provisioning↔ingest reconciliation — FULL_PORT CLUB ingest vs the provisioning t_club create
epic: E-15
status: todo
depends_on: [S-141, S-138]
integration_base: integration/migration
origin: implementation-followup
origin_story: S-141b
acceptance:
  - A bundle whose manifest declares a Club (provisioning) AND ships a FULL_PORT `CLUB` entity stream ingests without a `t_club` PK/unique collision against the provisioning service's club-create.
  - The legacy `ClubId` resolves to the provisioning-minted club id for all FK rewriting (i.e. `legacy_id_map_club` is populated from the provisioned club, not a second inserted row), so child rows (users, flights, etc.) land under the provisioned Club.
  - The full-fixture parity round-trip (all `KnownMappers`, including `CLUB`) passes end-to-end — unblocking the all-entity coverage in S-187a and the full producer→consumer e2e in S-139a.
  - `MigrationBundleParityRoundTripIT` is extended to exercise the CLUB FULL_PORT path it currently documents as out of scope.
estimate: M
adr_refs: [0008, 0022]
parity_test: MigrationBundleParityRoundTripIT (extend to CLUB FULL_PORT); full coverage via S-187a + S-139a
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, security]
github_issue: 185
github_pr: 186
---

## Context

`MigrationBundleParityRoundTripIT` (S-141b) explicitly scopes OUT the CLUB FULL_PORT path: *"the CLUB FULL_PORT path conflicts with the provisioning service's t_club-create — those are S-141c (provisioning-vs-ingest reconciliation) and S-187a (cross-module test fixture sharing)."* On first successful ingest the deployment-provisioning flow (S-138) mints a `t_club`; a bundle that also carries a FULL_PORT `CLUB` stream then collides on that club. Until reconciled, neither the all-entity parity coverage (S-187a) nor the real-jar full e2e (S-139a) can pass with CLUB included.

Surfaced by the S-139 / S-139a refinements (2026-05-31).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide the reconciliation contract (refine): provisioning-minted Club is authoritative and FULL_PORT CLUB rows reconcile onto it via `legacy_id_map_club`, vs CLUB excluded from FULL_PORT when provisioning owns the Club. Behaviour, not schema-level logic (ADR 0022 directive 2).
- [ ] Implement the chosen reconciliation in the ingest pipeline.
- [ ] Extend `MigrationBundleParityRoundTripIT` to cover CLUB FULL_PORT.

## Notes
- This is the gating dependency for both S-187a (full mapper coverage) and S-139a (full e2e). Order it before completing those.
- Tenant isolation (ADR 0008): the reconciled Club is the tenant boundary for every ingested child row — get the `legacy_id_map_club` mapping right or child rows leak across tenants.
- Assumption: estimated M (one ingest-path branch + one extended IT); revisit if the reconciliation needs a manifest-shape change (would pull in S-139 contract work).

<!-- modernize-refine: start -->

## Design notes

**Reconciliation mechanism — self-id rewrite + UPSERT (pinned).** Option (a), and the only one consistent with AC1/AC3/AC4 + S-141b's "Architecture notes". When draining `CLUB.ndjson`: rewrite the row's own `legacy_guid` (→ `id`) through `legacy_id_map_club` (already seeded by `seedClubLegacyIdMap`: legacyClubId → provisioning-minted id), then `INSERT INTO t_club (…) VALUES (…) ON CONFLICT (id) DO UPDATE SET <mapper cols except id> = EXCLUDED.<col>`. Provisioning runs before ingest in the same txn, so the row always pre-exists → effectively always the UPDATE branch, overlaying the ~26 legacy config columns `Club.create` left at defaults (address, `send_*` operator emails, `run_delivery_*` flags, contact, sync caches, audit). Rejected: (b) skip the CLUB INSERT — loses those columns, fails AC3 full-fixture parity; (c) exclude CLUB from FULL_PORT at the producer — a manifest/policy change that pulls in S-139 contract work and contradicts the M estimate.

**Self-id rewrite is fail-closed** — unlike the generic FULL_PORT FK path (`ForeignKeyResolver` leaves an unmapped FULL_PORT FK untouched and relies on a *downstream* FK constraint to surface the miss). CLUB's own id has no downstream FK to catch it, so an unmapped CLUB row would `ON CONFLICT` against a row provisioning never minted (or insert an unprovisioned, `deployment_id`-less club → opaque NOT-NULL violation). Require: every CLUB row's rewritten id ∈ `provisioned.clubIds()`, else abort the txn reusing `BUNDLE_CROSS_TENANT_FK_LEAK` semantics.

**Where it lives.** Special-case `CLUB` inside `EntityStreamIngestor.ingestEntityNdjson` (the UPSERT clause + the self-id rewrite); every other entity keeps the blind INSERT. `ForeignKeyResolver` is unchanged — it rewrites CLUB's *outbound* FKs (`country_id`, `club_state_id`); the self-id is an INSERT-builder concern. Don't generalise — CLUB is the only entity that reconciles onto a provisioning-minted row.

**Conflict-column precedence.** Bundle-wins on the 31 mapper columns (`EXCLUDED` overwrites); provisioning-wins on the synthetic columns the mapper does **not** carry — `slug`, `public_registration_enabled`, `deployment_id` — because the SET list never names them. `club_key`/`country_id`/`club_state_id` arrive identical (manifest derives from the same legacy club) → harmless no-op overwrites, no `ux_club_key` collision. Pin a test that the destination SET list ∩ `{deployment_id, slug, public_registration_enabled}` = ∅ so a future mapper addition can't silently widen the UPDATE surface.

**Hardening (in scope).** `seedClubLegacyIdMap` currently zips `manifest.clubs()` against `provisioned.clubIds()` by **index** (`EntityStreamIngestor:76`). On provisioning's idempotency-replay path `loadResult` returns clubIds in DB order, not manifest order, so the index pairing mismaps. Today unreachable (a failed ingest flips the upload to terminal `FAILED` → any retry is a fresh upload + key, never an id-replay), but fragile and load-bearing once multi-Club CLUB rows reconcile. Pair by `legacyClubId` identity, not index.

**Cross-story contracts.** Consumes S-138 (`Club.create` + the minted id) and S-141b (`seedClubLegacyIdMap` / `EntityStreamIngestor` / `ForeignKeyResolver`). Produces the working CLUB FULL_PORT ingest path that **S-187a** (all-28-mapper coverage) and **S-139a** (real-jar e2e) gate on. The server-vs-parity-harness CLUB-rewrite divergence (`migration-bundle/src/parity/.../ForeignKeyRewriter` skips FULL_PORT→FULL_PORT; the server rewrites CLUB) stays owned by **S-187a** — this story does **not** touch `migration-bundle/src/parity`.

**ADR 0022 D2.** No deviation — pure ingest-path behaviour. `ux_club_key` UNIQUE + the PK are pre-existing structural invariants; no business rule lands in schema.

## Edge cases & hidden requirements

- **No `CLUB.ndjson` (provisioning-only, the S-141b status quo) must still pass** — the reconcile is *additive*; the extended IT keeps a no-CLUB-stream case green.
- **Multi-Club** — each `CLUB.ndjson` row rewrites to its *own* provisioned id; correct only once the pairing is identity-based (see Design hardening). Assert no cross-assignment of legacy columns between the two clubs.
- **Unmapped CLUB row** (`legacy_guid` ∉ this bundle's manifest) → fail-closed abort (see Design / Security), not a fresh INSERT.
- **Conflict drift** (manifest vs bundle differ on clubname / club_key / country / state) → bundle wins (faithful port); no dedicated drift error. A bundle `club_key` that collides with a *different* provisioned club surfaces as a natural `ux_club_key` violation (sqlstate-only body).
- **Soft-delete** — a `CLUB.ndjson` carrying `deleted_on` lands faithfully (could tombstone the just-provisioned club). Whether a deleted legacy club is *exported* is a producer decision (S-139 / S-185) — out of scope here; ingest stays faithful.
- **`created_on` overwrite** — bundle (legacy provenance) wins over the trial-signup instant; it's `@ParityIgnore`, so parity-neutral. Acceptable.
- **Duplicate legacy ClubId within one `CLUB.ndjson`** — not expected (producer emits one row per club); the second row's DO UPDATE would silently overwrite the first. No guard added; S-187a's coverage gate would surface it if it ever happens.

## Security plan

- **Tenant-boundary integrity (load-bearing, structurally safe).** `legacy_id_map_club` is a `CREATE TEMP TABLE … ON COMMIT DROP` table seeded *in this same txn* from *this* request's `provisioned.clubIds()` — session-local (a concurrent tenant's ingest is a different connection with its own temp table; the global gate defaults to 1 in-flight anyway). So a CLUB row can never rewrite onto another tenant's club. Make the self-id rewrite **fail-closed** (`BUNDLE_CROSS_TENANT_FK_LEAK`) — CLUB's own id has no downstream FK to catch a miss.
- **Smuggled columns.** The UPSERT SET list is built from `ClubMapper.columns()`, which excludes `deployment_id` / `slug` / `public_registration_enabled` → a bundle cannot move its club to another deployment, rename the slug, or flip public registration. Pin a test asserting the SET ∩ `{deployment_id, slug, public_registration_enabled}` = ∅ (mirrors `ClubSpec`'s inbound-`deployment_id` strip on the provisioning path).
- **SQL-injection.** Column names interpolate into the `ON CONFLICT … SET` clause exactly as the existing INSERT; the construction-time `^[A-Za-z0-9_]+$` allow-list covers it; values stay parameterised. No new surface.
- **Audit.** Folds into the existing `MIGRATION_INGEST_*` trail — no per-CLUB before/after snapshot (the "before" is the seed row minted ms earlier in the same txn; actor + deployment already captured).
- **PII.** CLUB `email`/`phone`/`contact` are the caller's *own* club, single-tenant, self-provided → no new redaction. Route UPSERT constraint failures through the existing sqlstate-only response path (raw `SQLException.getMessage()` stays server-log-only).

## Test plan

All weight at the **integration** layer — extend `MigrationBundleParityRoundTripIT` (`@Tag("slow")`, Postgres); no new unit/e2e. The `parity` source set is still off the server testImplementation classpath (S-141b AC1), so hand-craft NDJSON: add a `clubNdjson(legacyClubId, …)` sibling helper emitting the 31 `ClubMapper` columns with `legacy_guid == manifest legacyClubId`.

- **Headline (AC1/AC2/AC4)** — manifest Club + `CLUB.ndjson` (FULL_PORT) + child `USER.ndjson`: after 200, exactly ONE `t_club` under the deployment, `id == clubIds[0]`, no `ux_club_key` dup, a representative subset of the 31 legacy columns landed via UPDATE, child `User.club_id == clubIds[0]`.
- **Regression** — the existing provisioning-only path (no `CLUB.ndjson`) keeps passing unchanged.
- **Fail-closed** — `CLUB.ndjson` `legacy_guid` absent from `legacy_id_map_club` → asserted error code + txn rollback + zero new `t_club`.
- **Multi-Club** — 2 clubs each with a `CLUB.ndjson` row → each reconciles onto its own provisioned row (sentinel column per club); guards the identity-pairing fix.
- **Conflict survival** — provisioning-owned `slug` / `public_registration_enabled` survive the UPDATE untouched.
- **Parity strategy** — this IT is the server-side vertical slice; the all-28-mapper sampled-value diff is S-187a's `ParityOracleHarnessTest` (this story *unblocks* it + S-139a's real-jar e2e but doesn't own them). Don't re-assert all 31 columns cell-by-cell — a sentinel subset suffices.

## Performance plan

(N/A — one UPSERT per declared Club (1..N, tiny); no hot path, no new index. Bulk-ingest throughput is owned by S-141b.)

<!-- modernize-refine: end -->
