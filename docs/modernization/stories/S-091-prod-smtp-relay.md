---
id: S-091
title: Production SMTP relay selection
epic: E-10
status: todo
depends_on: [S-082, S-044]
acceptance:
  - SMTP relay chosen + provisioned: self-hosted Postal on the production VPS, or transactional API (Postmark / Resend / Brevo EU).
  - Backend `application.yml` configured against the chosen relay for production.
  - SPF + DKIM + DMARC records set on the sending domain.
  - A delivery test email reaches a real Gmail inbox without ending in spam.
estimate: M
adr_refs: [0013]
parity_test: none
---

## Context
ADR 0013 deferred this to deployment-time. Needs to happen before cutover.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Compare candidates: self-hosted Postal (cost-free, more ops), Postmark (highest deliverability), Resend (modern API, EU region), Brevo (EU HQ, free tier).
- [ ] Decide; provision.
- [ ] DNS records.
- [ ] Deliverability smoke test.

## Notes
For a glider club with low-volume transactional email (daily reports + a handful of notifications), the **free tier of Brevo or Resend** likely covers the workload with zero monthly cost. Self-hosted Postal is more work for marginal savings.
