---
id: S-045
title: K8s-migration trigger criteria (written threshold)
epic: E-05
status: todo
depends_on: []
acceptance:
  - A short doc under `next/ops/k8s-migration-criteria.md` enumerates: scale (e.g. >50 clubs onboarded), reliability (downtime > SLO twice in a quarter), feature (need for horizontal scale or network-level tenant isolation).
  - Each criterion has a measurable threshold and a designated trigger.
  - Doc is reviewed and pinned by the operator.
estimate: S
adr_refs: [0010]
parity_test: none
---

## Context
ADR 0010 commits to K8s mid-term. Without a written threshold, migration becomes either premature or never. Doc removes the ambiguity.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Draft criteria.
- [ ] Review with operator.
- [ ] Commit.

## Notes
Treat this doc as a contract with future-self — don't migrate to K8s "because everyone else does" or "I have free time." Migrate when criteria fire.
