---
id: E-15
title: Self-service migration & freemium SaaS
status: todo
adr_refs: [0007, 0008, 0018, 0019, 0020, 0021]
---

## Goal
Make AlpenFlight a multi-tenant SaaS that any legacy FLS deployment can self-onboard onto. The end-to-end flow: legacy admin lands on `alpenflight.ch` → signs up via Keycloak (Google IdP federation or email/password) → plays around in a shared sandbox demo Deployment that resets nightly → downloads a single-file Java JAR → runs it against their legacy SQL Server with a per-upload public-key fingerprint → uploads the resulting encrypted bundle → ingest provisions their own trial **Deployment** containing 1..N **Clubs** from the bundle → 72 h banner counts down → entire Deployment is hard-deleted unless they subscribe (per-Deployment flat-tier freemium: free = 1 Club, 2 aircraft, 5 active users across the whole Deployment; paid = unlimited, billing via a third-party provider — Stripe is the recommended default).

This epic operationalizes vision-doc amendment **2026-05-17c** (constraints C25–C34, outcome O8). The Deployment entity (C34) wraps 1..N Clubs: Club remains the `@TenantId` carrier (per ADR 0008) so cross-Club isolation is preserved; Deployment is the lifecycle / billing / freemium / trial-handle wrapper. The operator's own clubs onboard via the same path as every other customer (operator-owned Deployments get flipped manually to `active` via the S-028 / S-137 admin endpoint after ingest).

## Scope
- **In:** marketing landing CTA copy + nav; self-service Keycloak signup with Google IdP federation; sandbox demo tenant + seed + nightly reset cron + anonymous-session scoping; trial-tenant runtime provisioning on first ingest; legacy FLS export Java JAR (build + CLI + DB read + bundle write + AES+RSA hybrid encrypt); per-upload public-key handshake endpoint + UI; encrypted-bundle upload + streaming decrypt + ingest pipeline; trial-countdown UX + 72 h hard-delete cron; tenant lifecycle state machine (`sandbox / trial / active / past_due / cancelled / deleting`); freemium feature-gate annotation + server-side enforcement + UI upgrade prompt; subscription billing integration (provider per ADR 0021) — checkout + customer portal + webhook → lifecycle-state transitions; trial-to-paid promotion (suppress auto-delete, preserve data); funnel telemetry events end-to-end.
- **Out:** the schema-mapping logic itself (lives in S-016, shared library `next/migration-bundle/`). VAT / invoicing / accounting beyond the third-party provider's defaults (operator can iterate later). Per-tenant custom contracts, enterprise plans, volume discounts (future). Anonymous demo writes that should *persist* (deliberately ephemeral — see C27). Mobile-app distribution of the JAR (CLI only).

## Stories
- [ ] S-133 — Public marketing landing CTA + "Migrate from legacy FLS" + "Try demo" entry points
- [ ] S-134 — Keycloak self-service signup + Google IdP federation
- [ ] S-135 — Sandbox demo Deployment: seed data + nightly-reset cron
- [ ] S-136 — Anonymous demo-session scoping (signed cookie → sandbox Deployment context)
- [ ] S-137 — Deployment entity + lifecycle state machine + job filter (ADR 0018)
- [ ] S-138 — Trial-Deployment provisioning on first successful ingest
- [ ] S-139 — Legacy export JAR: build + CLI + JDBC read + bundle writer + hybrid encrypt (ADR 0019)
- [ ] S-140 — Per-upload keypair handshake + public-key surface
- [ ] S-141 — Encrypted-bundle upload endpoint + streaming decrypt + ingest pipeline
- [ ] S-142 — Trial countdown UX + 72 h hard-delete cron
- [ ] S-143 — Freemium feature-gate annotation + server-side enforcement + 402 contract (ADR 0020)
- [ ] S-144 — Freemium UI upgrade-prompt component + interceptor
- [ ] S-145 — Subscription billing integration: checkout + customer portal + webhook → lifecycle transitions (ADR 0021)
- [ ] S-146 — Trial-to-paid promotion: subscription-activated suppresses auto-delete + flips state
- [ ] S-147 — Funnel telemetry events end-to-end

## Done when
- A legacy FLS admin with no prior contact with the operator can complete the full path from landing-page-view to "I see my own data in AlpenFlight, with a 72 h countdown" without any operator intervention, in ≤ 30 minutes for a typical legacy DB. Funnel telemetry shows each step.
- A trial Deployment that does not subscribe within 72 h is hard-deleted within ±15 minutes of the deadline, cascading through every Club and every tenant-scoped row; the audit log retains the deletion event; the Deployment's Keycloak group is deprovisioned.
- A free-tier Deployment whose Clubs attempt a gated endpoint receives 402 with `{ code: PLAN_GATE, required_plan, upgrade_url }`; the SPA renders an upgrade prompt; subscribing flips the Deployment to `active` and the same call succeeds.
- The sandbox Deployment resets nightly within 5 minutes; no real-Deployment data is ever touched by the reset job; sandbox writes never leak into trial / active Deployments.
- Operator-owned Deployments onboard via the same JAR + upload path; the admin flips them from `trial` to `active` via S-028 / S-137's admin endpoint post-ingest.
- The bundle format + encryption protocol are fixed via ADR 0019; the parity oracle (S-016) re-exports from a seeded legacy DB and round-trips through the upload pipeline in CI.

## Open items
- **ADRs to land before story refinement:** ADR 0018 (tenant lifecycle), ADR 0019 (bundle format + encryption), ADR 0020 (feature-gate mechanism), ADR 0021 (billing provider). Recommended sequence: 0018 → 0019 → 0020 → 0021. Each unblocks ~3–4 of the stories above.
- **Trial extension policy.** Vision C29 says "non-negotiable absent subscription". Edge case: the operator's friends-and-family / debug accounts. Out-of-band extension is allowed by the operator manually flipping `lifecycle_state` — no UI knob. Confirm during S-137 refine.
- **GDPR data-subject delete during trial.** A trial user requests deletion before 72 h: same delete path, fires immediately, supersedes the countdown. Confirm during S-142 refine.
