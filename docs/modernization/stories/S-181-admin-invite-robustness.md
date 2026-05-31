---
id: S-181
title: Admin invite robustness — recognise pre-existing KC user (federated or local)
epic: E-06
status: todo
depends_on: [S-052, S-168]
integration_base: integration/users-suite
acceptance:
  - `UsersService.invite` (S-052) gains a pre-check via the KC admin REST `users?email=` lookup before the create call:
    - **No KC user with that email** → today's path: create KC user via `UserDirectoryPort.createUser` + `t_user` row + UPDATE_PASSWORD required action + invite email. Unchanged.
    - **KC user exists, no `clubId` attribute (unattached)** → bind the existing KC user to the inviting tenant. Set the `clubId` user-attribute via admin REST → create the `t_user` row with the existing KC sub → grant requested roles → skip the password-reset email (the user already has whatever credential they signed up with — local or Google). Send a *welcome-attached* email instead (new template under S-082's layout): "An admin at {clubName} has added you to the club. Sign in to AlpenFlight to get started." Localised per the KC user's `locale` attribute.
    - **KC user exists with a `clubId` attribute (attached to another club)** → 409 with the existing one-sub-one-club guard. Error message includes "this email is already attached to another club — they need to leave that club first, OR you can share your join code instead." Refine pins whether the existing club's name is exposed in the error (cross-tenant leak concern vs. UX clarity).
  - The KC admin REST call uses the same `alpenflight-backend-admin` machine client S-052 already wires; no new client.
  - Existing `UsersService.invite` 409 on `findActiveByUsernameLower` collision (local-row collision in caller's tenant) remains. The new branch handles only the *cross-tenant* / *unattached-KC-user* cases.
  - `t_user` row created on the "unattached" branch has the same shape as a regular invite: `clubId` = caller tenant, `keycloakSub` = existing KC user's id, `username` from request, `friendlyName` / `notificationEmail` / `languageId` from request, optional `personId`.
  - Audit blob differentiates the branch: `user.invited` event carries `{ branch: "new_kc_user" | "attached_existing" }`. Refine confirms whether to split into two audit kinds.
  - S-168 admin invite modal stays as-is on the wire; the new behaviour is server-side only. Error surfaces handled by the existing toast / form-error path.
  - Tests: new IT `UsersInviteRobustnessIT` covering the three branches; SPA spec `users-invite.spec.ts` (S-168's existing file) extended with the unattached-existing-KC-user case (signup via Google fixture → admin invites → row appears + KC clubId attribute set + no password-reset email sent + welcome-attached email asserted via Mailpit).
estimate: S
adr_refs: [0007, 0018]
---

## Context

Q2 + Q9 grilling outcomes: with one-sub-one-club as the rule and user-initiated join (S-178) as the dominant new path, the admin-push invite still needs *robustness* against the case where the invitee already has a federated KC identity. Today's invite blows up with a KC duplicate-username error at the create-user call. After this story, admin invites just-work whether the invitee has signed up yet or not.

The (c) flavor of Q3 ("no way to invite specifically via Google") is intentionally NOT addressed here. The user-initiated join flow (S-178/S-179) covers Google-using pilots: admin shares the join code, pilot signs up via Google, admin approves. Admin-push invite stays as a password-flow primary with the cross-tenant robustness fix as its only enhancement.

## Cross-story contracts

- **Consumes:** S-052 `UsersService.invite` + `UserDirectoryPort`; S-168 admin invite modal; S-082 email base (new "welcome-attached" template).
- **Produces:** Closes the (a) Google-signed-up-first / admin-invites-collides dead-end. Closes the (d) admin-invite-never-lands dead-end via the attribute-write fallback. Does NOT introduce new UI affordances.

## Open design questions (for refine)

- **Cross-tenant club-name disclosure in 409.** Acceptance 1's third branch returns a 409 when the invitee is already attached. Refine picks: (i) include the other club's name + the user's email confirmation (clearer UX, minor cross-tenant disclosure); (ii) generic "this email is in use elsewhere" message; (iii) audit-log only, generic error to caller. Operator preference TBD; default to (ii) for safety, log the specifics audit-side.
- **`detachKeycloakSub` interaction.** A soft-deleted-then-re-invited user (S-169 / S-052 partial UNIQUE detach path) is a *third* race: KC user exists, no `t_user` for it in caller's tenant, AND a tombstone in caller's tenant. Confirm the existing detach logic still works on this path; refine pins the order of checks.
- **Welcome-attached email template.** New template; localised; lands under `templates/email/users/welcome-attached.html`. Refine confirms the canonical wording.
