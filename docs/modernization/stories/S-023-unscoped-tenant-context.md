---
id: S-023
title: UnscopedTenantContext mechanism (system admin, jobs, OGN)
epic: E-03
status: todo
depends_on: [S-022]
acceptance:
  - An `UnscopedTenantContext` annotation or programmatic block exists; entering it temporarily switches the tenant resolver to a "no filter" mode.
  - Use is restricted: only methods carrying the annotation, or code inside the programmatic block, can be unscoped. Default is always scoped.
  - Three callers are exercised in tests: a system-admin endpoint (cross-club report), a scheduled job, an OGN ingestion service.
  - A linter check or convention (documented) discourages casual use outside these legitimate cases.
estimate: M
adr_refs: [0008]
parity_test: none
---

## Context
ADR 0008 calls this out as a follow-up. The mechanism has to exist or scheduled jobs (which iterate across all clubs) and the OGN ingestion endpoint (which writes flights for many clubs from a service principal) can't function. But it's also the new R1 if implemented carelessly — needs guardrails.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the resolver dual-mode (delegate to thread-local override; default is principal-driven).
- [ ] Define a `@SystemTenantAware` annotation backed by Spring AOP that sets/clears the override.
- [ ] Provide a `runUnscoped(Runnable)` helper for non-annotated call sites (e.g. jobs).
- [ ] Document the legitimate use cases in `next/server/docs/multi-tenancy.md`.
- [ ] Test with all three caller shapes.
- [ ] Audit-log entry (S-027) for every unscoped operation — "actor did X without tenant scope" is a high-signal event.

## Notes
The mistake to avoid: a "convenience" annotation that anyone reaches for. The right shape is "compile error or explicit annotation," not "easy to call."
