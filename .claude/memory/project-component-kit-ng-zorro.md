---
name: project-component-kit-ng-zorro
description: "S-008 chose ng-zorro-antd as primary component primitives source, paired with Tailwind v4 tokens. From-to datepicker + autocomplete-with-dropdown were the two organisms that decided the fork."
metadata: 
  node_type: memory
  type: project
  originSessionId: 60c6c053-e3a6-4f91-ac7c-5232fd92d23a
---

S-008 (component primitives kit) settled the fork on **ng-zorro-antd as the primary component-library source** for `next/web/src/app/shared/ui/`. Decision date: 2026-05-17. Operator quote: "I like the default-look of ng-zorro, and it has both a from-to datepicker and a autocomplete input with dropdown (the two most important components for our flights form)."

**Why:** the flights form drives the most expensive UX in the app. ng-zorro's `nz-range-picker` (from-to date) and `nz-select`/`nz-auto-complete` (dropdown autocomplete) cover those two organisms out of the box. Building those on Angular CDK + Tailwind would have been the bulk of the kit's effort. ADR 0004 didn't name ng-zorro as an option; this story is the addendum.

**Version compatibility (resolved 2026-05-17 via https://ng.ant.design/docs/introduce/en):** ng-zorro-antd **21.2.2** tracks Angular `^21.0.0` directly. Zoneless + OnPush officially supported. Context7's v19.3.1 listing is stale; the upstream now ships in lockstep with Angular's major. Q1 from S-008 refine open questions: closed.

**How to apply:**
- Primitives under `next/web/src/app/shared/ui/{atoms,molecules,organisms}/` wrap ng-zorro components with `af-*` selectors. Wrappers are thin — they exist to enforce the AC-DIR-1..AC-DIR-11 directives (breakpoints, density modes, touch targets, card-mode data-table, recency-bias autocomplete, native input types for time/date in form fields, etc.).
- **Theming:** Tailwind v4 `@theme { ... }` in `src/styles.css` is the single source of brand tokens. ng-zorro's `--ant-*` CSS variables are derived from Tailwind tokens in `styles.css` — Tailwind tokens authoritative, ng-zorro vars consume. Do not edit ng-zorro Less variables (avoids the `@angular-builders/custom-webpack` detour).
- **Density (AC-DIR-2):** `<af-density-provider>` directive sets `data-density="comfortable|dense"` on the host AND exposes a signal that wrappers read to pick `nzSize="default"|"small"` on ng-zorro hosts.
- **i18n:** two stacks — ng-zorro `NzI18nService.setLocale(de_DE|fr_FR|it_IT|en_US)` for ng-zorro UI strings; transloco/@angular/localize (S-005) for AlpenFlight domain strings. A single `LocaleService` wrapper switches both in lockstep.
- **Bundle:** per-component standalone imports only (`NzButtonModule` not `NgZorroAntdModule`). The Vision §F12 marginal-3G budget is the gate; capture the ng-zorro tax in S-008's perf plan.
- **Native input opt-out for time/date in forms (AC-DIR-9):** `<af-input type="time"|"date">` passes through to native `<input type="time"|"date">` inside `<af-form-field>` — does NOT wrap `<nz-time-picker>`/`<nz-date-picker>`. Only the **from-to range picker** uses `<nz-range-picker>`.
- **Selector prefix:** `af-` (post-rebrand). The S-008 story body still uses `fls-*` in ACs — needs an AC update (separate concern from this memory).
