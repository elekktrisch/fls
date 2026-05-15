# 0016 — Push notification provider

- **Status:** Accepted
- **Date:** 2026-05-15
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  4. Swiss / EU data residency compatible
  7. Solo-operator operability
  10. Lower TCO
  11. Mature ecosystem for our integration points
  12. Supports the C17 end-user improvements within chosen stack

## Context

The [2026-05-15 vision amendment](../02-vision-and-constraints.md) adds push notifications as a delivery channel alongside email (C20: email remains primary) and an in-app inbox. The NFR is best-effort delivery — no SLA — and Swiss/EU residency (C4) applies to whatever delivery mechanism we pick. Push is the channel that reaches a user who has the PWA installed but isn't currently in the app, which is the gap email + in-app inbox don't fill (push is the "your delivery is ready" tap on a phone home screen).

The market has consolidated around three patterns: the W3C Web Push standard with VAPID keys (browser-native, encrypted-payload-only at the push service), Google's Firebase Cloud Messaging (cross-platform SDK, Google-managed), and third-party push SaaS (OneSignal, Pusher, Pushwoosh — managed dashboards, paid). Picking the wrong one bakes a third-party dependency or a vendor lock-in into the client-side service worker that's expensive to undo.

## Options considered

### Option A — Web Push (W3C VAPID standard)
- **Capabilities:** Browser-native push standard. Server generates a VAPID key pair (one-time setup); client subscribes via service worker (`pushManager.subscribe({ applicationServerKey: vapidPublicKey })`); server signs push messages with the VAPID private key and POSTs to a browser-vendor-operated push service (Mozilla autopush, Google FCM, Apple Push). The push service sees only the **encrypted payload** — VAPID encrypts end-to-end between server and the user's browser. Spring Boot integration via `webpush-java` or `nl.martijndwars:web-push` libraries.
- **Fit to criteria:** criterion 4 ✓ — payload encryption preserves Swiss residency intent: intermediate push services see an opaque blob, not message contents; metadata (subscription URL) is unavoidable but unrelated to PII. Criterion 7 ✓ — no third-party SaaS account; one Spring dependency. Criterion 10 ✓ — zero ongoing cost; push services are free for senders. Criterion 11 ✓ — W3C standard, supported by Chrome / Edge / Firefox / Safari (16.4+ macOS and iOS). Criterion 12 ✓ — pairs naturally with the ADR 0015 service worker.
- **Migration cost:** medium. Service worker push handler (the SW from ADR 0015 grows a `push` event listener), subscription management endpoint, VAPID key generation + storage in the server config, retry-on-410-subscription-gone cleanup.
- **Ecosystem risk:** low. W3C standard with multi-browser support; the underlying push services are all major browser vendors.
- **Escape hatch:** if FCM-only features become useful later (cross-app token migration, etc.), add FCM as a second channel — VAPID and FCM coexist on the same SW.

### Option B — Firebase Cloud Messaging (FCM)
- **Capabilities:** Google-managed delivery. Single SDK across iOS/Android/web; subscription tokens; topic-based messaging; analytics dashboard. Generous free tier (unlimited push for now).
- **Fit to criteria:** criterion 4 ~ — Google offers EU regions for FCM data storage, but the FCM transport metadata routes through Google infrastructure regardless of region. FADP/GDPR compliance requires a Data Processing Addendum with Google; achievable but adds a contract. Criterion 7 ✗ — Google account dependency, Firebase console as a separate ops surface. Criterion 10 ✓ — free tier ample for FLS volume. Criterion 11 ✓ — well-supported. Criterion 12 ✓ — works with the SW.
- **Migration cost:** medium — comparable to A. FCM SDK on client; Firebase Admin SDK on server.
- **Ecosystem risk:** medium — Google could change FCM terms (history of deprecations: GCM → FCM in 2018; FCM API HTTP v1 in 2024). Migrating off FCM later is doable but a project.
- **Escape hatch:** strip out the FCM SDK, replace with Web Push. FCM uses Web Push under the hood for browsers, so the data model is compatible.

