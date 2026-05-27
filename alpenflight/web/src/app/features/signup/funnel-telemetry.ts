// Placeholder funnel-telemetry emitter (S-147 ships the real one). Kept thin
// so S-147 can substitute a structured-emit pipeline without touching call
// sites; consumers go through `emitFunnelEvent(...)`.
//
// PII discipline (S-147 contract, S-134 security plan): payload carries
// opaque `actor_id` (Keycloak sub UUID) + the categorical fields the funnel
// needs. NO email, NO given/family name, NO raw IP.

export interface FunnelEvent {
  event_id: string;
  // Keycloak `sub` (UUID). Absent when the event fires before tokens settle.
  actor_id?: string;
  timestamp: string;
  // Categorical context the funnel queries group by. Free-form on purpose;
  // each event's properties are documented in alpenflight/docs/funnel-events.md
  // (S-147 deliverable).
  properties: Record<string, string>;
}

export function emitFunnelEvent(event: FunnelEvent): void {
  // S-147 swaps console.info for the structured-logging pipeline. Until then,
  // dev-loop visibility + a grep target for the Playwright PII assertion in
  // alpenflight/web/e2e/tests/public/signup.spec.ts (which gates on the
  // "[funnel]" prefix + `console.info` message type — swapping either breaks
  // that test, intentionally).
  console.info('[funnel]', JSON.stringify(event));
}
