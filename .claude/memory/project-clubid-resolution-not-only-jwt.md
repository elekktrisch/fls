---
name: project-clubid-resolution-not-only-jwt
description: clubId must resolve from JWT claim OR DB user record (Google IdP users have no clubId claim). Affects S-022 @TenantId design.
metadata: 
  node_type: memory
  type: project
  originSessionId: e9c21872-536a-4ded-862d-7d30fd1f1531
---

The `clubId` claim that S-019's Keycloak protocol mapper emits is **not the only source** S-022's `@TenantId` resolver can rely on. Two real-world cases break the "claim is always there" assumption:

1. **Google OIDC (and other social IdPs)** — users sign in via Google federated identity. Their JWT carries `email`, `sub`, etc., but no `clubId` user-attribute. Future story: S-134 self-service signup + Google IdP.
2. **Imported legacy users** — when bulk-importing from an FLS deployment (S-028 / a later import story), users may exist in Keycloak before their `clubId` attribute is populated.

**How to apply** in S-020 / S-022 design:
- The `@TenantId` resolver must read the `clubId` claim if present, but **fall back to a DB lookup** (`user` table keyed by `sub` / `email`) when absent.
- Treating "no clubId" as automatically cross-tenant (SYSTEM_ADMINISTRATOR shortcut) is **wrong** for the Google IdP case — those users have a clubId, it just lives in the local DB.
- S-019's mapper still belongs in the realm — it covers the Keycloak-native-account fast path. The DB fallback is the S-022 backstop.
