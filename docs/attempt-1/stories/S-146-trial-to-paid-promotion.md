---
id: S-146
title: Trial-to-paid promotion — subscription activation suppresses auto-delete
epic: E-15
status: todo
depends_on: [S-137, S-142, S-145]
acceptance:
  - When the `subscription.activated` webhook (S-145) flips a `trial` Deployment to `active`, the trial countdown ceases (covered by the lifecycle-state filter on `TrialExpiryJob`, S-142). No explicit "cancel scheduled job" call is required.
  - The dashboard banner transitions from "Trial expires in X" to a one-time "Welcome to AlpenFlight! Your subscription is active." confirmation that auto-dismisses after 24 h. The banner is shown on every Club in the activated Deployment.
  - All data uploaded during trial is preserved unchanged — promotion is a state flip on the Deployment, not a re-ingest.
  - If a `cancelled` Deployment subscribes (reactivation), the same path runs: state → `active`; any pending `deleting` cron does not fire. (`cancelled` Deployments are not enumerated by `TrialExpiryJob`; reactivation timing vs. grace window — refine.)
  - Edge case: subscription webhook arrives *after* the 72 h cron has fired and the Deployment is `deleting` or already hard-deleted. Webhook returns 200; activation attempt logs an alert + emits `subscription.activated_post_delete` for monitoring; operator runbook documents manual restore-from-recent-backup recovery.
  - Funnel-telemetry: `trial.converted_to_paid` (with `time_to_convert_h` for analytics).
estimate: S
adr_refs: [0018, 0021]
parity_test: tests/billing/trial-promotion.spec.ts (new)
---

## Context
The mechanical part of trial→paid is a Deployment state transition that S-137's lifecycle filter (on `TrialExpiryJob`) handles automatically. This story focuses on the *UX* of promotion (banner change, welcome confirmation) and the *edge cases* (post-delete subscription, cancelled-reactivation timing).

The post-delete race is the trickiest: if the user clicks "Subscribe" at 71h59m and the provider doesn't activate until 72h05m (after cron fired), the Deployment is gone. Mitigation calibrated to the ±15 min auto-delete precision NFR + typical provider activation latency (seconds): race is rare but possible. Runbook documents manual recovery from recent backup.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Banner state transitions in the SPA.
- [ ] Welcome-confirmation component (auto-dismiss 24 h).
- [ ] Post-delete-race detection + alert hook + operator runbook entry.
- [ ] Funnel-telemetry events.

## Notes
- A safer alternative for the post-delete race: delay the actual delete by an extra buffer hour past the 72 h mark, marked `deleting` state in between. Refine in S-137 or here. Operator's call on precise window.
- "Welcome to AlpenFlight" copy is the operator's call; placeholder for now.
