---
name: project-walking-skeleton-clubs-mocked-auth
description: "Reshape S-048 (Clubs CRUD) into the walking-skeleton showcase for the kit primitives, with mocked authorization. Selected 2026-05-17. Defers the S-019/S-020/S-022/S-026 auth chain."
metadata: 
  node_type: memory
  type: project
  originSessionId: 60c6c053-e3a6-4f91-ac7c-5232fd92d23a
---

S-048 (Clubs CRUD) is being reshaped (decision 2026-05-17) to land **before the auth chain** as the walking-skeleton showcase for the component primitives kit (S-008). Operator instruction: "Reshape s-048" — after asking "Can we use the clubs crud story as showcase, by mocking the authorization part?"

**Why:** validates the kit primitives against a real domain surface end-to-end, gives the operator a demoable user-facing slice ahead of the full auth chain (S-019 Keycloak → S-020 Spring Security → S-022 TenantId resolver → S-026 authz model), and lets the Playwright snapshot + axe-core suite deferred from S-008 land against a real feature rather than the synthetic `/dev/primitives` route.

**What gets mocked:**
- Frontend: `MockAuthInterceptor` (or similar) stamps a fixed `Authorization: Bearer mock-club-1-admin` header so the SPA doesn't redirect to Keycloak.
- Backend: Spring profile `mock-auth` activates a `MockSecurityConfig` that hard-codes `clubId="club-1"` + roles `[CLUB_ADMINISTRATOR]` on the principal; bypasses the OAuth2 resource-server chain.
- `@TenantId` resolution: short-circuit via the mock principal so `@TenantId` still binds (S-022's seam stays); just sourced from the mock instead of the real JWT.

**What stays real:**
- The actual `clubs` table (V1 schema from S-013).
- The Signal Store + Reactive Forms + form-field/data-table/autocomplete consumption.
- The Playwright e2e suite hitting the real backend.
- The TenantId discipline (server-side filtering).

**Rip-out path:** when S-019/S-020/S-022/S-026 land, the Spring `mock-auth` profile + the SPA mock interceptor get deleted in one commit. The Signal Stores + forms don't change.

**Implications for _ORDER.md:**
- Move S-048 into Phase B/C (early), behind only S-008 + S-013.
- S-019/S-020/S-022/S-026 stay in Phase B/C but become "rip out the mock" rather than "first enable auth."
- S-027 (audit log) stays on its current dependency chain.

**How to apply (next step):** when finalizing S-008, surface this as an ADR amendment proposal + `_ORDER.md` reshape. Reshape itself is a separate `/modernize-decompose` or direct story edit.
