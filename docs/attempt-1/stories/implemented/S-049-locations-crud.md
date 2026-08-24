---
id: S-049
title: Locations CRUD (reference data)
epic: E-06
status: done
started_at: 2026-05-20
done_at: 2026-05-20
depends_on: [S-047, S-022, S-026]
github_issue: 88
github_pr: 89
acceptance:
  - `Location` + `LocationType` + `InOutboundPoint` ported as shared reference data — no `@TenantId`. Mutation gated by `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`.
  - `InOutboundPoint` lives as a child of Location's aggregate; managed via Location's edit screen only (no top-level CRUD endpoint).
  - ICAO code validated server-side against `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$` (legacy was lax — flagged in `## Assumptions made`).
  - List/edit screens use the kit components from S-008.
  - `e2e/tests/masterdata/locations-crud.spec.ts` parity (port semantics, not legacy URL shape).
  - LocationType admin CRUD UI is **deferred** to a follow-up; S-049 ships LocationType as a Flyway-seeded read-only dropdown.
  - **Per-tenant visibility deferred to S-049b** — operator confirmed the data shape (shared `Location` + per-tenant visibility join), but the visibility join + endpoint + UI is filed as a follow-up to keep this PR reviewable.
estimate: M
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023, 0026]
parity_test: e2e/tests/masterdata/locations-crud.spec.ts
refined: true
refined_at: 2026-05-20
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context

Locations are shared reference data (sacred-cow airports) — classified `kind: reference` in `alpenflight/database/tenant-rules.yaml`. The original story header treated them as per-club masterdata; that contradicted the shipped schema and was resolved in favor of the schema (no `@TenantId`; SYSTEM_ADMIN-only mutation). Cross-tenant blast radius — a rename shifts every flight log in every club — is the load-bearing reason writes are SYSTEM_ADMIN-only.

## Cross-story contracts

- Produces `/api/v1/locations` + `/api/v1/location-types` for S-051+ / S-062a-c (Person home base, Flight `started/landed_location_id`).
- Per-tenant visibility split out to **S-049b** — Location + LocationType + InOutboundPoint stay shared here; the per-tenant visibility join + endpoint + UI lands in S-049b.

## Assumptions made

- **ICAO tightened beyond legacy.** Legacy `flsserver` accepted any string for ICAO; the new server boundary enforces `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$` (4 letters or 2 letters + 2 digits). Existing legacy rows that don't match the regex will require a one-time data cleanup on cutover (tracked in the import slice S-028).
- **Audit emission deferred.** Reference-data mutations are high-value forensic events but no audit emitter exists yet (S-027 is the unified-audit story). `principalUserId()` records the OIDC `sub` on soft-delete as a partial trail; S-027 retrofits the full emitter.

## Parity exclusions

- **Legacy `LocationType` admin CRUD** — deferred. S-049 ships LocationType as a Flyway-seeded read-only dropdown only.
- **Legacy spatial-coordinate validation** — never enforced server-side in legacy; coordinates remain opaque `VARCHAR(10)` end-to-end. No spatial parsing.
- **Legacy URL shape (`/api/v1/locations/page/0/100`, `X-HTTP-Method-Override`, `{Items: [...]}` envelope)** — intentionally not preserved (ADR 0022); the SPA port asserts observable behavior only.
