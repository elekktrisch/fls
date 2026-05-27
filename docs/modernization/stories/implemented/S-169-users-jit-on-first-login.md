---
id: S-169
title: Users — JIT projection on first authenticated login
epic: E-06
status: done
started_at: 2026-05-27
done_at: 2026-05-27
estimate: S
parity_test: none
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0007, 0022, 0023]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 139
github_pr: 140
origin: scope-split
origin_story: S-052
---

## Context

Split off from [S-052](implemented/S-052-users-crud.md). S-052 shipped
`UsersService.materializeFromJwt`; this story wires the call into the
authenticated-request lifecycle so the local `t_user` projection appears
on first login for any KC-resident principal — covering both bulk-provisioned
realm users (S-028) and federated IdP onboarding (S-134).

## Acceptance criteria

- On any authenticated request with a UUID `sub` + parseable UUID `clubId`
  claim, a `t_user` row exists before controller dispatch.
- Soft-deleted tombstones refuse the residual JWT with 403 problem+json —
  closes the in-window stale-token gap (≤ 15 min per ADR 0007).
- Concurrent first-login requests for the same `sub` resolve to exactly
  one row.

## Cross-story contracts

- **Soft-delete + re-entry resolution.** Operator decision during refine:
  `UsersService.softDelete` does **not** null `keycloak_sub` (preserves the
  gate's lookup surface). The re-invite path (`UsersService.invite`)
  detaches the tombstone's sub before inserting the new row so the
  partial UNIQUE admits the re-use. The original refinement notes said
  softDelete clears the sub — that approach defeats the gate; resolved in
  favour of keep-sub-on-tombstone.

## Follow-ups implied

- **S-172 (proposed) — Federated IdP multi-club model.** One KC identity
  alive in N clubs: drop the global `ux_user_keycloak_sub`, add
  `(keycloak_sub, club_id) WHERE deleted_on IS NULL`. Cross-cuts
  `ClubTenantIdentifierResolver` (one sub → multiple tenants → club-picker
  on login). File after this story soaks.
- **V8 dev seed deletion.** `V8__dev_user_seed.sql` stays in place until
  this story has shaken out one full dev bring-up cycle. Follow-up
  migration drops it.
- **KC disable on softDelete.** Out-of-window gate (invalidate refresh
  tokens at the IdP) on top of the JIT in-window gate. Couples to S-052;
  file when reactivate-UI lands.
