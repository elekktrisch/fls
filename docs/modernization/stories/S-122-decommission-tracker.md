---
id: S-122
title: Decommission tracker
epic: E-14
status: todo
depends_on: [S-121]
acceptance:
  - A doc `next/ops/post-cutover-decommission.md` lists every legacy component to retire and the date it was retired: `flsserver/`, `flsweb/`, `FLS.Workflow.Activator`, `Alpinely.TownCrier`, `Ionic.Zip`, `FLSAnalyser` (if migrated), `PROFFIX-FLS-Sync` (NOT — stays per scope), the legacy SQL Server instance, the legacy reverse proxy.
  - Retirement happens in a grace period (1–4 weeks post-cutover) — not on cutover day, in case rollback is needed.
  - Each line item has a "retired on" date and an "archived to" location.
estimate: S
adr_refs: []
parity_test: none
---

## Context
Closes the loop. The legacy infrastructure consumes hosting cost — but only after we're confident the new system is stable.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the tracker.
- [ ] Set the retention period.
- [ ] On schedule, retire components.

## Notes
Wait for at least one full DeliveryMailExportJob cycle (monthly) before retiring legacy — that's when the rare jobs run, and parity issues that survived cutover surface.
