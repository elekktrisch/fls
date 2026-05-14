---
id: S-040
title: Production Dockerfile for the Spring Boot service
epic: E-05
status: todo
depends_on: [S-001]
acceptance:
  - Multi-stage Dockerfile: build stage with full JDK; runtime stage with `eclipse-temurin:25-jre-alpine` or distroless.
  - Runs as non-root user.
  - JVM flags tuned for container memory (`-XX:MaxRAMPercentage=75`).
  - Image is tagged with a git SHA; size < 250 MB.
  - Liveness/readiness work against `/actuator/health/liveness` and `/actuator/health/readiness`.
estimate: S
adr_refs: [0010]
parity_test: none
---

## Context
ADR 0010 follow-up. The image is referenced by S-039.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Write the Dockerfile.
- [ ] Add a build step in CI that builds + tags the image.
- [ ] Verify `docker run` against the dev compose works.

## Notes
Distroless gives the smallest attack surface; eclipse-temurin Alpine gives easier debugging (it has a shell). Either is fine; **eclipse-temurin alpine** recommended for solo-operator debuggability.
