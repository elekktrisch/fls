---
id: S-176
title: Server-sent events channel for the authenticated principal (/api/v1/me/events)
epic: E-03
status: todo
rolled_up_into: J-3
depends_on: [S-020, S-021]
integration_base: integration/users-suite
acceptance:
  - `GET /api/v1/me/events` is an authenticated SSE stream scoped to the caller's KC sub. One connection per browser tab; multiple tabs each open their own. Bearer authentication; no anonymous access.
  - The channel emits typed events as `event: <kind>` lines with a JSON `data:` payload. First consumer is the join-request slice (S-178) emitting `join-request.status-changed`. Schema is extensible — future stories (in-app inbox, push notifications, reservation alerts) add new event kinds without breaking subscribers.
  - Heartbeat comment line every 25s to keep idle connections alive through proxies. Client reconnect handled by the browser's default EventSource backoff; the server tolerates rapid reconnects (no per-sub connection cap below 8).
  - Server-side event publishing API on the backend: `MePrincipalEventBus.publish(kcSub, kind, payload)` is the single fan-out point. Application services call it; the bus dispatches to any active SSE connections for that sub. In-memory only — no event replay across server restart.
  - SPA: an `Angular` service `MeEventsService` opens the stream after the OIDC client emits an authenticated session, subscribes via RxJS subjects per kind, and reconnects on transient errors.
  - Authentication for SSE: the SPA sends the Bearer token via the standard `Authorization` header through a small fetch-polyfill EventSource wrapper (native `EventSource` cannot set headers). Pin the polyfill choice during refine.
  - No persistence: events that fire while no connection is open are lost. The join-request slice (S-178) and any future consumer must also expose a `GET …/status` read for state-on-load; SSE is the change-notification overlay, not the source of truth.
  - Audit: connection open/close logs go through structured logging (S-031); no per-event audit row.
estimate: M
adr_refs: [0007, 0017]
---

## Context

Q16 grilling outcome: the join-request slice (S-178) needs the pilot's SPA to learn *immediately* when admin approves their request — without polling or forced re-login. SSE was chosen over polling and over full re-auth on approval. The transport is foundational and explicitly reusable: Vision O6 ("in-app inbox"), the freemium gate-hit prompts (C30), and reservation cancellation alerts (O6) are all later consumers.

This story owns the **transport only**. The first event-kind (`join-request.status-changed`) is published by S-178; the in-app inbox UX (a richer surface that persists events to a backing store) is a future story.

## Cross-story contracts

- **Consumes:** S-020 resource-server Bearer auth; S-021 Angular OIDC client (the SPA service opens the stream after the OIDC client signals authenticated).
- **Produces:** `MePrincipalEventBus` (server) + `MeEventsService` (SPA). First consumer is S-178. Future consumers add their own event kinds without touching this story's surface.

## Open design questions (for refine)

- **EventSource polyfill choice.** Native EventSource cannot send `Authorization` headers. Refine pins one of: (a) `event-source-polyfill` npm; (b) a custom fetch-stream reader; (c) a one-shot signed query-param token (`?ticket=…`) issued by `/api/v1/me/events/ticket` that the SSE endpoint validates and discards. Operator preference TBD.
- **Per-sub connection cap.** Default budget: 8 concurrent connections per KC sub. Multi-tab + multi-device must work; aggressive caps cause silent drops. Refine confirms the cap and the over-cap response (close oldest vs. reject newest).
- **Heartbeat interval.** 25s is a typical proxy-tolerant value; refine confirms against the reverse-proxy chosen in S-041 (which is also on this branch's roadmap).

## Tasks

- [ ] `MePrincipalEventBus` interface + in-memory `Sinks.Many`-backed impl (reactor-core).
- [ ] `MeEventsController` exposing `GET /api/v1/me/events` returning a `Flux<ServerSentEvent<…>>`.
- [ ] Bearer authn on the SSE endpoint — confirm Spring Security 7 + WebFlux interop is wired (project is Servlet-stack; pick one of: bridge via async dispatch, or pull WebFlux for this endpoint only).
- [ ] SPA `MeEventsService` + polyfill choice.
- [ ] Integration test: open SSE, publish an event via the bus, assert client receives it; assert idle heartbeats arrive on a fixture timer.

## Notes

The SSE channel is bigger than what S-178 strictly needs. The intent is to land the transport once so subsequent in-product notification stories can subscribe without re-introducing a separate channel. Keep the scope tight on *this* story: transport + first-class event publishing API. The richer in-app inbox UX, persistence, and read-receipts come later.
