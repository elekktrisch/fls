---
id: S-144
title: Freemium UI upgrade-prompt component + 402 interceptor
epic: E-15
status: todo
depends_on: [S-008, S-143]
acceptance:
  - A SPA-wide HTTP interceptor catches 402 `PLAN_GATE` responses and surfaces a modal (or non-blocking toast — refine) showing: feature name, current plan, required plan, "Upgrade now" CTA → `/account/subscribe`.
  - A reusable primitive `<af-upgrade-prompt>` renders the same prompt in inline mode (for use in gated screens that want to render their upgrade state without a failed call — e.g. the Excel-export button shows the prompt directly when clicked on a `free` tenant).
  - Both surfaces consume the gate registry (`GET /api/v1/plan/features` from S-143) — no hard-coded feature names in the UI.
  - The component meets the touch-target NFR (≥ 44 × 44 CSS px on `<md`); modal renders correctly at the four breakpoints (`sm` 360 portrait → `xl` 1440 dense).
  - Free-tier tenants see a passive "Free plan — upgrade for Excel exports, Proffix sync, and notifications" banner pinned in the dashboard header until dismissed.
  - Funnel-telemetry events: `gate.hit` (with `feature`), `gate.upgrade_cta_click`.
estimate: S
adr_refs: [0017, 0020]
parity_test: tests/billing/upgrade-prompt.spec.ts (new)
---

## Context
Companion to S-143 — the server enforces, the SPA explains. The prompt has two surface areas (reactive modal on 402, proactive inline component) so screens can choose the better UX for their context.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] HTTP interceptor in the SPA.
- [ ] `<af-upgrade-prompt>` primitive in the component kit (S-008).
- [ ] Plan-feature registry caching in the Signal Store (S-006).
- [ ] Inline-mode usage on the Excel-export, Proffix, and Notifications surfaces.
- [ ] Funnel-telemetry hookup.

## Notes
- Operator's call on modal-vs-toast (refine). Modal is more conversion-effective; toast is less interrupting. Probably modal for gated *writes*, toast for gated *reads*.
