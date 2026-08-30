---
id: E-12
title: Public (no-auth) flows
status: todo
adr_refs: [0007, 0008]
---

## Goal
Port the three public flows — landing page, trial-flight registration, passenger-flight registration — onto the new stack with the nav-bar-hiding mechanism fixed (R12: the `||` tautology bug must not be reproduced), plus the password-reset and email-confirmation landing pages that hand off to Keycloak (since C14 + ADR 0007 push those flows into the IdP).

## Scope
- In: landing page; trial-flight registration form + endpoint; passenger-flight registration form + endpoint; password-reset landing page (renders Keycloak callback result); email-confirmation landing page; nav-bar mechanism that actually works (e.g. route flag, layout slot — *not* a boolean expression).
- Out: the password-reset and email-confirmation business logic (Keycloak owns it).

## Stories
- [ ] S-097 — Landing page port + nav-bar mechanism (closes R12)
- [ ] S-098 — Trial-flight registration port (public POST)
- [ ] S-099 — Passenger-flight registration port (public POST)
- [ ] S-100 — Lost-password + email-confirmation landing pages (Keycloak handoff)

## Done when
- Specs `01` `09` and the `landing.spec.ts` pass.
- Public POST endpoints accept the tenant-from-URL/form pattern (S-025) and reject unsupported tenant IDs.
- The nav-bar is hidden on `/trialflight` and `/passengerflight` by a verifiable mechanism, *not* by the broken `||` expression — exercised by a Playwright assertion.
