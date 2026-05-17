---
id: S-133
title: Public marketing landing CTA — "Migrate from legacy FLS" + "Try demo"
epic: E-15
status: todo
depends_on: [S-097, S-008]
acceptance:
  - The public landing page (`/`) renders two above-the-fold CTAs: "Migrate from legacy FLS" → routes to `/signup?intent=migrate`; "Try demo" → routes to `/demo` (sandbox entry, see S-136).
  - Both CTAs meet the touch-target NFR (≥ 44 × 44 CSS px on `<md`) per vision-doc §2 + S-097 amendment 2026-05-15b.
  - Page renders correctly at the four vision-doc breakpoints (`sm` 360, `md` 768, `lg` 1024, `xl` 1440); CTAs stack on `<md` and sit side-by-side on `≥md`.
  - Below the fold: short value-prop copy ("Your data, encrypted, in one click"), a 3-step graphic (signup → upload → use), and a "How it works" link that scrolls to a longer explainer.
  - A funnel-telemetry event `landing.cta_click` fires with `cta_id ∈ { migrate, demo }` (see S-147).
estimate: S
adr_refs: [0004, 0017, 0018]
parity_test: none (greenfield — no legacy equivalent)
---

## Context
Extends S-097 (landing page port) with the migration-path CTAs introduced by vision amendment 2026-05-17c. The legacy landing has no equivalent — this is greenfield marketing copy + a structured CTA pair that funnels visitors into either the signup flow (S-134) or the sandbox demo (S-136).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `/demo` and `/signup` routes to the public layout (`publicLayout: true`).
- [ ] Build the CTA pair as a primitive in S-008 (`<af-hero-cta-pair>`) so other public pages can reuse it.
- [ ] Marketing copy: short headline + 1-paragraph value prop + 3-step graphic. Drafted in English first; i18n hooks in place for later.
- [ ] Emit funnel-telemetry events (see S-147 for the convention).

## Notes
- The CTAs are intent-aware: the `intent=migrate` query param sticks through Keycloak signup so the post-signup landing can route the user into the JAR-download flow rather than the demo. (See S-134.)
- Headline copy is the operator's call. Leave a TODO placeholder + a short list of suggestions; the operator picks during refine.
