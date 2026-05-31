---
id: S-141c
title: Provisioning↔ingest reconciliation — FULL_PORT CLUB ingest vs the provisioning t_club create
epic: E-15
status: done
started_at: 2026-05-31
done_at: 2026-05-31
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
parity_test: MigrationBundleParityRoundTripIT (CLUB FULL_PORT); full 28-mapper coverage via S-187a + S-139a
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, security]
github_issue: 185
github_pr: 186
merged: true
merged_at: 2026-05-31
---

## Context

On first successful ingest the provisioning flow (S-138) mints a `t_club`; a bundle that also ships a FULL_PORT `CLUB` stream previously carried a second, colliding club row. This story reconciles the two: the provisioning-minted Club is authoritative, and the `CLUB.ndjson` row rewrites its own legacy id to the provisioned `t_club.id` (via `legacy_id_map_club`) and UPSERTs onto it — no second row, full legacy-column fidelity. The rewrite is **fail-closed**: a CLUB row whose legacy id this bundle's manifest didn't provision aborts the ingest (`BUNDLE_CROSS_TENANT_FK_LEAK`), because a Club id is a tenant root (ADR 0008). Provisioning-owned synthetic columns (`slug`, `public_registration_enabled`, `deployment_id`) are absent from `ClubMapper`, so the bundle structurally cannot touch them.

## Acceptance criteria
See frontmatter.

## Cross-story contracts

- **Consumes:** S-138 provisioning (the minted Club id); S-141b ingest seams (`EntityStreamIngestor`, `ForeignKeyResolver`, `seedClubLegacyIdMap`).
- **Produces / unblocks:** the working CLUB FULL_PORT ingest path that **S-187a** (all-28-mapper coverage) and **S-139a** (real-jar producer→consumer e2e) gate on. The server-vs-parity-harness CLUB-rewrite divergence (`migration-bundle/src/parity/.../ForeignKeyRewriter` skips FULL_PORT→FULL_PORT; the server rewrites CLUB) stays owned by **S-187a** — this story does not touch `migration-bundle/src/parity`.

## Parity scope

Server-side vertical slice in `MigrationBundleParityRoundTripIT` (CLUB FULL_PORT reconcile + config-column fidelity, multi-Club by `club_key`, fail-closed on an unprovisioned id). The full 28-mapper sampled-value diff is **S-187a**'s `ParityOracleHarnessTest`.
