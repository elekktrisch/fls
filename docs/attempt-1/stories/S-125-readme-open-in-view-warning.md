---
id: S-125
title: Surface open-in-view=false consequence in alpenflight/server/README.md
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: []
adr_refs: []
refined: false
origin: rework
origin_story: S-001
origin_finding: open-in-view=false is pinned in application.yml with a code comment but the LazyInitializationException-is-intentional consequence is invisible from a README skim; needs surfacing before S-012 (JPA) lands so contributors don't misread the runtime failure mode.
---

## Context

Follow-up from review of S-001 (originating story). The originating story's review found:

> `open-in-view=false` consequence is not surfaced in README. The `application.yml:9-10` comment is good but invisible from a README skim. Future contributors hitting `LazyInitializationException` in S-012+ deserve to know it's intentional.
> **Suggested fix:** one sentence in the conventions table.
> **Path:** `alpenflight/server/README.md` (conventions table or a JPA-conventions subsection).

See [`S-001-scaffold-server-skeleton.md`](S-001-scaffold-server-skeleton.md#review) for full review context.

Deferred from S-001 because S-012 (Schema: identity and reference) is the first story that actually introduces JPA entities and where this design choice will start producing visible runtime behavior. Better to bundle the README update with S-012's documentation so the surrounding context — fetch-join strategy, `@EntityGraph`, DTO projection — lands together.

## Acceptance criteria

- [ ] `alpenflight/server/README.md` carries a one-paragraph (or table-row) entry under conventions explaining: (a) `spring.jpa.open-in-view=false` is the project default, (b) consequence is `LazyInitializationException` outside the persistence boundary, (c) approved escape hatches (fetch-join, `@EntityGraph`, DTO projection).
- [ ] The entry is discoverable in a 5-minute README skim — not buried in an FAQ-style appendix.
- [ ] If S-012's design notes already state this contract, link from the README to the relevant section rather than duplicating.
