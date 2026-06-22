# 0029 — Dev/test-only HTTP affordances live in the production module, profile-gated

- **Status:** Accepted (operator-approved 2026-06-22)
- **Date:** 2026-06-22
- **Scope:** Where the test/e2e support endpoints that have no production CRUD
  surface (seed a fixture, trigger an orchestration) physically live, and the
  guardrails that keep them unreachable in production.

## Context

Integration tests and the real-idp Playwright suite need trigger/seed endpoints
that no production screen exposes — provisioning a deployment under a synthesised
principal, granting a `PersonFlightTimeCredit` matched to a freshly-minted
immatriculation. Two such affordances already shipped:

- `tenancy.provisioning.web.InternalProvisioningController` — `@Profile("test")`,
  `@Hidden`, `/api/v1/internal/migrations` (on `main` since the provisioning work).
- `accounting.web.InternalPersonFlightTimeCreditSeedController` — `@Profile({"dev","test"})`,
  `@Hidden`, `/api/v1/internal/person-flight-time-credits` (J-9b).

Both sit in `src/main` of their module. The pattern was established by imitation,
never adjudicated — so the seed/test code is **compiled into the production
server artifact**, gated only at runtime. The operator flagged the ambiguity
(2026-06-22): is this an ADR violation, or a missing ADR? It is the latter — no
ADR governs where test affordances live. [ADR 0021](0021-integration-test-data-isolation.md)
governs test *data* isolation, not test-support *code* placement.

## Decision

**Sanction the pattern.** A dev/test-only HTTP affordance MAY live in `src/main`
of the module that owns the domain it exercises, and MUST carry all four:

1. **`@Profile(...)` naming only non-production profiles** — `@Profile("test")`
   for IT-only, `@Profile({"dev","test"})` when the real-idp e2e backend (which
   boots `dev`) also needs it. Production runs neither, so the bean is never
   instantiated — the class is on the classpath but inert at runtime.
2. **`@Hidden`** — kept out of the OpenAPI snapshot (no client is generated for it).
3. **`/api/v1/internal/` path prefix** — a single, greppable namespace a
   production gateway/ingress can deny wholesale, independent of the profile gate.
4. **A role gate (`@PreAuthorize`)** + the audited mutation behind the application
   service (never inline in the controller).

The two existing controllers conform; new affordances follow this shape.

## Alternatives considered

- **Relocate to a dev/test-only Gradle source set / module off the production
  classpath** (structural exclusion — the class can't ship in the prod JAR).
  Rejected: the real-idp e2e backend boots the `dev` profile against the
  production assembly, so the affordance can't live in `src/test` (not on that
  classpath); a dedicated dev-only source set adds build machinery + a second
  classpath seam for a marginal gain over the profile + `/internal/`-gateway
  exclusion already in place. Revisit only if a hard requirement emerges that
  test code must be physically absent from the production artifact (e.g. a
  supply-chain audit).
- **Leave undocumented.** Rejected — the operator flagged exactly this ambiguity;
  an established pattern with security-relevant guardrails earns one greppable rule.

## Consequences

- **Positive:** one rule for every `/api/v1/internal/` affordance; the prefix is a
  single production deny point; the profile gate means a misconfiguration fails
  closed (absent the profile, no bean). Both existing controllers are legitimised.
- **Negative:** the affordance code is physically present in the production
  artifact — defense by runtime gate + gateway deny, not structural exclusion.
  Mitigation: never enable the `dev`/`test` profile in production; deny `/internal/`
  at the edge; the four annotations are mechanically checkable.
- **Follow-up (rider, not now):** an ArchUnit guard asserting every controller
  mapped under `/api/v1/internal/` carries `@Profile` (non-prod) + `@Hidden`, so a
  future affordance can't silently ship reachable in production.
