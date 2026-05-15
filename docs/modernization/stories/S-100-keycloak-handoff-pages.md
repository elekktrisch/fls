---
id: S-100
title: Lost-password + email-confirmation landing pages
epic: E-12
status: todo
depends_on: [S-097, S-019]
acceptance:
  - `/lostpassword` page renders a "click the link in your email" message after submitting an email; the actual reset flow is owned by Keycloak.
  - `/confirm` page renders the result of an email-confirmation callback from Keycloak.
  - The previous in-app password reset UI is *not* ported — Keycloak owns it (per ADR 0007).
estimate: S
adr_refs: [0007]
parity_test: none
---

## Context
The legacy app had bespoke password reset; Keycloak handles it now. The SPA still needs landing pages for the user flow.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the two landing pages.
- [ ] Configure Keycloak redirect URIs to point at these pages.

## Notes
The page UX should be branded to match the rest of the SPA — Keycloak's own UI handles the password-entry; our pages handle the bookends.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (C21 mobile-first whole-app):

- **AC-DIR-1 (mobile-first bookend pages).** Both `/lostpassword` and `/confirm` render correctly at ≥ 360 × 640 portrait. Layout uses the same public-layout primitive as S-097, S-098, S-099.
- **AC-DIR-2 (CTAs ≥ 44 × 44 px on `<md`).** "Resend email", "go to login", "open inbox" actions meet the touch-target rule.
- **AC-DIR-3 (Keycloak theme — out of scope here).** Keycloak's own UI (the password-entry form) is themed by Keycloak; that theming lives in S-019 / S-020, not this story. Confirm with the Keycloak theme implementer that the Keycloak pages also render correctly at mobile breakpoints so the user's journey doesn't break mid-flow.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-100` runs.

<!-- amendment-2026-05-15b: end -->
