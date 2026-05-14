---
id: S-066
title: OGN ingestion REST endpoint
epic: E-07
status: todo
depends_on: [S-058, S-023, S-029]
acceptance:
  - `POST /api/v1/ogn/flights` accepts the inbound flight contract (schema documented in OpenAPI).
  - Authenticated via OAuth2 client-credentials grant for a dedicated `fls-ogn` Keycloak client.
  - Writes through `UnscopedTenantContext` (S-023) because OGN writes flights for many clubs from one service principal.
  - Endpoint validates: incoming `club_slug` resolves to a real club; incoming aircraft immatriculation exists in that club; basic timestamp sanity.
  - Audit-log entry for each ingested flight (actor = `ogn-sync`).
estimate: M
adr_refs: [0005, 0008]
parity_test: none
---

## Context
C8 + R9: replace OGNAnalyser's direct DB writes with a proper REST API. Owner must coordinate with the OGN maintainer (S-114).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define the inbound DTO matching the legacy direct-write columns.
- [ ] Add the `fls-ogn` client to Keycloak (S-019 follow-up — add it to the realm export).
- [ ] Implement the controller; validate; write via unscoped context.
- [ ] OpenAPI annotations for the contract.
- [ ] Smoke test: simulate OGN-Analyser's call pattern.

## Notes
The schema reshape (E-02) means OGN cannot keep writing the SQL Server schema directly. Coordination with the maintainer is critical — see S-114.
