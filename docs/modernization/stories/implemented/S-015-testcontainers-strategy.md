---
id: S-015
title: Testcontainers test-DB strategy + helpers
epic: E-02
status: done
started_at: 2026-05-18
done_at: 2026-05-18
github_issue: 64
github_pr: 65
depends_on: [S-009]
acceptance:
  - Test-DB strategy decided: **per-test pre-clean per ADR 0021** (NOT `@Transactional` rollback as originally refined — rollback doesn't survive HTTP boundaries; CONVENTIONS.md documents).
  - A shared `@SpringBootTest`-with-test-DB base class is committed (`PostgresIntegrationTest`).
  - A `@WithTenant(clubId)` annotation that sets tenant context before each test. Value is `String` (UUID-string literal) parsed to `UUID`, per the S-022 pre-coordination contract.
  - The hello-endpoint integration test from S-001 runs against the Testcontainers DB (`HelloEndpointPostgresIT`).
estimate: M
adr_refs: [0003, 0008, 0021]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
reviewed: true
reviewed_at: 2026-05-18
review_outcome: improvements-only
review_blockers: 0
review_improvements: 4
review_nudges: 5
review_parity_oracle: N/A — greenfield test infrastructure; no legacy oracle exists
review_reviewers: [maintainability, security, tech-writer]
merged: true
merged_at: 2026-05-18
---

## Context

`PostgresIntegrationTest` is the consolidated base class for full-stack DB-touching `@SpringBootTest` integration tests. The S-015 surface (`@WithTenant`, `TenantContextExtension`, `TenantTestContext`) ships test-only; S-022 retrofits the extension to also push the resolved tenant into Spring Security's context so the production resolver consumes the same value via the production code path.

## Cross-story contracts

- **S-022** consumes: `@WithTenant(String)` (UUID-string), `TenantContextExtension` swap-in point, `TenantTestContext.{current, runAs, runUnscoped, NO_TENANT}`. The `NO_TENANT` nil-UUID sentinel matches the resolver's chosen sentinel; `Optional.empty()` (no annotation) is **distinct** from `runUnscoped()` (explicit unscoped intent).
- **S-023 (UnscopedTenantContext)** consumes: `TenantTestContext.runUnscoped(Runnable)` as the test-side surface for the unscoped path.
- **S-024 (cross-tenant leakage CI)** consumes: `TenantTestContext.runAs(UUID, Runnable)` as the canonical leakage pattern (create-as-A → switch to B → assert empty).

## Deviations from refinement

- **No `@Transactional` rollback.** S-015's original refinement chose Spring's `@Rollback(true)` + class-level `@Transactional` as the isolation strategy. ADR 0021 (landed post-refine, 2026-05-16) explicitly rejected that approach: rollback doesn't survive HTTP boundaries (`MockMvc` / `TestRestTemplate` requests run in their own transaction). Implementation follows ADR 0021's per-test unique-`Club` + pre-clean-at-start pattern. Existing `ClubsControllerIT` already uses this pattern; the shared `IntegrationTestSupport.createTestClub(...)` helper lands at S-022.
- **`@WithTenant(String)` not `@WithTenant(long)`.** Java annotations cannot carry `UUID`. Pre-coordinated with S-022's resolver contract (PR #63).
- **No `org.testcontainers:postgresql` dependency** — kept the S-009-established Docker-CLI lifecycle helper (`PostgresTestContainerLifecycle`) because Testcontainers 1.21.x bundles docker-java 3.4.x which negotiates Docker REST API 1.32; the sandbox daemon requires ≥1.44.

## Review

<!-- modernize-review: start -->

### Parity

**Oracle:** N/A — greenfield test infrastructure; no legacy oracle exists.

<!-- modernize-review: end -->
