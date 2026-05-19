# next/web — frontend conventions

This file applies to all work under `next/web/`. The rewrite uses **modern Angular** (signal-based, standalone, zoneless) + **TailwindCSS** + **atomic-design** primitives. ADR 0004 + ADR 0006 are the source of truth for the framework + state-management choices; this file pins the day-to-day conventions that those ADRs leave open.

If a convention here conflicts with an ADR, the ADR wins — open an issue to update this file.

## 1. Atomic design taxonomy

Components live under `src/app/shared/ui/` in three layers. Each component is a single standalone component (no NgModules, ever).

```
src/app/shared/ui/
├── atoms/           single-purpose, zero-dependency primitives
│   ├── button/      <af-button>
│   ├── input/       <af-input>
│   ├── icon/        <af-icon>      Lucide line icons via dynamic-name (ADR 0024 / S-156)
│   └── badge/       <af-badge>
├── molecules/       compositions of 2–N atoms with one concern
│   ├── form-field/  <af-form-field>     label + input + error
│   ├── search-input/<af-search-input>   input + icon + clear
│   ├── menu-item/   <af-menu-item>      icon + label + chevron
│   └── field-errors/<af-field-errors>   reactive-forms error renderer (S-007)
└── organisms/       feature-agnostic compositions; CDK-backed where applicable
    ├── data-table/  <af-data-table>     sortable + paginated + filterable
    ├── dialog/      <af-dialog>         CDK overlay
    ├── date-picker/ <af-date-picker>    CDK + headless
    └── nav-bar/     <af-nav-bar>        app shell nav
```

Path aliases (declared in `tsconfig.json`):
```json
"@ui/atoms/*":     ["src/app/shared/ui/atoms/*"],
"@ui/molecules/*": ["src/app/shared/ui/molecules/*"],
"@ui/organisms/*": ["src/app/shared/ui/organisms/*"]
```

Layering rules:
- **Atoms** import nothing from `molecules/` or `organisms/`. They may import other atoms only when strictly necessary (avoid).
- **Molecules** may import atoms but not organisms.
- **Organisms** may import atoms + molecules.
- **Features** (`src/app/features/*`) consume any of the three. Reverse imports are forbidden.
- ESLint enforces via `no-restricted-imports` patterns where feasible.

Anything not feature-agnostic (i.e. tied to a specific domain entity) does **not** belong under `shared/ui/` — it lives in the feature folder.

## 2. Feature folders and routing

Features are grouped by **feature**, and each feature owns its own routing. There is no global "components dump" or shared `routes.ts` registry.

```
src/app/features/
├── landing/
│   ├── landing.routes.ts       exports LANDING_ROUTES: Routes
│   ├── landing.component.ts
│   └── landing.component.spec.ts
├── flights/
│   ├── flights.routes.ts       exports FLIGHTS_ROUTES with child routes (list, edit, ...)
│   ├── flight-list.component.ts
│   ├── flight-edit.component.ts
│   ├── flight.store.ts         feature-scoped Signal Store
│   └── flight.service.ts       optional thin wrapper over generated client
└── reservations/
    ├── reservations.routes.ts
    └── ...
```

Rules:

- One folder per feature, named after the domain noun (`flights`, `reservations`, `members`). Folder name == route segment.
- Every feature folder has a `<feature>.routes.ts` exporting a `const <FEATURE>_ROUTES: Routes`. No exceptions.
- `app.routes.ts` registers features via `loadChildren: () => import('@features/<x>/<x>.routes').then(m => m.<X>_ROUTES)`. **Never** `loadComponent` at the top level (placeholder/skeleton landing is the only allowance until the first real feature lands).
- Lazy by default. Eager loading requires a comment explaining why (auth shell, error pages — those are the only legitimate reasons).
- Feature-scoped stores, services, types live inside the feature folder. They are **not exported** outside the feature unless promoted to `shared/`.
- Cross-feature reuse goes through `shared/ui/` (UI), `shared/util/` (pure helpers), or `core/` (cross-cutting infra). **Direct imports between feature folders are forbidden** — ESLint enforces via `no-restricted-imports`.
- A feature's route data may carry `{ showNavBar, publicAccess, requiredRole }` — `AppComponent` reads `showNavBar` on `NavigationEnd` to toggle the chrome (closes the legacy `||` tautology bug structurally).
- A feature's e2e specs live under `next/web/e2e/tests/<feature>/` mirroring the folder name.

