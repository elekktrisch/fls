---
id: S-145
title: Subscription billing integration — checkout + customer portal + webhook → Deployment lifecycle (ADR 0021)
epic: E-15
status: todo
depends_on: [S-137, S-138]
acceptance:
  - `POST /api/v1/billing/checkout-session` (authenticated; tenant-scoped to a Club whose Deployment is in state `trial` or `cancelled`) creates a provider-hosted checkout session and returns the redirect URL. Per ADR 0021, default provider is Stripe (operator may swap).
  - `Deployment.billing_customer_id` and `Deployment.billing_subscription_id` columns store the provider IDs. Card data and other sensitive billing data never touch AlpenFlight (vision C33).
  - `POST /api/v1/billing/webhook` (unauthenticated; signature-verified) accepts events:
    - `subscription.activated` → `Deployment.lifecycle_state: trial|cancelled → active`; suppress trial-expiry (idempotent — no-op if already `active`).
    - `subscription.payment_failed` → `active → past_due`.
    - `subscription.cancelled` → `active|past_due → cancelled`; do NOT immediately delete (grace window — refine).
    - `subscription.reactivated` → `cancelled → active`.
  - State transitions go through S-137's state machine; illegal transitions log + return 200 (avoid provider retry-loop) + emit an alert.
  - `POST /api/v1/billing/portal-session` (authenticated) creates a provider-hosted customer-portal session (cancel, update payment method, view invoices) and returns the redirect URL.
  - SPA renders `/account/subscribe` (checkout entry) and `/account/billing` (portal entry) inside the authenticated dashboard.
  - Funnel-telemetry: `subscription.checkout_started`, `subscription.activated`, `subscription.cancelled`, `subscription.payment_failed`.
  - Provider API keys live in env; webhook signing secret documented in the operator runbook.
estimate: L
adr_refs: [0008, 0018, 0021]
parity_test: tests/billing/subscription-lifecycle.spec.ts (new — uses the provider's test mode)
---

## Context
Vision C33 keeps PCI scope outside AlpenFlight. ADR 0021 picks the provider (Stripe default). One Deployment = one subscription; the operator pays once for an N-Club Deployment.

State machine transitions are owned by S-137; this story owns the integration — outbound checkout/portal session creation + inbound webhook handling that drives transitions.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] ADR 0021 must land before this story.
- [ ] Add `billing_customer_id`, `billing_subscription_id` columns to `Deployment` (Flyway).
- [ ] Provider SDK wiring (assuming Stripe: `stripe-java`).
- [ ] Checkout-session creation endpoint.
- [ ] Webhook receiver + signature verification + event dispatcher.
- [ ] Customer-portal-session endpoint.
- [ ] SPA `/account/subscribe` + `/account/billing` pages.

## Notes
- Test-mode credentials in CI; production credentials only in the production env file. Webhook URL in dev points at a tunnel (Stripe CLI's `stripe listen`).
- Per memory `[[feedback-re-runnable-over-frozen-docs]]`: parity test drives the provider's test mode end-to-end, not a frozen webhook fixture (though a fallback fixture is acceptable for offline CI).
- Paddle is worth comparing in ADR 0021 because it handles EU VAT-MOSS automatically — relevant for a CH/EU customer base.
