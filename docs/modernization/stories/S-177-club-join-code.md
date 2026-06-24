---
id: S-177
title: Club join code — field + admin rotate endpoint + admin UI
epic: E-06
status: todo
rolled_up_into: J-12a
depends_on: [S-048]
integration_base: integration/users-suite
acceptance:
  - `Club` aggregate gains a `joinCode` field — a short, human-shareable string (8 chars from a 32-char alphabet — `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, ambiguous chars stripped). Carries 40 bits of entropy. Generated at Club creation and on every rotation.
  - Flyway migration adds `t_club.join_code TEXT NOT NULL` + `UNIQUE INDEX ux_club_join_code (join_code)` (global UNIQUE — code must resolve unambiguously to one Club across all tenants and Deployments). Backfill for existing clubs in V… migration: generate a fresh random code per row, retry on collision.
  - `Club.rotateJoinCode(Clock)` mutator on the aggregate. Business rule: rotation is always allowed for CLUB_ADMINISTRATOR; no rotation rate-limit at the domain level (rotation cost is small and the audit row is enough).
  - `POST /api/v1/clubs/{clubId}/join-code/rotate` (CLUB_ADMINISTRATOR, tenant-scoped) returns `{ joinCode }` with the new value. Emits a `club.join_code_rotated` audit event with actor + clubId; **the audit blob does not contain the old or new code** (codes are quasi-secrets; an admin reading the audit log shouldn't be able to recover a club's current code).
  - `GET /api/v1/clubs/{clubId}` (CLUB_ADMINISTRATOR view) surfaces the current `joinCode`. Pilots (non-admin) calling the same endpoint do NOT see `joinCode` in the response — the field is admin-only on the wire.
  - Admin UI on the Club edit page (the existing one shipped in S-048): a "Join code" panel shows the current code + a "Rotate" button + a "Copy" button. After rotate, the new value displays and the old value is gone.
  - The code is **not** treated as an authorization token — submitting it filed a request to join (S-178), not an automatic admission. Rotation invalidates the code as a discovery key for new submissions; pending requests filed under the old code stay valid until decided.
estimate: S
adr_refs: [0008]
---

## Context

Q3/Q4 grilling outcome: the user-initiated join flow uses an **admin-shared, per-club, rotatable, multi-use** code. The code is a discovery key only; the gate is the admin's approval (S-178). This story carries just the code itself + admin rotation surface; the join-request mechanics live in S-178.

## Cross-story contracts

- **Consumes:** S-048 Club aggregate + admin edit surface.
- **Produces:** `Club.joinCode` field + `rotateJoinCode()` mutator + admin-only DTO field. Consumed by S-178 (code-to-club resolution) and S-180 (admin UI panel).

## Tasks

- [ ] V… migration: `t_club.join_code` column + UNIQUE index + per-row random backfill (retry on collision).
- [ ] `JoinCodeGenerator` utility in `clubs.domain` — deterministic length + alphabet; collision-resistant generator.
- [ ] `Club.rotateJoinCode(Clock)` mutator.
- [ ] `POST /api/v1/clubs/{clubId}/join-code/rotate` endpoint + tenant gate + audit.
- [ ] `ClubResponse.joinCode` shown to CLUB_ADMINISTRATOR only — controller-side gate (look at `users-suite`'s S-168 role-gated DTO pattern).
- [ ] Admin UI: panel on Club edit page + e2e spec.

## Open design questions (for refine)

- **Initial code on existing rows.** Backfill uses a per-row generator. Confirm that the chosen alphabet's collision-retry stays well-behaved at 12-club + future-scale row counts; should be a non-issue at 40-bit entropy.
- **Wire-shape of admin-only `joinCode`.** Two options: (a) field is `null` on the wire for non-admins; (b) two separate DTOs (`ClubResponse` + `ClubAdminResponse`). Today's codebase already gates fields conditionally (per S-052/S-168 invitePending precedent). Refine picks the cleaner shape.
- **Future "disable codes" toggle.** Vision allows a CLUB_ADMIN to make their club join-by-invite-only (no public code). Not in scope here; surface a `Disable code` button as a follow-up after the join-request flow has soaked.
