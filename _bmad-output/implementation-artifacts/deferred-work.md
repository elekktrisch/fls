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

- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-the-build-gates-and-ci.md`
  summary: Build gate 4 -- every table declares its data kind (`system-reference`, `club-scoped`,
    `cross-club-link`, or the `club`-only `tenant-root` exception) via
    `COMMENT ON TABLE ... IS 'data-kind: ...'`, and every club-scoped table's row-level-security
    policy is verified by an automated check, not just declared by story 1.2's migration.
  evidence: Story 1.3's spec exceeded the 1,600-token target with all nine gates included. Gates
    1, 2, 3, 5, 6, 7, 8, and 9 form one complete, working CI pipeline on their own; gate 4 needs a
    new migration (`V3__club_data_kind.sql`) and a new Testcontainers-backed check
    (`server/core/src/integrationTest/.../DataKindAndRlsGateIT.java`) and is self-contained enough
    to land as a fast follow-up patch to the same `.github/workflows/ci.yml` this story creates.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-the-application-shell-and-the-design-tokens.md`
  summary: The shell's `:focus-visible` ring (`2px solid {live}`, `1px` offset, never removed) has
    no automated regression test.
  evidence: Verifying a CSS outline rule needs visual/e2e tooling this early build stage does not
    have yet; `alpenflight/` has no e2e suite of its own (AD-21). Revisit once one exists.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-the-application-shell-and-the-design-tokens.md`
  summary: `DESIGN.md`'s `components.topbar` entry defines `wordmarkColor`/`wordmarkType` for a
    brand wordmark in the top bar; the shell's top bar renders only the four nav links.
  evidence: Neither `EXPERIENCE.md`'s navigation table nor this story's spec calls for a wordmark
    in the shell itself (Home already shows the "AlpenFlight" heading). Whether the top bar should
    carry one is an open design question for a later story, not an omission this story's Code Map
    committed to.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-the-application-shell-and-the-design-tokens.md`
  summary: The shell's nav and routed content apply no `--spacing-container-max` (1440px)
    constraint, so both can stretch unbounded on a very wide viewport.
  evidence: Token exists and is published in `styles.css`; no current screen has content that
    would look broken at 1440px+ yet. Apply it once a feature slice's layout needs the cap.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-the-application-shell-and-the-design-tokens.md`
  summary: A hard refresh or direct URL entry at `/operate`, `/plan`, `/records`, or `/admin`
    against the production build has no server-side SPA fallback (`historyApiFallback`) verified;
    the Spring Boot static-resource serving this story's client build lands in was never checked
    for a catch-all forward to `index.html`.
  evidence: Purely server-side deployment concern, outside `client/platform`'s scope and this
    story's Code Map; `ng serve`'s dev server already handles it transparently for local dev.
