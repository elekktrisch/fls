---
name: project-demo-mode-feature-note
description: FLS rewrite should expose a demo / try-it mode so prospective users can experience the system without being onboarded — captured as an open item in vision §8
metadata: 
  node_type: memory
  type: project
  originSessionId: 2ebfc468-e2a8-4504-b8cd-c3ac72861da0
---

The FLS rewrite (`next/`) should ship a **demo / try-it mode** that lets prospective club admins or pilots experience the actual flight-log, reservation, and planning UX without being onboarded into a real tenant.

**Why:** the operator's onboarding funnel currently goes straight from "landing page" (S-097) to "be onboarded as a paying club" — there's no in-between. A demo surface lowers the friction for a prospect's "let me see what this actually looks like" moment, and gives the operator something concrete to demo on a sales call. It's a marketing / GTM lever, not a feature requirement.

**Open shape (three options, not yet decided):**
- (a) Read-only seeded demo tenant (`demo-club`) anyone can browse — no writes, pre-populated synthetic data.
- (b) Sandbox tenant that resets nightly and accepts writes from anonymous demo sessions — heavier; needs PII isolation + abuse rate-limit + cron reset.
- (c) Storybook-style isolated component showcases — lightest; doesn't show the integrated workflow.

**Where it's tracked:**
- `docs/modernization/02-vision-and-constraints.md` §8 Open items (added 2026-05-15)
- Touches multi-tenancy contract (ADR 0008 / S-022) — demo tenant must be scope-able without breaking the `clubId` claim invariant from S-019. May warrant an ADR or sub-story decision before implementation.
- Likely a new epic or extension of E-12 (public flows); S-097 (landing-page) is the natural place to host a "Try the demo" link slot.

**How to apply:** when working in phase 4 decomposition, public flows (E-12), or anything touching S-097 / S-098 / S-099 / S-100 — surface this note. It should NOT be an in-flight requirement that gates cutover; it's post-MVP-shaped scope that should still be visible. If the operator escalates priority, lift to a refined story; otherwise leave as a tracked open item.

Related: [[fls-modernization-workflow]] for the workflow that picks this up in phase 4.
