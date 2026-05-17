---
id: S-136
title: Anonymous demo-session scoping — sandbox Deployment access without Keycloak
epic: E-15
status: todo
depends_on: [S-022, S-135, S-137]
acceptance:
  - Navigating to `/demo` provisions a short-lived signed session cookie (`af_anon`, HttpOnly + Secure + SameSite=Lax, 24 h max) carrying a synthetic anon identity `{ user_id: anon-<uuid>, deployment_id: <sandbox-uuid>, club_id: <sandbox-default-club>, roles: [demo-user] }`. If the sandbox Deployment has multiple Clubs, the UX lets the user pick one (default to the first); the picked club_id goes into the cookie.
  - The backend's tenant resolver (S-022) accepts the anon cookie as a valid tenant-context source AND treats it as mutually exclusive with the Bearer JWT — a request carrying both rejects.
  - Anonymous writes succeed against the cookie's chosen sandbox Club; audit-log captures the anon identity.
  - Anonymous sessions cannot reach any Deployment other than sandbox; a controller-level guard rejects with 403 if an anon-session request resolves to a Club whose parent Deployment is not `sandbox`.
  - Demo SPA surface renders a persistent banner: "You're in demo mode — data resets nightly. Sign up to keep your own data." CTA → `/signup?intent=migrate`.
  - Abuse rate-limit: per-IP write rate cap on `/api/v1/*` for anon sessions (refine — initial cut: 60 writes / minute / IP).
estimate: M
adr_refs: [0007, 0008, 0018]
parity_test: tests/sandbox/anon-session.spec.ts (new)
---

## Context
Sandbox Deployment (S-135) needs an authentication path that is NOT Keycloak (we don't want every "Try demo" visitor in our user directory) yet still flows through the same `@TenantId` plumbing real Clubs use.

A signed session cookie issued by the backend on first visit to `/demo` is the path: tenant resolver (S-022) accepts it as an alternative source. Same code path as a real request, different authentication front-door.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend endpoint `POST /api/v1/sessions/demo` that issues the signed cookie.
- [ ] Extend `ClubTenantIdentifierResolver` to accept the anon cookie (refine: chain vs. separate resolver).
- [ ] Controller guard: anon-session requests whose Club belongs to a non-sandbox Deployment → 403.
- [ ] Cross-tenant leakage test (S-024) extended to cover anon sessions.
- [ ] Demo-mode banner component.
- [ ] Per-IP rate limit (refine: in-process bucket vs. Caddy/Traefik filter).
- [ ] Funnel-telemetry events: `demo.session_started`, `demo.signup_cta_click`.

## Notes
- Cookie is signed with a server-side HMAC key (env). Rotation invalidates all anon sessions — acceptable.
- Anon audit-log rows are kept for abuse forensics but excluded from the per-Deployment audit UI (sandbox has no audit UI anyway).
- This is the *only* path that writes to the sandbox Deployment. Real Keycloak users cannot author-switch into sandbox. Sandbox is sealed by role + Deployment check.
