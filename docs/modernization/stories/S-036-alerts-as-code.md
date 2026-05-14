---
id: S-036
title: Alert rules as code (initial set)
epic: E-04
status: todo
depends_on: [S-035]
acceptance:
  - Grafana unified alerting rules committed as YAML under `next/ops/grafana/alerts/`.
  - Initial alerts: HTTP 5xx rate > 1% over 5 min, p95 latency > 500ms over 10 min on critical endpoints, scheduled-job-failure (consecutive failures > 0), disk-free < 10%, TLS-cert expiry < 30 days.
  - At least one notification channel is configured (email or Telegram).
  - A test fire produces an actual notification.
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
Dashboards show; alerts wake you up. NFR — 99.0% SLO is only achievable with proactive alerting.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Write alert rules in Grafana-Unified-Alerting YAML.
- [ ] Configure notification channel(s) — email at minimum; Telegram preferred for instant phone notification at the operator's preference.
- [ ] Provision rules + channels via Grafana provisioning.
- [ ] Drill: trigger a fake 5xx burst, confirm alert fires + notification arrives.

## Notes
Don't overlap with the audit log — audit is "what was done"; alerts are "wake me up." They write to different sinks for different consumers.
