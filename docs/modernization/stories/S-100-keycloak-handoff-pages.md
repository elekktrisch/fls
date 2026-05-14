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
