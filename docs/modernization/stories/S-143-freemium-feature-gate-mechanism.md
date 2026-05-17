---
id: S-143
title: Freemium feature-gate annotation + server-side enforcement + 402 contract (ADR 0020)
epic: E-15
status: todo
depends_on: [S-026, S-137]
acceptance:
  - `@PlanGate(feature = "<feature-key>", min = ACTIVE)` annotation applied to a controller method (or service method) intercepts calls; if the request's Club's parent Deployment has `plan` below `min`, the request fails fast with HTTP 402 Payment Required + body `{ code: "PLAN_GATE", feature, current_plan, required_plan, upgrade_url }`.
  - The gate evaluator reads the current Club's parent Deployment's `plan` field (derived from `Deployment.lifecycle_state`: `trial` + `active` → `active`; `past_due` retains read access but blocks gated writes — refine via ADR 0020; `cancelled` / `sandbox` → `free`).
  - Per vision C30, the following feature keys exist out-of-the-box with default `min`:
    - `excel-export` → `active`
    - `proffix-integration` → `active`
    - `notifications-email` → `active`
    - `notifications-push` → `active`
    - `scheduled-jobs.opt-in` → `active`
    - `club-limit-extra` → `active` (free is hard-capped at 1 Club per Deployment)
    - `aircraft-limit-extra` → `active` (free is hard-capped at 2 aircraft per Deployment)
    - `user-seat-extra` → `active` (free is hard-capped at 5 active users per Deployment)
  - The Club / aircraft / user caps are enforced at the *create* path: creating a 2nd Club, a 3rd aircraft, or a 6th user in a `free` Deployment returns 402. Caps apply across the whole Deployment (an Org with 3 Clubs on free still gets a total of 2 aircraft + 5 users, not 6 / 15) — evaluator uses `DeploymentContext` (S-137) to sum across Clubs.
  - Free-tier Deployments retain full audit-log emission (vision C32 — audit applies regardless of plan), even though the audit-log UI is gated.
  - Registry endpoint `GET /api/v1/plan/features` returns the gate config so the SPA renders upgrade prompts without hard-coding feature names.
  - Integration test: a `free` Deployment's Club making a gated POST returns 402 with the expected envelope.
estimate: M
adr_refs: [0018, 0020]
parity_test: tests/billing/feature-gates.spec.ts (new)
---

## Context
Vision C30 specifies the freemium gate axis. ADR 0020 locks the mechanism so every gated feature shares one annotation + response shape. C34 specifies that the plan lives on the Deployment, not the Club — so multi-Club Deployments share a single plan.

The annotation lives on the gated boundary — usually the controller method, sometimes a service method when reached from multiple endpoints. Evaluator is a Spring AOP advice or `HandlerInterceptor`.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `@PlanGate` annotation + interceptor.
- [ ] Plan-resolver service: read Deployment plan from the current Club's `deployment_id`.
- [ ] Apply the annotation to the eight default features listed in acceptance.
- [ ] Cap-at-create enforcement for Club + aircraft + active-user counts, summed across the Deployment via `DeploymentContext`.
- [ ] Registry endpoint.
- [ ] 402 contract documented in OpenAPI surface (S-003).

## Notes
- Per vision §2 NFR: server-side enforcement is the contract; SPA UI is informational only. Bypassing via curl must still return 402.
- `trial` Deployments are treated as `active` for gates so the user can experience the full product before the subscribe-or-delete moment.
- Free-tier Deployments are capped at 1 Club. A free-tier Deployment that *was* provisioned with multiple Clubs from a bundle (S-138) keeps those Clubs but cannot create more, and the user sees an upgrade prompt referencing the cap.
