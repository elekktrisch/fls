---
id: S-044
title: VPS provider selection + provisioning
epic: E-05
status: todo
depends_on: []
acceptance:
  - Provider is chosen and documented (Hetzner CH/DE, Exoscale CH, Infomaniak CH, Init7 CH, OVHcloud FR/DE).
  - Production VPS is provisioned with: 4 vCPU, 8 GB RAM, 80 GB SSD (sized for backend + Postgres + Keycloak + observability stack ≈ 5 GB headroom).
  - SSH key auth only (no password), fail2ban configured, automatic security updates enabled.
  - Snapshot/backup support enabled at the provider level (complementary to S-042).
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
ADR 0010 deferred to a phase-4 story.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Compare price + support quality + snapshot features (Hetzner CH/DE recommended for cost; Exoscale CH for closer support).
- [ ] Provision the VPS.
- [ ] Apply baseline hardening (SSH key, fail2ban, unattended-upgrades, UFW with only 22/80/443 open).
- [ ] Document the host setup in `next/ops/runbooks/host-setup.md`.

## Notes
Decision can happen close to cutover — earlier provisioning means paying for an unused VPS. But the provider choice is a story so it doesn't fall between the cracks.
