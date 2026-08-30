---
id: E-01
title: Foundations & project scaffolding
status: todo
adr_refs: [0001, 0004, 0005, 0006, 0023, 0024]
---

## Goal
Stand up the `alpenflight/server/` (Spring Boot 4.x / Java 25) and `alpenflight/web/` (Angular 21 / Tailwind / TypeScript) project skeletons with the cross-cutting conventions wired up — build tooling, null-safety, OpenAPI publication, TS codegen, i18n, NgRx Signal Store reference, Reactive Forms patterns, component-primitives kit. Once this epic is done, every subsequent feature story is "write a new domain on the template" rather than "decide how the template should look."

## Scope
- In: project skeletons; one-touch dev startup for both apps; codegen pipeline; reference patterns for state, forms, components, i18n.
- Out: any business feature; auth (E-03); observability (E-04); deployment (E-05); CI workflows beyond a basic build+test pipeline.

## Stories
- [ ] S-001 — Scaffold `alpenflight/server/` Spring Boot skeleton
- [ ] S-002 — Scaffold `alpenflight/web/` Angular skeleton
- [ ] S-003 — Wire springdoc-openapi + publish OpenAPI spec
- [ ] S-004 — Pick + wire TypeScript API client codegen
- [ ] S-005 — Pick + wire i18n library + bundled JSON shape
- [ ] S-006 — Reference NgRx Signal Store + session store skeleton
- [ ] S-007 — Reactive Forms convention + typed form helpers
- [ ] S-008 — Component primitives kit + Tailwind design tokens
- [ ] S-155 — Module layering template — Spring Modulith + ArchUnit + Clubs reshape (ADR 0023)
- [ ] S-156 — Install lucide-angular + wire `<af-icon>` atom (ADR 0024)
- [ ] S-157 — Wordmark v1 SVG assets (ADR 0024)

## Done when
- A new contributor can `docker compose up && ./gradlew bootRun` (or equivalent) and `ng serve`, hit a sample REST endpoint authenticated against the local IdP, see it in the generated TS client, and render a form against it using the reference store and the UI kit, in under 15 minutes of setup time.
- A second domain ("Hello") can be added end-to-end in <2 hours by copying the reference patterns.