When a feature outgrows a single component (typical at 3+ routes or 4+ components), split into per-route subfolders (`flights/list/`, `flights/edit/`) but keep `flights.routes.ts` at the feature root as the single routing entry point.

## 3. Tailwind tokens (v4)

Tailwind v4 is the styling system. There is **no SCSS, no global CSS variables outside `@theme`, no CSS-in-JS**. Component `styles: []` arrays stay empty unless a Tailwind utility cannot express the rule (rare; document the why in a one-line comment).

- All design tokens (colors, spacing, type scale, shadows, radii) are defined in `src/styles.css` inside the `@theme { ... }` block as CSS custom properties (`--color-brand-500: oklch(...)`, `--font-display: "Inter", sans-serif`, `--breakpoint-3xl: 1920px`). **There is no `tailwind.config.js`** — v4 is CSS-first.
- Arbitrary values (`text-[#abc123]`, `w-[37px]`) require a one-line justification in the template. If a value recurs, promote it to a `@theme` token.
- Source-scanning is automatic. When a new source folder lives outside the default scan path, add an `@source "../path/**/*.{html,ts}";` directive at the top of `styles.css` — in the same PR.
- Inline-class allowlist for dynamic names (`bg-${color}-500`): use `@source inline("...")` in `styles.css`. Document each entry.
- `preflight` stays on. Components that need to opt out wrap in a scoped class — never disable preflight globally.
- PostCSS pipeline is **a single plugin**: `@tailwindcss/postcss` (in `postcss.config.mjs`). v4 handles vendor prefixing, `@import` resolution, and CSS nesting internally — do not add `autoprefixer`, `postcss-import`, or `tailwindcss-nesting`. No `cssnano` either (esbuild minifies).
- Color palette uses OKLCH (Tailwind v4 default). Hex is allowed but discouraged — OKLCH gives better perceptual uniformity for design-token tweaks.

## 4. Signals-first reactivity

The new code is signal-first. Greenfield, so no legacy RxJS to preserve.

