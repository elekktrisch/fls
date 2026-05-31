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
