---
id: S-117
title: DNS / reverse-proxy cutover plan
epic: E-14
status: todo
depends_on: [S-041, S-044]
acceptance:
  - The cutover plan for DNS / proxy switching is documented in `next/ops/runbooks/cutover.md`.
  - TTL on the production domain's A/AAAA record is lowered to 60s ≥ 24 hours before cutover.
  - The legacy production environment is *not* decommissioned — it stays running with traffic stopped, available for the rollback path.
  - DNS swap is automatable (script with `dig` verification).
estimate: S
adr_refs: [0010]
parity_test: none
---

## Context
The actual mechanical cutover. Lowering TTL pre-cutover is the standard trick to keep the cutover window narrow.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Identify the registrar / DNS provider.
- [ ] Lower TTL 24h before.
- [ ] Document the DNS-flip command sequence in the runbook.
- [ ] Verify the propagation pattern is understood.

## Notes
Vision §5 explicitly defines rollback as "old system never decommissioned, only traffic stopped." This story enforces that.
