---
id: S-150
title: Proffix integration — verify live consumer call pattern
epic: E-09
status: todo
depends_on: []
acceptance:
  - Where the live Proffix integration code lives is confirmed (in-repo `FLS.Server.ProffixInvoiceService` stub vs. external [`PROFFIX-FLS-Sync`](https://github.com/arminstutz/PROFFIX-FLS-Sync) repo) — closes vision §8 open item.
  - The live consumer's call pattern is documented: which endpoints, what payloads, how often, what auth.
  - AlpenFlight's `/api/v1/deliveries/*` matches the consumer's expectations (verified end-to-end against the live consumer in a test config, per S-080).
  - Contact with the Proffix maintainer (arminstutz on GitHub) is established; they're aware AlpenFlight is the successor surface for any tenant migrating off legacy FLS.
estimate: M
adr_refs: []
parity_test: none
---

## Context
Vision §8 open item. Proffix is sacred-cow (C7) and outside our repo — we need to know what to serve so the API shape doesn't drift. Per-tenant: each AlpenFlight tenant that uses Proffix points the sync at its own AlpenFlight URL when ready.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Read the external `PROFFIX-FLS-Sync` repo.
- [ ] Document its call pattern.
- [ ] Compare against AlpenFlight endpoints; flag any drift.
- [ ] Contact maintainer; share AlpenFlight onboarding info for their existing customers.

## Notes
No code changes on the Proffix side expected (sacred cow). The maintainer should know AlpenFlight exists so they can answer customer questions about pointing PROFFIX-FLS-Sync at it.