### Option C — Third-party SaaS (OneSignal, Pusher, Pushwoosh)
- **Capabilities:** managed dashboards, audience segmentation, A/B testing, scheduling, multi-channel orchestration (push + email + SMS in one console).
- **Fit to criteria:** criterion 4 ✗ — most are US-hosted; data residency requires enterprise plans + DPAs and routing concerns. Criterion 7 ✗ — another vendor relationship, dashboard, billing. Criterion 10 ✗ — free tiers exist but cap at unrealistic volumes; FLS-scale would be in the paid tier. Criterion 11 ✓ — mature, but the maturity benefits are aimed at marketing teams (segmentation, A/B) that FLS doesn't have. Criterion 12 — works, but overkill.
- **Migration cost:** medium.
- **Ecosystem risk:** medium-high — vendor relationships are not low-risk for a solo operator.
- **Escape hatch:** migrate to A or B; the subscription tokens are not portable but resubscribing users is acceptable.

## Decision

Chosen: **Option A — Web Push (VAPID)**. Driven primarily by criterion 7 (no third-party SaaS dependency aligns with solo-operator operability), criterion 4 (end-to-end encryption preserves Swiss residency intent — push services see only opaque payloads), criterion 10 (zero ongoing cost), and criterion 12 (pairs cleanly with the ADR 0015 service worker, which already exists for offline writes). FCM and the third-party SaaS options offer features (segmentation, A/B, cross-app token migration) that don't justify the dependency cost for a 12-club glider-operations app.

VAPID keys are generated once and stored in server config (treated as secrets per the existing secrets-management pattern). Subscriptions live in a per-user table keyed by `user_id` + browser fingerprint; on 410 Gone from the push service (subscription expired), the server cleans up the row. Push payloads are kept small (< 4 KB per the Web Push spec) and contain only an event identifier — the SW then fetches details via the regular auth'd API when the user taps the notification.

## Consequences

- **Positive:**
  - Zero ongoing cost; no per-message fee; no third-party billing relationship.
  - Privacy posture aligns with FADP: push payloads are encrypted end-to-end; the intermediate push services never see notification contents.
  - Pairs natively with the ADR 0015 service worker — one SW, two event handlers (`sync` for offline writes, `push` for notifications).
  - Mature ecosystem on the JVM side: `nl.martijndwars:web-push` is the standard Spring-compatible library.
  - Works on Chrome / Edge / Firefox / Safari 16.4+ (macOS and iOS). Coverage is enough for FLS's user base (glider clubs in 2026 with smartphones).

- **Negative:**
  - No segmentation / analytics dashboard out of the box. Mitigation: in-app inbox provides delivery audit; per-user push opt-out lives in user prefs; for ad-hoc analytics, the observability stack ([ADR 0011](0011-observability.md)) can chart `push.sent` / `push.delivered-410` / `push.opt-out` events.
  - VAPID key rotation is a manual procedure (and rotation invalidates all existing subscriptions). Mitigation: document a runbook; rotation should be rare.
  - iOS Safari requires the PWA to be installed to the home screen for push to work (a known iOS limitation). Acceptable for the airfield use case where users install the app anyway.
  - 410 cleanup is the operator's responsibility (no managed service to clean expired subscriptions). Mitigation: on every push send, capture 410 responses and delete the subscription row in the same transaction — covered by the standard `webpush-java` retry pattern.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** VAPID key generation + secrets-config + admin runbook for key rotation.
  - **Story:** Push subscription entity (`PushSubscription` keyed by user + endpoint) + register/deregister API endpoints (`POST /api/v1/me/push-subscriptions`, `DELETE /api/v1/me/push-subscriptions/{id}`).
  - **Story:** Service worker `push` event handler + notification rendering (extends the SW from ADR 0015).
  - **Story:** Server-side push dispatcher service — receives a `NotificationEvent` (e.g. `delivery.ready`, `reservation.cancelled-waitlist`); fans out to email ([ADR 0013](0013-email-infrastructure.md)) + in-app inbox + push subscribers per user preference; logs delivery outcomes for the observability dashboard.
  - **Story:** Per-user notification preferences UI (email / push / in-app inbox, per event type — leveraging the legacy `PersonClub.NotificationXxx` flags).
  - **Story:** 410-Gone cleanup integrated into the dispatcher (no separate cron job needed).
  - **NFR call-out:** "best-effort, no SLA" per vision amendment — Web Push has no delivery guarantee, which aligns. Monitoring is via `push.sent` count vs. `push.delivered-410` opt-out rate; alert only on catastrophic deviation.
  - **No new ADR** — push channel completeness (in-app inbox, email coexistence, per-user preferences) is story-level.
