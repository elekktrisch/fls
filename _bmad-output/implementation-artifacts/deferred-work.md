# Deferred Work

Real findings, surfaced incidentally during review, not caused by and not blocking the story that
surfaced them. Collected here for later focused attention. Append-only — do not edit or de-duplicate
existing entries.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: `deploy/Dockerfile`'s runtime stage runs as root with no `HEALTHCHECK`.
  evidence: Production hardening for an unattended, disposable Compose node (AD-16, AD-20) is a
    supplier-operations concern (PRD §4.14), not this template story's scope.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: No `LICENSE` file or per-module license marker reflects the Apache-2.0 (core, open)
    vs. closed-source (pro) module-tier split.
  evidence: This pairs naturally with story 1.3's "core never references a pro module" build gate,
    which already enforces the same boundary at the build-graph level.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: `client/features/system-status/system-status-card.ts` imports a generated OpenAPI type
    via a relative path that reaches into `client/platform`'s internal `src/generated/...` tree,
    with no path alias or public entry point.
  evidence: Every later feature slice copies this pattern (the epic's stated purpose). Worth a
    deliberate module-boundary decision before story 1.5/1.6 add the first real feature slices,
    not a one-off fix now.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: `SystemStatusCard` has no fallback branch for the state where `isLoading()`, `error()`,
    and `hasValue()` are all false.
  evidence: Low-probability given `httpResource`'s eager resolution, but the component renders
    nothing with no user feedback if it ever occurs; worth a defensive `@else` branch next time
    this component or its pattern is touched.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: `exportOpenApiSpec`'s polling loop busy-spins with no backoff on a non-200 response,
    does not fail fast on an early process exit, and leaves the success-path `HttpURLConnection`
    undisconnected.
  evidence: Build-tooling robustness, not user-facing; the task already works end to end.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md`
  summary: The Node runtime version is set independently in `client/platform/build.gradle.kts`
    and `deploy/Dockerfile`'s `FROM node:...` line, rather than both reading
    `libs.versions.toml`'s `nodeRuntime` entry.
  evidence: Gradle and Docker are separate build systems with no native shared-value mechanism;
    full de-duplication needs a build-arg pipeline or a deliberate decision to accept the
    duplication, not a story-1.1 blocker.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md`
  summary: No isolated unit test for `UuidV7Generator` -- it fires only on INSERT and mints
    distinct, time-ordered values -- only indirect coverage exists today through one
    integration test.
  evidence: Test-coverage gap, not a defect; not blocking this story's acceptance criteria.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md`
  summary: `UuidV7Generator` reads the raw physical JDBC connection and issues an extra
    round-trip query per insert, bypassing Hibernate's normal execution path.
  evidence: Acceptable for `club`'s low insert volume today; worth revisiting before a
    high-insert table (e.g. `flight`, per this story's own Design Notes) adopts the same
    generator.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md`
  summary: The `club_isolation` RLS predicate would raise a raw cast error if
    `app.current_club_id` were ever set to a non-UUID string.
  evidence: Not reachable today -- only test code sets the variable, always with a valid UUID.
    Revisit when story 1.7's real transaction opener starts setting it from a JWT claim.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md`
  summary: No documented local-dev reset procedure for dropping the `postgres-data` volume or
    re-importing the Keycloak realm when migrations or realm content change during iteration.
  evidence: Cosmetic developer-experience gap, not a functional defect.
