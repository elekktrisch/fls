---
id: S-034
title: GlitchTip in compose + Spring + Angular SDK integration
epic: E-04
status: todo
depends_on: [S-001, S-002, S-039]
acceptance:
  - GlitchTip runs via docker-compose (its required Postgres + Redis bundled).
  - `sentry-spring-boot-starter` is wired against GlitchTip's DSN; an unhandled exception in a test endpoint shows up in GlitchTip within 1 minute with tenant + actor context attached.
  - Angular Sentry SDK is wired; an unhandled SPA exception shows up in GlitchTip.
  - Release tagging is configured: each deploy tags errors with the build version.
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
The "Sentry-equivalent" NFR. GlitchTip is the lightweight Sentry-compatible choice for day-1 self-hosting.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add GlitchTip + its dependencies (Postgres, Redis) to compose.
- [ ] Add `sentry-spring-boot-starter` to backend; configure DSN, release tag, environment.
- [ ] Add `@sentry/angular` to SPA; configure release tag + integrations.
- [ ] Wire tenant + actor context as Sentry tags (Spring side from security context, Angular side from `SessionStore`).
- [ ] Smoke test: deliberate exception → appears in GlitchTip within 1 min.

## Notes
GlitchTip is the practical choice over self-hosted Sentry (which needs many containers); both speak the same SDK so swapping later is config-only.
