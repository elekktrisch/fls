---
name: project-login-in-keycloak
description: "The actual login form lives in Keycloak (OIDC provider), not the AlpenFlight SPA. Customizing it is a Keycloak theme job (FreeMarker + CSS), separate from the SPA UI polish."
metadata: 
  node_type: memory
  type: project
  originSessionId: c0a3f26e-f788-4ff4-871a-8813bcc19c73
---

The AlpenFlight Angular SPA does NOT host a literal email/password login form.
Authentication is OIDC via Keycloak (`angular-auth-oidc-client`):

- The landing page surfaces a **Sign in CTA** that calls `OidcSecurityService.authorize()`, redirecting the browser to Keycloak's hosted login UI.
- Keycloak handles credential entry, MFA, password reset, etc.
- On success Keycloak redirects back to `/auth/callback`.

**Why:** Tracked here so future "make the login look polished" requests route to
the right place — Keycloak theme customization, not Angular template edits.

**How to apply:** When asked about the login form's visuals (logo on login,
SSO buttons, password reset link styling, error banner copy), do NOT touch
the SPA. Direct the work to `alpenflight/auth/` (Keycloak realm + theme).
Related work: per-tenant whitelabel branding ([[project-rebrand-alpenflight]]
+ ADR 0014) extends to Keycloak's login template via `ui_locales` and theme
selection — same boundary.

The SPA's landing page surfaces brand identity + sign-in CTA + "Try demo"
link; everything *after* clicking sign-in is Keycloak's surface.
