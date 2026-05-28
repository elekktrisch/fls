---
id: S-180
title: Join-request SPA — admin approval page + modal
epic: E-06
status: todo
depends_on: [S-178, S-176, S-168, S-051]
integration_base: integration/users-suite
acceptance:
  - **/join-requests page** (CLUB_ADMINISTRATOR). Standalone route — the S-166 dashboard tile becomes a follow-up that links here. Lists pending requests for the caller's tenant in submission order: friendlyName + email + submitted-at + truncated note + Approve / Deny actions per row. Counter badge on the nav-bar entry shows pending count (subscribes to SSE for live update — new `join-request.status-changed` events bump the counter).
  - **Approval modal** (one row → "Approve" button → modal). Fields: (a) role checkboxes — same catalog as S-168's invite modal, identical `RoleAssignmentPolicy` gating, defaults match the pilot baseline; (b) optional Person picker reusing S-168's `/api/v1/persons/lookup` exact-match picker; (c) info section showing the request's friendlyName + email + note + submission timestamp (admin's reference, not editable). "Approve" button POSTs `/api/v1/join-requests/{id}/approve` with `{ roles[], personId? }`. On success → modal closes, row drops out of pending list, success toast "{ friendlyName } added to { clubName }."
  - **Deny modal.** Fields: optional reason textarea (capped 500 chars, character counter, "this will be emailed to the requester"). "Deny" button POSTs `/api/v1/join-requests/{id}/deny` with `{ reason? }`. On success → modal closes, row drops out.
  - **Empty state.** No pending requests → friendly "No pending requests" + a "Share your join code" link to the Club edit page (S-177).
  - **Errors.** 409 `User already attached` on approve → "This pilot has joined another club since they submitted; we can't approve here." 409 on the Person picker (Person belongs to another tenant per S-051) → inline picker error.
  - **i18n.** Page + modal strings ship in English first, matching the S-168 / S-051 sibling. FE-i18n consolidation is a separate cross-cutting story.
  - **Tests.** New Playwright spec `alpenflight/web/e2e/tests/join/admin-approve.spec.ts` covering: list-renders-current-pending; approve happy path → t_user materialised + KC clubId attribute set + SSE event observed by the pilot tab; approve-without-Person-picker → Person + PersonClub auto-create assertion via DB read; deny + reason text → pilot-on-deny email asserted via Mailpit; pending-counter badge updates on SSE event.
estimate: M
adr_refs: [0008, 0021]
---

## Context

Q5 + Q8 grilling outcomes: approval surface lives inline on the club-admin dashboard *visually*, but the actual approval page is a standalone route that the dashboard tile (S-166 follow-up) links to. Approval modal requires roles + optional Person picker; auto-Person creation happens server-side (S-178 acceptance) when admin skips the picker. The admin's view is event-driven via SSE (S-176) so the pending counter is live.

## Cross-story contracts

- **Consumes:** S-178 admin-side endpoints; S-176 `MeEventsService` (live counter); S-168 invite modal patterns (role catalog + Person picker); S-051 `/persons/lookup` flow + `mergeManagedRoles` if applicable (review whether out-of-band roles concern applies at approve time — refine pins).
- **Produces:** Standalone admin surface that the S-166 dashboard tile (follow-up) launches into. The approval-modal component is reusable from the dashboard tile.

## Open design questions (for refine)

- **Counter source.** Initial count comes from a `GET /api/v1/join-requests/pending-count` endpoint (or just the list-length on /join-requests) + SSE events bump it. Refine confirms whether a dedicated count endpoint is worth it vs. inferring from the list response.
- **Out-of-band roles at approve.** S-168 ships `mergeManagedRoles` to preserve out-of-band roles (e.g. SYSTEM_ADMINISTRATOR on a user being edited). Approval-time creates a fresh `t_user` row, so there's no prior set to preserve — the merge concern is N/A here. Confirm and document.
- **Bulk approval.** Multi-select + bulk approve was *not* in the grilling scope. Defer to a follow-up; the per-request modal already covers the common case.
- **Audit-trail visibility for admins.** Vision §2 NFR talks about audit logs but not admin-side log surfacing. Admins see request history (approved + denied + withdrawn) on a separate tab? Out of scope for this story; file as a follow-up after the first cluster ships.
