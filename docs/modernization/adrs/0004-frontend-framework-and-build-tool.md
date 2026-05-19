# 0004 — Frontend framework + build tool

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): off-EOL · team-familiar stack · solo-operator operability · enables fast feature dev post-cutover · mature ecosystem · enables type-sharing between FE and BE (soft preference)

> **Note on revision:** an initial draft of this ADR accepted React 19 + Vite + TypeScript on the strength of community size. During elicitation the operator clarified that the developer is specifically comfortable with **modern Angular (signal-based, Angular 21)** and **TailwindCSS** — making "team-familiar stack" (criterion 2) the dominant signal. The decision was changed accordingly. The rejected-options section preserves the React analysis so the trade-off is visible later.

## Context

The current frontend is AngularJS 1.4 + Webpack 1 + Babel `preset-es2015`, pinned to a Node 8-era toolchain ([current-state §6](../01-current-state.md#client)). Every component of that stack is end-of-life. The replacement must be modern (criterion 1), maintainable by a solo developer comfortable with the chosen stack (criterion 2 + 7), and ideally enable a single source of truth for DTOs / enums across server and client (soft preference; directly addresses [R5](../01-current-state.md#r5--flightstatemapper-enum-duplication) — the `FlightStateMapper` enum-drift bug).

The "AngularJS 1.4" name on the current stack is a trap: modern Angular (16+, and especially the signal-based 17–21 line) is a ground-up rewrite. It shares neither code shape nor mental model with AngularJS — the framework name is the only carryover. With the dev's stated familiarity sitting specifically on the modern Angular line (signals, control flow `@if`/`@for`, standalone components, `inject()` DI, signal-based resources), the "AngularJS heritage" objection collapses.

## Options considered

### Option A — Angular 21 (latest, signal-based) + TailwindCSS + TypeScript
- **Capabilities:** Standalone components (no NgModules required), signals + computed/effect, the resource()/rxResource() APIs (introduced in Angular 19, stabilized through 21) for server-state fetching, control-flow syntax (`@if`, `@for`, `@switch`, `@defer`), built-in `HttpClient` + interceptors, Angular CLI for scaffolding and build, Angular Router with typed routes, ESBuild-based dev server fast enough to compete with Vite. TailwindCSS provides utility-first styling without a heavy component library opinion.
- **Fit to criteria:** off-EOL ✓ (Angular ships every 6 months with overlapping LTS windows; Angular 21 supported well past cutover). Team-familiar stack ✓ (operator-named; the *only* option that satisfies criterion 2). Solo-operator operability ✓ (one framework, one CLI, opinions about routing/forms/HTTP built in — fewer decisions for a solo dev). Fast feature dev ✓ (signals + standalone components remove the historical Angular ceremony). Mature ecosystem ✓ (Google-backed; long-standing community). Type-sharing ✓ — TypeScript end-to-end; OpenAPI codegen output is a normal TS file consumable by Angular services.
- **Migration cost:** medium. AngularJS 1.x components/controllers do not port — they get rewritten. But the mental model is closest of any modern framework to "templates + components + DI", which lowers the conceptual distance vs. JSX-based options. Angular's idiomatic forms (Reactive Forms, with typed forms in modern versions) match what existing AngularJS forms try to do. `angular-translate` → `@angular/localize` or `transloco` consuming the bundled JSON files chosen in [C15](../02-vision-and-constraints.md#3-hard-constraints).
- **Ecosystem risk:** low. Angular is one of the four mainstream frontend frameworks and has Google backing. Library breadth (HTTP, forms, routing, animations) is in-framework; component-library ecosystem is smaller than React's but covers our needs (Angular Material, PrimeNG, Spartan UI — Tailwind + headless component primitives are the front-runner alongside Tailwind).
- **Escape hatch:** Angular components are the least portable of the modern frameworks — but TypeScript business logic in services is plain TS and ports anywhere. Migration to another framework would still be a rewrite, but that risk is equally present with any of the alternatives.

### Option B — React 19 + Vite + TypeScript (originally recommended; rejected on team-familiarity)
- **Capabilities:** function components + hooks, large component ecosystem (Radix, shadcn, MUI, Mantine), Vite dev server, fast iteration.
- **Why not:** dev's actual comfort is Angular, not React. Criterion 2 (team-familiar stack) overrides criterion 7's "biggest answer pool" consideration when the gap is "comfortable" vs. "would have to learn." The React community-size advantage doesn't pay off if every feature requires re-learning idioms.
- Escape hatch and ecosystem characteristics remain genuinely strong — this remains the obvious fallback if Angular tooling becomes intolerable.

### Option C — Vue 3 + Vite + TypeScript
- **Why not:** not in the dev's comfort zone (criterion 2 again). Migration to a framework the dev doesn't currently use is exactly what we're trying to avoid for the solo timeline.

### Option D — SvelteKit + TypeScript
- **Why not:** smallest community of the modern options, and (most importantly) not in the dev's comfort zone.

## Decision

Chosen: **Option A — Angular 21 (signal-based) + TailwindCSS + TypeScript**. Driven primarily by criterion 2 (team-familiar stack) — the solo developer is productive in modern Angular today, and forcing a framework switch on top of a backend rewrite multiplies risk on the timeline. Modern Angular's signal-based reactivity and standalone components eliminate the historical ceremony that gave "Angular" a bad reputation; the dev experience is competitive with React in 2026. TailwindCSS for styling — utility-first, framework-agnostic, no opinions to fight, mature in 2026.

## Consequences

- **Positive:**
  - Developer can be productive from day 1 without learning a new framework. Frees timeline budget for the backend rewrite (which is the harder problem) and the rules-engine port (which is the highest-risk story).
  - Signals + new resource()/rxResource() APIs give a fetching/caching story comparable to TanStack Query without an external dependency — see [ADR 0006](.).
  - In-framework HTTP, forms, routing, i18n, and DI mean fewer per-feature library decisions.
  - TailwindCSS gives a consistent design-system surface without committing to a heavy component library; can be paired later with a headless library (Spartan, Angular CDK) for primitives.
  - TypeScript end-to-end still gives the [R5](../01-current-state.md#r5--flightstatemapper-enum-duplication) fix via OpenAPI-generated TS clients.

- **Negative:**
  - Angular's component-library ecosystem is smaller than React's. Mitigation: Tailwind + Angular CDK + Spartan covers most needs; Angular Material is a fallback for richer widgets.
  - AI assistance / Stack Overflow density is lower for modern signal-based Angular than for React. Mitigation: official Angular docs in 2026 are excellent, and the framework's release cadence keeps documentation current.
  - Angular release cadence (every 6 months) creates a steady-state upgrade burden. Discipline required: bump versions promptly, do not let multi-major drift accumulate.
  - Build tooling is Angular CLI rather than Vite — fine, but the operator should not be surprised that `ng serve` differs from `vite` in dev-server behavior.

- **Follow-ups (other ADRs / stories implied):**
  - **ADR 0005** (API shape) — REST + OpenAPI + generated TypeScript client; codegen output drops into Angular services unchanged.
  - **ADR 0006** (State management / data fetching) — Angular's signal-based resource()/rxResource() vs. third-party TanStack Query for Angular vs. NgRx-signals.
  - **Story:** scaffold `alpenflight/web/` with Angular CLI (latest), TailwindCSS, ESLint config, Vitest or Jest for unit tests, Playwright for e2e.
  - **Story:** pick a component-primitive approach — TailwindCSS + Angular CDK (headless) is the front-runner; alternatives Spartan UI (shadcn-for-Angular), Angular Material, PrimeNG. Phase-4 task.
  - **Story:** pick a forms approach — Reactive Forms (typed) is the Angular-idiomatic choice and matches the dev's expected baseline.
  - **Story:** pick an i18n library — `@angular/localize` (built-in) vs. transloco (third-party, more flexible) — consuming the bundled JSON files chosen in [C15](../02-vision-and-constraints.md#3-hard-constraints). Phase-4 task.
  - **Story:** establish a typed API client generator (orval has Angular HttpClient output; openapi-generator's typescript-angular generator is the canonical option) that consumes the backend's springdoc-openapi schema.
