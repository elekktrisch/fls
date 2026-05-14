---
id: S-116
title: Production IdP selection
epic: E-14
status: todo
depends_on: [S-019]
acceptance:
  - Production IdP chosen from: Ory Network (DE/EU), Logto Cloud (EU), Auth0 (EU), self-hosted Keycloak on production VPS.
  - Choice rationalized against: Swiss/EU residency proof, OIDC-standard surface, MFA support, pricing fit.
  - Production realm/tenant configured; production client IDs + secrets stored in production env.
  - The `next/auth/realm-export.json` from S-019 round-trips into production (or is mirrored as a comparable production-realm setup).
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007's open item. Must resolve before cutover.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Vendor comparison.
- [ ] Recommendation: **self-hosted Keycloak in prod** for cost-zero + residency-clean — same image as dev, deployed to the prod VPS. Hosted options stay as fallback if Keycloak ops burden becomes painful.
- [ ] Decision committed in `next/auth/prod-idp.md`.
- [ ] Provision.

## Notes
Self-hosted keeps everything under the operator's control. The "fewer moving parts" preference + small scale + no MAU-tier pricing wins out.
