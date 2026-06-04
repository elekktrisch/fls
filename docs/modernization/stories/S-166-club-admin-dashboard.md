---
id: S-166
title: Home/dashboard page — club-admin variant
epic: E-07
status: todo
rolled_up_into: J-3
depends_on: [S-165]
acceptance:
  - When the authenticated user has the `CLUB_ADMINISTRATOR` role, `/start` renders the club-admin variant instead of the pilot variant.
  - Variant covers admin-facing surfaces (TBD during refine — candidates: pending-flight approvals, today's club activity, flight-report inbox, member status).
  - Pilot variant remains the fallback for users with `CLUB_ADMINISTRATOR` who explicitly toggle to "Pilot view" (UX TBD).
estimate: M
adr_refs: [0008]
---

## Context
Follow-up to S-165. Consumes the `GET /api/v1/me` endpoint shipped by S-165 for role-based variant routing (`roles` array). Club admins have different at-a-glance needs than pilots — pending approvals, today's airfield activity, billing-readiness flags. The pilot variant from S-165 doesn't fit those needs.

**Scope is TBD until refine.** Decompose the admin-facing surfaces with the operator before committing to ACs; the design reference (`screens-home.jsx`) does not cover the admin variant.

## Open questions for refine

- Which admin-facing data lands at MVP? Likely candidates: count of flights in `NotProcessed` / `Invalid` states (pending validation), today's flight count, pending delivery batches, recently-created member records.
- Does the variant share the layout shell with the pilot variant or replace it entirely?
- Does the club-admin still see their personal "Your last flight" card alongside admin tiles, or is the admin context-switch total?
- Toggle between admin / pilot views — UI affordance and where it lives.
