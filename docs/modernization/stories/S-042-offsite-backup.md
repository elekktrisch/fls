---
id: S-042
title: Off-site pg_dump backup to Swiss object storage
epic: E-05
status: todo
depends_on: [S-039]
acceptance:
  - A scheduled job (cron on the VPS, or a one-shot container in compose) runs `pg_dump` nightly.
  - Output is uploaded to a Swiss-region object-storage bucket (Exoscale SOS, Infomaniak Swiss Backup, or equivalent).
  - Retention: 30 daily + 12 monthly + 5 yearly.
  - Failure to upload alerts via the channel from S-036.
  - Encryption-at-rest enabled on the bucket; transit is TLS by default.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
99.0% SLO budgets ~7 hr/month downtime — a multi-day disaster (host wipe) would blow it. Off-site backups are how you recover.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Pick the bucket provider (Swiss region required).
- [ ] Configure credentials (separate creds with bucket-only scope).
- [ ] Write the dump+upload script (or use `pgbackrest` if appetite for more infra).
- [ ] Schedule via OS cron on the VPS (not Spring `@Scheduled` — should outlive a backend crash).
- [ ] Retention enforcement.

## Notes
The DB itself is residency-compliant (Postgres on the VPS); backups must be too (C4). Exoscale SOS (CH/AT/DE) and Infomaniak Swiss Backup are the natural picks.