- **State:** use `signal()`, `computed()`, `effect()`. Use NgRx Signal Store (`@ngrx/signals`) for shared/cross-component state — see S-006.
- **DI:** `inject()`, never constructor injection.
- **Inputs/outputs:** `input()` / `model()` / `output()` (signal-based APIs). Avoid `@Input()` / `@Output()` decorators in new code.
- **Templates:** `@if` / `@for` / `@switch` / `@defer`. Never `*ngIf` / `*ngFor` in new code.
- **Change detection:** zoneless. Components must work without zone.js — verify by avoiding `setTimeout`-driven view updates and untracked async writes outside `effect()`.
- **HTTP in components:** forbidden. Components inject a Signal Store; the store owns the generated-client call (e.g. `ClubsService`) inside `rxMethod`/`tapResponse`. ESLint enforces via `no-restricted-imports` on `features/**/*.component.ts`.
- **HTTP in stores:** generated OpenAPI service (`ClubsService` etc.) inside `rxMethod` is the canonical shape — the `authInterceptor()` from `angular-auth-oidc-client` attaches the Bearer to every `/api/v1/*` call. `httpResource()` / `rxResource()` are valid for component-local read-only views but should NOT be used inside a Signal Store (component-scoped resource refs aren't DI-injectable).
- **RxJS:** allowed where it fits (event streams, debouncing, websockets), but bridge to signals at the component boundary via `toSignal()`. Don't expose `Observable<T>` in component public surface.
- **Subjects:** `Subject` / `BehaviorSubject` are not state. Convert to signals for state; keep as event buses only when truly stream-shaped. The `MUTATION_BUS` (`core/mutation-bus/`) is the one application-wide bus — cross-store cache invalidation goes through it, not direct store-to-store injection.
- **Template signal invocation:** `@if (store.showX())` invokes the signal; `@if (store.showX)` treats the function reference as truthy and always renders. Mind the parens.
- **Conditional visibility:** when a render condition depends on domain state, expose it as a `computed()` on the store (e.g. `showAdvanced = computed(() => ...)`). Templates bind via `@if (store.showX())`. Avoid component-local signals that re-derive store state — duplicates the source of truth.

## 4b. Refetch policy & prefetch contract

Per-domain conventions for cache/refetch behavior. The reference store is `ClubsStore` (per S-048).

| Domain class | Policy | Trigger | Latency budget |
|---|---|---|---|
| Masterdata (aircraft / persons / locations / flight-types / routes) | Cache-long; TTL ≈ 1h | `SessionStore.bootstrapPrefetch()` on auth + tenant switch | < 1.5s all-parallel |
| Flights | Refetch-on-visibility | `document.visibilitychange` → `store.refresh()` | < 500ms p95 |
| Deliveries | Refetch-on-mutation | Subscribed to `MUTATION_BUS` `delivery.*` events | < 500ms p95 |
| Session-derived (current-user prefs) | One-shot | `bootstrapPrefetch` only | < 200ms |

Every domain store MUST subscribe to `session.logout` + `session.tenantSwitch` on the bus and `clear()` its state. The convention is the discipline; no runtime registry.

Public flows skip prefetch via `data: { publicAccess: true }` on the route (or `data: { skipPrefetch: true }` on a private route that needs to bypass). See `src/app/auth/README.md`.

## 5. Accessibility (WCAG 2.1 AA target)

Lint-enforced baseline (deeper a11y testing — axe-core / visual contrast / manual checklists — is a follow-up story; don't preemptively scaffold it).

- ESLint: `@angular-eslint/template/recommended-extra` rules are on; do not weaken.
- Every interactive element has a name (visible label, `aria-label`, or `aria-labelledby`). Icon-only buttons require `aria-label`.
- Forms: every input is paired with a `<label for>` or wrapped in `<af-form-field>` (which handles label association).
- Keyboard: every interactive control is reachable via `Tab`; visible focus state is never removed (do not write `outline: none` without a replacement focus ring).
- Headings: one `<h1>` per route; no skipped levels.
- Color: never convey state by color alone — pair with icon, text, or aria attribute.
- Modals + popovers (organisms): use Angular CDK `Overlay` + `FocusTrap` — they handle focus capture, escape-to-close, and ARIA roles correctly out of the box. Do not roll your own.
- `[innerHTML]` is forbidden in templates without a code-review approval comment.

When unsure about a specific ARIA pattern, check the latest WAI-ARIA Authoring Practices via Context7 (see §7) before guessing.

## 6. Templates, structure, and naming

- Component selector prefix: `af-` (AlpenFlight brand prefix; short by Angular convention, parallel to `mat-` / `cdk-` / `ng-`).
- File layout per component: `name.component.ts` (logic + template inline if < 30 lines, else external) + `name.component.spec.ts`. Avoid separate `name.component.html` for atoms/molecules unless the template is non-trivial.
- One component per file. One default-exported standalone class per file.
- Public API of a primitive folder is its `index.ts` (`export * from './button.component'`). Imports always go through `@ui/atoms/button`, never deep paths.
- Routes: see §2. Top-level routes use `loadChildren` per feature; per-feature routes use `loadComponent` for individual pages.

## 7. Library docs — use Context7

For anything Angular / TailwindCSS / NgRx Signals / Angular CDK / Spartan UI / RxJS / Playwright / @angular-eslint API questions, **fetch current docs via Context7** before answering. The framework moves fast; training data lags.

Workflow: `resolve-library-id` → pick best match (prefer high-trust, version-pinned IDs when a version matters) → `query-docs` with the full question.

This applies even when you "already know" — Angular signal APIs and zoneless rules in particular have shifted across 19/20/21.

## 8. Testing posture

**One rule, two layers:** unit tests for logic, Playwright for DOM.

- **Unit (Vitest, `pnpm test`).** Test logic classes only — services, signal stores, pure utilities, type guards, mappers. **Never `TestBed.createComponent` + DOM assertions** (`nativeElement.textContent`, `querySelector`, `toHaveText`, etc.). Component shapes are a Playwright concern; turning vitest into a fake browser duplicates surface area and adds zoneless + signal flakiness for no signal that an e2e wouldn't catch sooner.
- **E2E (Playwright, `pnpm e2e`).** Every feature ships at least one happy-path spec in `next/web/e2e/tests/<feature>.spec.ts`. UI rendering, routing, ARIA, keyboard flows, and "the generated client actually wires to the network" assertions all live here. Mock the backend via `page.route('**/api/v1/<path>', route => route.fulfill({...}))` when the spec doesn't need a live server. Real-backend e2e lands later (S-109/S-110 territory).
- **Legacy parity port:** later (S-109).
- **Coverage targets:** not enforced by CI; coverage accumulates per feature story.
- **Acceptable vitest specs today:** anything that asserts behavior without rendering a template — e.g. a `FlightStore` reducer / selector test, a `dateRange.spec.ts` for a pure helper, a guard's URL-building. **Not acceptable today:** `*.component.spec.ts` files that assert on rendered output. (Files predating this convention stay until their next touch; new specs follow the rule.)

## 9. Local-environment quirks (sandbox)

- `node_modules/` in this folder is a **symlink** to `/home/agent/fls-build/next-web/node_modules/`. The mounted Windows host FS at `/c/Users/...` cannot reliably `rmdir` deeply-nested directories during `pnpm install`, so the store + the live `node_modules` both live on the Linux-local FS. Don't `rm -rf` the symlink target without recreating it.
- pnpm is configured project-wide with `nodeLinker: hoisted` + `packageImportMethod: copy` (see `pnpm-workspace.yaml`). Don't switch to symlinked layout — that retriggers the cross-FS issue.
- **Install scripts are globally disabled** (`pnpm config set ignore-scripts true`, `npm config set ignore-scripts true`). esbuild's platform binary is selected via the `ESBUILD_BINARY_PATH` env var (persisted in `/etc/sandbox-persistent.sh`) — no postinstall needed.

## 10. Don't list

- No NgModules.
- No constructor DI in new code.
- No `*ngIf` / `*ngFor` / `*ngSwitch`.
- No `@Input()` / `@Output()` decorators in new code.
- No SCSS, no `tailwind.config.js` (v4 is CSS-first), no global CSS variables outside `@theme`, no CSS-in-JS.
- No `localStorage` / `sessionStorage` writes in app code (auth-owned files in S-021 are the only allowlist).
- No `bypassSecurityTrust*`.
- No deep imports into another feature folder. Cross-feature sharing goes through `shared/ui/`, `shared/util/`, or `core/`.
- No `HttpClient` injection in `features/**/*.component.ts`. Components inject Signal Stores (ESLint enforces via `no-restricted-imports`).
- No sibling-store injection from `features/**/*.store.ts`. Coordinate cross-domain via `MUTATION_BUS`. See `src/app/core/mutation-bus/README.md`.
- No raw `access_token` / `refresh_token` / `id_token` in any `signalStore` state. Tokens live in the OIDC library's storage (S-021).
- No `any` — `tsconfig.json` strict family is the floor.

## 11. Visual conventions

[ADR 0024](../../docs/modernization/adrs/0024-visual-design-system-and-tone.md) is the source of truth for the aesthetic layer — mood, neutrals, radius, elevation, typographic stance, icons, motion, copy voice, state personality, navigation pattern, wordmark, and ng-zorro override depth. Headlines that affect day-to-day work:

- **Neutrals:** Tailwind `slate` (cool blue-gray). Brand color stays at `--color-brand-500` (sky blue) and appears only on actions / links / focus rings / active nav indicators / selected-row accents / progress / the brand glyph — **never** on chrome backgrounds.
- **Radius:** sharp. `--radius-md: 0` in `src/styles.css`; 2px is the maximum tolerated exception.
- **Elevation:** flat — author code uses 1px slate-200 borders for separation, no shadows. Drop-shadow reserved for the active-modal overlay. ng-zorro defaults (dropdown shadows etc.) are accepted as a deliberate scope cap.
- **Typography:** Roboto. Body 400 / headings 500 (no Bold). Scale 1.125 (minor third). **Sentence case everywhere** — buttons, nav, labels. Apply the `.tabular` utility on numeric data columns.
- **Icons:** Lucide via `<af-icon name="…" />`. Registry at `core/icons/icon-registry.ts`; feature stories add named imports there. ng-zorro internal icons (chevrons, X, ✓) stay untouched.
- **Motion:** restrained — `opacity 120ms ease-out` only. No slide-in, no spring, no pulsing skeletons. Hover/focus state changes are instant.
- **State personality:** terse + functional. One-line empty states, no illustrations. Inline red helper for field errors, dismissable toast for global errors. Spinner only after 300ms.
- **Voice:** terse Swiss-impersonal. Short declarative + imperative. No "we". Past-tense done states. German-shaped economy; cheap to translate to DE / FR / IT.
- **Color mode:** light only v1. Semantic surface tokens (`--color-surface-bg`, `--color-surface-fg`, `--color-surface-muted`, `--color-border`) are in place so a future dark swap is a single PR.
- **ng-zorro:** bridge tokens only (`--ant-*` variables in `styles.css`). Accept antd defaults where the bridge doesn't reach (focus ring style, hover-bg tint, dropdown shadows, slide-down motion, table padding) — deliberate scope cap.

When in doubt, read ADR 0024. Drift from this section is a maintainability-reviewer finding.
