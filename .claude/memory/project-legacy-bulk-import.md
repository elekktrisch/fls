---
name: project-legacy-bulk-import
description: "Cutover must bulk-import multiple clubs + multiple clubadmins at once from a legacy FLS deployment. Bigger than S-028's single-tenant scope."
metadata: 
  node_type: memory
  type: project
  originSessionId: e9c21872-536a-4ded-862d-7d30fd1f1531
---

The cutover story isn't "one tenant onboards at a time" — it's **import a complete legacy FLS deployment**, meaning **N clubs × M users (including N+ club admins) at once**.

**How to apply:**
- S-028 (bulk-provision tenant users in Keycloak) as currently scoped (single tenant, operator-driven) is the *building block*, not the whole story. A higher-level cutover story (own ID, possibly S-15x territory) wraps it: iterate the legacy `Club` + `User` + `PersonClub` rows → for each row, create Keycloak realm-side artifact + DB-side artifact in lockstep.
- Multiple `SYSTEM_ADMINISTRATOR` + many `CLUB_ADMINISTRATOR` rows on import — the role catalog (`SYSTEM_ADMINISTRATOR`, `CLUB_ADMINISTRATOR`, ...) and the `clubId` user-attribute schema in S-019 must support this without realm reshape.
- Per C14 (vision): imported users get `requiredActions: ["UPDATE_PASSWORD"]` — every legacy user receives a reset link rather than a migrated password hash.

**Implications for S-019:** none today — the realm shape (clients, roles, mapper) supports bulk users natively via Keycloak's admin REST API. But the README should call out that the 3 seed users are *dev fixtures*, not the cutover plan.
