---
id: S-115
title: Proffix integration verification
epic: E-14
status: todo
depends_on: []
acceptance:
  - Where the live Proffix code lives is confirmed (in-repo `FLS.Server.ProffixInvoiceService` stub vs. external `PROFFIX-FLS-Sync` repo) — closes vision §8 open item.
  - The live consumer's call pattern is documented: which endpoints, what payloads, how often, what auth.
  - The new server's `/api/v1/deliveries/*` matches the consumer's expectations (verified via S-080).
  - Contact with the Proffix maintainer (arminstutz on GitHub) is established; they're informed of the cutover date.
estimate: M
adr_refs: []
parity_test: none
---

## Context
Vision §8 open item. Proffix is sacred-cow and outside our repo — we have to know what to serve.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Read `PROFFIX-FLS-Sync` repo (external).
- [ ] Document its call pattern.
- [ ] Compare against new-server endpoints.
- [ ] Contact maintainer.

## Notes
Probably no code changes on the Proffix side (sacred cow) — we adapt. But the maintainer should know cutover is coming so they can monitor for any oddities.
