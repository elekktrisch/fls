---
name: feedback-idp-portability-no-keycloak-specific-validators
description: "Don't hardwire Keycloak-specific realm config to satisfy resource-server validators — prod IdP could be Google / Ory / Auth0; user/tenant identity goes through DB lookup keyed by sub/email, not vendor-specific claim shapes"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5a9ea496-b197-4282-a5ad-4865de72232a
---

When designing Spring Security validators / authz plumbing for the resource server, do NOT lean on Keycloak-specific realm-export modifications (hardcoded audience mappers, custom client scopes, vendor protocol-mappers) to make validation work. The production IdP is deferred per ADR 0007 — options include Google OIDC, Ory Network, Logto, Auth0, or self-hosted Keycloak. Validation strategy must survive a swap.

Concrete consequence for S-020: ship JWT validators that work against any standards-compliant OIDC issuer — `iss` + `exp/nbf` are universal; `aud` is fine when the IdP emits it but DON'T bake "always-on aud=<our-client-id>" into the validator chain if it requires vendor-specific realm config to land.

For user identity / tenant resolution (clubId), the same logic applies: **do the mapping by DB user data**, not by vendor-specific token claims. The DB is the portable source of truth; the JWT carries `sub` (and ideally `email`) as the lookup key.

**Why:** S-020 surfaced the question "fix the Keycloak realm export to add an audience mapper for `alpenflight-backend`, or defer audience validation?" Operator said: prod could be Google IdP, so do the mapping by DB user data. The vendor-specific boyscout fix would have created drift from the IdP-portability anchor in ADR 0007.

**How to apply:**
- S-020 validator chain = `JwtTimestampValidator` + `JwtIssuerValidator`. No `JwtAudienceValidator` in this story.
- S-022 [[project-clubid-resolution-not-only-jwt]] — already pinned: claim-first, DB-fallback by `sub`/`email`. Same principle.
- Future authz/identity stories: when tempted to "just add a Keycloak protocol-mapper for X" — stop. The same X must work for any OIDC IdP. Move X to the DB or to a code-side validator that doesn't depend on issuer-specific claim shapes.
