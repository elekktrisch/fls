---
id: S-123
title: Lock down springdoc off-state until S-003 wires it
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: []
adr_refs: []
refined: false
origin: rework
origin_story: S-001
origin_finding: springdoc-openapi-starter-webmvc-ui is on classpath at S-001 with api-docs/swagger-ui disabled by config; a stray env var (Spring relaxed binding) could flip it on with no code change.
---

## Context

Follow-up from review of S-001 (originating story). The originating story's review found:

> `springdoc-openapi-starter-webmvc-ui` is on the classpath now with `api-docs.enabled=false` / `swagger-ui.enabled=false`. A stray `springdoc.swagger-ui.enabled=true` env var (Spring's relaxed binding) flips it on with no code change.
> **Suggested fix:** either defer the dependency to S-003 (cleanest), or add an integration test asserting `/swagger-ui/index.html` returns 404 under the prod profile so the off-by-default state is regression-locked.
> **Path:** `next/server/build.gradle.kts` (dependency) + `next/server/src/main/resources/application*.yml` (config) + a new test under `next/server/src/test/java/ch/fls/`.

See [`S-001-scaffold-server-skeleton.md`](S-001-scaffold-server-skeleton.md#review) for full review context.

This decision is intentionally left open — the next refine pass should choose between:

1. **Move the dependency to S-003.** Removes the attack surface entirely until springdoc is actually wired. S-001 has no swagger consumer; the dep is purely anticipatory. Cleanest if S-003 is imminent.
2. **Keep the dependency, add a prod-profile regression test.** `@SpringBootTest(properties = "spring.profiles.active=prod")` + `MockMvc` asserting `/swagger-ui/index.html` returns 404 and `/v3/api-docs` returns 404. Locks the off-state via test rather than absence.

S-003 is imminent in execution order, so option 1 is the leading candidate.

## Acceptance criteria

- [ ] Either: `springdoc-openapi-starter-webmvc-ui` is removed from `next/server/build.gradle.kts` until S-003 reintroduces it; OR a prod-profile integration test under `next/server/src/test/java/` asserts `GET /swagger-ui/index.html` and `GET /v3/api-docs` both return 404.
- [ ] If the dep is removed, S-003's own AC must explicitly include reintroducing it (cross-reference noted in S-003).
- [ ] If the test approach is chosen, the test passes against the current S-001 config without further changes.
