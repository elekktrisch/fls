---
id: S-002
title: Scaffold next/web/ Angular skeleton
epic: E-01
status: in_progress
started_at: 2026-05-15
depends_on: []
github_issue: 8
acceptance:
  - `ng serve` runs the dev server; a placeholder "Hello FLS" route renders.
  - TailwindCSS is wired and a sample utility class (`text-blue-600`) renders correctly.
  - ESLint + Prettier are configured; `ng lint` passes on the skeleton.
  - Unit-test runner (Vitest preferred over Karma+Jasmine — modern, Vite-fast) is configured; one passing component test exists.
  - Playwright is wired against the new app (separate from the legacy `e2e/`); one passing landing-page test exists.
  - `next/web/CLAUDE.md` is preserved and matches the FE conventions (atomic design, signals-first, Tailwind tokens, a11y baseline, Context7 reminder). Pre-staged before this story; the implementer must respect it, not regenerate it.
  - Atomic-design scaffold exists under `src/app/shared/ui/{atoms,molecules,organisms}/` with `.gitkeep` placeholders and path aliases `@ui/atoms/*`, `@ui/molecules/*`, `@ui/organisms/*` wired in `tsconfig.json`.
  - Feature/routing convention is enforced from day one: `landing` lives at `src/app/features/landing/` with its own `landing.routes.ts` exporting `LANDING_ROUTES`; top-level `app.routes.ts` uses `loadChildren` (not `loadComponent`) per feature; eager routes are forbidden post-skeleton.
estimate: M
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
Frontend twin of S-001. Establishes the Angular project skeleton.

## Acceptance criteria
- See frontmatter. Plus: project uses standalone components (no NgModules); the `inject()` DI pattern; signal-based reactivity; control-flow syntax (`@if`/`@for`); zoneless change detection.
- All conventions in `next/web/CLAUDE.md` apply (atomic design taxonomy, Tailwind tokens, signals-first, a11y baseline). The implementer reads it before starting.

## Tasks
- [ ] Generate skeleton via `pnpm dlx @angular/cli@21 new` (see Build-tool decisions for full flags).
- [ ] Add TailwindCSS v4 via the official `@tailwindcss/postcss` route: `pnpm add -D tailwindcss @tailwindcss/postcss`; commit `postcss.config.mjs` + `@import "tailwindcss";` + `@theme { ... }` block in `src/styles.css`. No `tailwind.config.js`.
- [ ] Configure ESLint with `@angular-eslint` recommended; add Prettier.
- [ ] Replace Karma+Jasmine with Vitest (or Jest if Vitest's Angular support has edges — re-evaluate at impl time).
- [ ] Add Playwright in a separate `next/web/e2e/` directory; write one smoke spec hitting `ng serve`.
- [ ] Confirm `tsconfig.json` is strict, no `any`.
- [ ] Pre-create `src/app/shared/ui/{atoms,molecules,organisms}/.gitkeep` with empty `index.ts` per layer (`export {};`) so first imports resolve cleanly.
- [ ] Wire `@ui/atoms/*`, `@ui/molecules/*`, `@ui/organisms/*` path aliases in `tsconfig.json`.
- [ ] Land the feature-folder + per-feature-routes pattern: create `src/app/features/landing/landing.routes.ts` exporting `LANDING_ROUTES`; have `app.routes.ts` consume it via `loadChildren`. No `loadComponent` at top level.
- [ ] Verify `next/web/CLAUDE.md` is committed (already pre-staged before this story); do not regenerate it from scratch.

## Notes
Modern Angular (signal-based, Angular 21 line per ADR 0004). Closes — by virtue of TypeScript strict mode + S-003/S-004 — the precondition for R5's fix.

The atomic-design folder scaffold is created in this story even though `shared/ui/` content lands later (S-008). Reason: cheaper to fix the layout once, before any feature story imports through it. See `next/web/CLAUDE.md` §1 for the layering rules.

<!-- modernize-refine: start -->

## Design notes

### Module layout

```
next/web/
├── angular.json                    # CLI workspace; one project "next-web"
├── package.json                    # deps + scripts (start, build, test, e2e, lint, format)
├── pnpm-lock.yaml                  # pnpm chosen — see Build-tool decisions
├── tsconfig.json                   # base; strict family flags on
├── tsconfig.app.json
├── tsconfig.spec.json
├── eslint.config.mjs               # flat config (ESLint 10+; flat is the only supported format in v10)
├── .prettierrc
├── .prettierignore
├── postcss.config.mjs              # @tailwindcss/postcss only (autoprefixer + import handled by v4 internally)
├── proxy.conf.json                 # /api/v1/* + /Token + /oauth2/* + /realms/* → http://localhost:8080
├── vitest.config.ts                # @analogjs/vitest-angular preset (Karma fallback if AnalogJS lags)
├── .editorconfig
├── .gitignore                      # node_modules, dist, .angular, coverage, playwright-report, .auth/
├── .nvmrc                          # 22.13 (Angular 21 needs 22.12+; ESLint 10 needs 22.13+)
├── README.md                       # one-command start, deploy artifact path, env / proxy conventions
│
├── public/                         # Angular 17+ assets convention (replaces src/assets)
│   ├── favicon.ico                 # placeholder
│   └── i18n/                       # S-005 lives here: de.json, en.json, fr.json, it.json
│       └── .gitkeep
│
├── src/
│   ├── main.ts                     # bootstrapApplication(AppComponent, appConfig)
│   ├── index.html                  # <fls-root></fls-root>, lang="de", <base href="/">, CSP stub
│   ├── styles.css                  # @import "tailwindcss"; + @theme { ...design tokens... }
│   │
│   └── app/
│       ├── app.component.ts        # standalone shell; selector fls-root
│       ├── app.component.html      # @if(showNavBar) { <fls-nav-bar/> } <router-outlet/>
│       ├── app.config.ts           # providers (zoneless, router, http omitted, forms standalone)
│       ├── app.routes.ts           # Routes registry; data.showNavBar boolean per route
│       │
│       ├── core/                   # cross-cutting; auth/http interceptors/error handling land here
│       │   └── .gitkeep
│       │
│       ├── features/               # one folder per feature; each owns its routes file
│       │   └── landing/            # placeholder route /
│       │       ├── landing.routes.ts        # exported `LANDING_ROUTES`; consumed via loadChildren
│       │       ├── landing.component.ts
│       │       └── landing.component.html   # "Hello FLS" + text-blue-600 smoke
│       │
│       ├── shared/
│       │   ├── ui/                 # atomic-design primitives kit (S-008 fills these)
│       │   │   ├── atoms/          # button, input, icon, badge, ...
│       │   │   │   └── .gitkeep
│       │   │   ├── molecules/      # form-field, search-input, menu-item, field-errors
│       │   │   │   └── .gitkeep
│       │   │   └── organisms/      # data-table, dialog, date-picker, nav-bar
│       │   │       └── .gitkeep
│       │   └── util/
│       │       └── .gitkeep
│       │
│       └── api/
│           └── generated/          # S-004 codegen output (committed, not generated-on-build)
│               └── .gitkeep
│
├── e2e/                            # Playwright; lives WITH the web module
│   ├── playwright.config.ts        # webServer: { command: 'pnpm start', port: 4200, reuseExistingServer: !CI }
│   ├── tests/
│   │   └── landing.spec.ts         # smoke: nav to /, expect "Hello FLS", verify text-blue-600 + computed color
│   └── tsconfig.json               # separate from app tsconfig
│
└── .angular/                       # CLI cache; gitignored
```

Path aliases in `tsconfig.json`:
```json
"paths": {
  "@app/*":          ["src/app/*"],
  "@core/*":         ["src/app/core/*"],
  "@features/*":     ["src/app/features/*"],
  "@shared/*":       ["src/app/shared/*"],
  "@ui/atoms/*":     ["src/app/shared/ui/atoms/*"],
  "@ui/molecules/*": ["src/app/shared/ui/molecules/*"],
  "@ui/organisms/*": ["src/app/shared/ui/organisms/*"],
  "@api/*":          ["src/app/api/*"]
}
```

### Build-tool / framework decisions

| Knob | Decision | Rationale |
|---|---|---|
| **Angular CLI** | 21.x | ADR 0004. Use `application` builder (esbuild) — CLI default. |
| **Tailwind** | **v4.x** | GA + mature in 2026. CSS-first config via `@theme` directive in `styles.css` replaces `tailwind.config.js`. Single `@tailwindcss/postcss` plugin replaces the old `tailwindcss + autoprefixer` pair (vendor prefixing + `@import` resolution + nesting are built in). Oxide engine. |
| **Package manager** | **pnpm** | Faster installs, content-addressable store saves disk with legacy `flsweb/` still present, Angular CLI supports `--package-manager=pnpm` natively. Signals "this is the new module" cleanly vs. legacy yarn. |
| **Component prefix** | **`fls`** | Legacy uses `<fls-navigation-bar>` (`flsweb/src/index.html`). Preserves brand + paste-from-legacy. |
| **Style language** | **CSS** | Tailwind only needs CSS. Reject SCSS — utilities + Tailwind v4's `@theme` + native CSS nesting replace SCSS features. |
| **TS strict** | `strict: true` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `noImplicitOverride` + `noFallthroughCasesInSwitch` + `noImplicitReturns` | Angular CLI strict is baseline; extras close doors that bite when scaling. Closes R5's structural precondition. |
| **Zoneless change detection** | **Enabled** via `provideZonelessChangeDetection()` | Stable since Angular v20.2; Angular 21 inherits. Signals + control-flow + zoneless is the modern spine; drops zone.js (~30 KB gzipped). Greenfield deps are signal-first. |
| **SSR / prerendering** | **Off** (`--ssr=false`) | CSR-only SPA. SSR buys nothing for internal tenant SaaS. |
| **Standalone APIs** | Required everywhere | No NgModules. ESLint enforces. |
| **Test runner** | **Vitest** via `@analogjs/vitest-angular`; Karma+Jasmine fallback if AnalogJS lags at impl time | AnalogJS docs confirm Angular 17–21 support; setup uses `setupTestBed({ zoneless: true })` per their v21+ guide. |
| **Linter** | ESLint 10 flat config + `@angular-eslint` v21 | ESLint 10 drops eslintrc support entirely; flat config (`eslint.config.mjs`) is the only format. Requires Node 22.13+. |
| **Formatter** | Prettier 3.x + `eslint-config-prettier` | ESLint defers to Prettier (avoid `eslint-plugin-prettier` — slower, noisier diffs). |
| **Node** | 22.13+ LTS | `.nvmrc` = `22.13`; `engines.node` = `>=22.13`. Angular 21 needs 22.12+, ESLint 10 needs 22.13+ — pick the higher floor. |

Generation command (recorded in README):
```
pnpm dlx @angular/cli@21 new next-web \
  --directory=. \
  --prefix=fls \
  --style=css \
  --routing \
  --strict \
  --standalone \
  --ssr=false \
  --package-manager=pnpm
```

`app.config.ts` shape:
```ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    // provideHttpClient — deliberately deferred to S-005/S-006 (first real fetch)
    // ReactiveFormsModule consumed at the standalone-component level, not globally
  ],
};
```

### Domain model
N/A — this story scaffolds the SPA. No entities, no DB, no JPA. Domain modeling starts with feature stories (S-062b/c, S-097, etc.).

### API surface

**Client routes (`app.routes.ts`):**

Top-level `app.routes.ts` registers per-feature route arrays via `loadChildren` — never `loadComponent` directly. Each feature folder owns a `<feature>.routes.ts` exporting a `Routes` array. This keeps top-level routing readable as features grow.

```ts
// app.routes.ts
export const routes: Routes = [
  { path: '', loadChildren: () => import('@features/landing/landing.routes').then(m => m.LANDING_ROUTES) },
  { path: '**', redirectTo: '' },
];

// features/landing/landing.routes.ts
export const LANDING_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./landing.component').then(m => m.LandingComponent),
    data: { showNavBar: false } },
];
```

| Path | Owner | `data` | Guard |
|---|---|---|---|
| `/` | `features/landing` | `{ showNavBar: false }` | none |
| `**` | redirect to `/` | — | — |

**Dev proxy (`proxy.conf.json`):**
```json
{
  "/api/v1/*": { "target": "http://localhost:8080", "secure": false, "changeOrigin": true },
  "/Token":    { "target": "http://localhost:8080", "secure": false, "changeOrigin": true },
  "/oauth2/*": { "target": "http://localhost:8080", "secure": false, "changeOrigin": true },
  "/realms/*": { "target": "http://localhost:8080", "secure": false, "changeOrigin": true }
}
```

**Reserved client-owned paths (NOT proxied):** `/oidc-callback`, `/oidc-silent-renew` — owned by S-021. `proxy.conf.json` carries a top-comment forbidding remote targets (legacy `start-test`/`start-prod` patterns prohibited).

No actual HTTP calls in S-002. Landing renders static text + `text-blue-600` smoke.

### Integration with other stories

**Inputs:** none (zero-dep).

**Outputs / contracts S-002 must hold stable:**

| Story | Contract |
|---|---|
| **S-004 (TS codegen)** | Codegen drops at `src/app/api/generated/`; output committed (greppable, reviewable). |
| **S-005 (i18n)** | Translation JSON at `public/i18n/{de,en,fr,it}.json`. `<html lang="de">` default per C15. |
| **S-006 (signal-store)** | Pre-install `@ngrx/signals@^21` dep in S-002. First store ships with S-006. |
| **S-007 (reactive forms)** | `ReactiveFormsModule` consumed at standalone-component level; no global `provideForms()`. |
| **S-008 (primitives kit)** | `@shared/ui/*` path alias reserved; primitives empty placeholder. |
| **S-021 (OIDC client)** | `/oidc-callback` + `/oidc-silent-renew` reserved (NOT in proxy). Auth-interceptor slot in `provideHttpClient(withInterceptors([...]))` when S-021 adds `provideHttpClient`. |
| **S-040 (server Dockerfile)** | N/A — SPA has its own deploy path. |
| **S-041 (Caddy reverse proxy)** | Production artifact path: `dist/next-web/browser/` (Angular 17+ `application` builder layout). SPA fallback to `index.html` for client-side routing. |
| **S-097 (landing page port)** | Replaces `LandingComponent`. Nav-bar visibility from route `data.showNavBar` read by `AppComponent` on `NavigationEnd` — fixes legacy `||` tautology bug at `flsweb/src/index.js:50` by construction. |
| **S-098 / S-099 (public flows)** | Public routes use `data: { showNavBar: false, publicAccess: true }`; no auth guard. Pattern lives in `app.routes.ts` from day 1. |
| **S-109 (Playwright corpus port)** | E2E lives at `next/web/e2e/`. Separate config from legacy top-level `/e2e/`. `webServer` shape pre-mirrors legacy so port is mechanical. |

### Alternatives considered

- **Tailwind v4 (chosen) vs. v3.** v4 is GA + mature in 2026: `@tailwindcss/postcss` integrates cleanly with Angular CLI's esbuild PostCSS pipeline, CSS-first `@theme` config eliminates `tailwind.config.js`, Oxide is faster on large codebases, and the official upgrade tool exists for downstream library compat. v3 is still maintained but the rationale for picking it (v4 integration "rougher in 2026") is no longer true. Original draft chose v3; reversed during version verification.
- **Vitest (chosen, with Karma fallback) vs. Karma+Jasmine vs. Jest.** Story prefers Vitest; Analog preset is the established Vitest+Angular path in 2026. Karma is officially deprecated by Angular. Jest+Angular has lagged Angular major bumps historically. Fallback only if AnalogJS plugin lags Angular 21.x at impl time. **See Open design questions.**
- **Zoneless (chosen) vs. zone.js.** Zoneless is stable in Angular 21; aligns with signals-first direction; drops ~30 KB.
- **`fls-` prefix (chosen) vs. `app-`.** Legacy continuity; template paste-from-legacy works.
- **pnpm (chosen) vs. npm vs. yarn.** pnpm: faster, disk-cheap, Angular CLI supports natively. Signals "new module."
- **E2E at `next/web/e2e/` (chosen) vs. `next/e2e/`.** Co-located with web module; matches Angular CLI's e2e schematic convention.
- **`public/` (chosen) vs. `src/assets/`.** Angular 17+ moved to `public/`; greenfield project should not inherit the older convention.
- **Codegen output committed (chosen) vs. generated on build.** Committed: reviewable in PRs, no build-time generator flake, grep-friendly.
- **SSR off (chosen) vs. on.** CSR-only SPA — internal SaaS; SEO N/A.
- **`provideHttpClient` / `provideForms` / `provideAnimations` pre-wired (rejected) vs. deferred.** All deferred: avoids ~50 KB of dep weight until needed; cost of wiring later is one provider call per feature.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — `ng serve` runs; placeholder route renders**
- Node version mismatch (Angular 21 requires Node 22.12+); pin `engines.node` + `.nvmrc`.
- Port 4200 collision with parallel dev workflows; set explicit `--port` in `angular.json` `serve.options.port` or document.
- SSR (`@angular/ssr`) prompts during `ng new` in Angular CLI 21 — must explicitly decline (`--ssr=false`).
- `BrowserModule` leaks from copy-paste of stale templates — ESLint enforces standalone.
- Reload break when `styles.css` `@theme` or `@source` directives change: dev-server `watch` paths must cover the entry CSS so token edits trigger rebuilds.

**AC2 — TailwindCSS wired; `text-blue-600` renders**
- Tailwind v4 chosen. Source-scanning is automatic in v4 (no `content` glob array) — it walks `@source` directives + the entry CSS's import graph. If a UI primitive lives outside the standard scan path, add `@source "../path/**/*.{html,ts}";` to `styles.css`.
- Dynamic class names (`bg-${color}-500`) — v4 still needs explicit `@source inline` or full class strings for safelisting.
- Tailwind `preflight` clashes with Angular CDK overlays + native form controls landing in S-007/S-008 — keep enabled; opt out per-tree if needed.
- PostCSS pipeline: single `@tailwindcss/postcss` plugin (autoprefixer + `@import` + nesting are now built-in).

**AC3 — ESLint + Prettier; `ng lint` passes**
- `@angular-eslint` major must match Angular major (21).
- ESLint 10 flat config (`eslint.config.mjs`) — eslintrc support is removed in v10; flat config is the only supported format. `@angular-eslint` v21+ supports it.
- Node 22.13+ required by ESLint 10 (Node 22.12 is enough for Angular 21 alone but ESLint 10 raises the floor).
- Prettier integration: `eslint-config-prettier` (turns off conflicting rules) — Prettier runs separately. Do NOT use `eslint-plugin-prettier`.
- `ng lint` is NOT a built-in command since Angular 12; must be wired via `@angular-eslint/builder` (`ng add @angular-eslint/schematics`).
- Editor-side autofix conflict: `.editorconfig` is the single source of truth.

**AC4 — Unit-test runner configured; one passing test**
- Vitest + Angular 21 native is not in Angular CLI; requires `@analogjs/vitest-angular` (third-party). See Open design questions.
- Coverage tool: Vitest uses v8/istanbul; Karma uses karma-coverage. `.gitignore` for `coverage/`.
- TestBed bootstrap config must match runtime: if skeleton picks zoneless, `provideZonelessChangeDetection()` in test bootstrap too.

**AC5 — Playwright wired; one passing landing test**
- Browser binaries: `npx playwright install --with-deps chromium` step required in CI.
- `playwright.config.ts` `webServer` adds boot time (~5-15s); pick `reuseExistingServer: !process.env.CI`.
- Base URL collision: legacy uses port 3000; new uses 4200 (Playwright default for `ng serve`).
- Test artifacts (`test-results/`, `playwright-report/`) gitignored.
- Headless + `--no-sandbox` if running in container (mirrors legacy Karma `FlsChromeHeadless` pattern).

### Hidden requirements

- **Node engine pin** — `.nvmrc` = `22.13`; `engines.node` = `>=22.13`. Bumped from raw 22 to satisfy ESLint 10's floor.
- **Package manager pin** — `packageManager` field + lockfile committed.
- **TS strict surface** — clarify beyond `strict: true`: enable `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`.
- **Path aliases** — `@app/*`, `@core/*`, `@features/*`, `@shared/*`, `@api/*`.
- **Bundle-size budgets** — see Performance plan. Tighten Angular CLI defaults now.
- **Standalone-only enforcement** — ESLint rule guards against NgModule regression.
- **Dev proxy stub** — `proxy.conf.json` proxying `/api/v1/*` (and later OIDC paths) to `http://localhost:8080`.
- **`<base href="/">`** in `index.html` — avoids legacy `BASE_URL: '../..'` bug.
- **`<html lang="de">`** per C15 (German default per legacy).
- **Reserve `public/i18n/`** with stub `de.json` — S-005 picks lib, but load path is pinned.
- **Component selector prefix `fls`** — set in `angular.json` `projects.<name>.prefix` and ESLint rule.
- **Layout shell friendly to nav-bar toggle per route** — `data: { showNavBar: boolean }` pattern; closes legacy `||` tautology bug structurally.
- **Zoneless or zone.js** — zoneless chosen.
- **Bundle output path** — `dist/<project>/browser/` (Angular 17+ `application` builder).
- **CSP scaffolding** — stub `<meta>` in `index.html`; see Security plan.
- **Source map strategy** — prod `sourceMap: false`; staging may use `hidden-source-map` for Sentry (S-034).
- **`.gitignore` correctness** — `coverage/`, `playwright-report/`, `test-results/`, `.angular/cache/`, `node_modules/`, `dist/`, `.auth/`.
- **`README.md` in `next/web/`** — one-command setup, proxy expectations, "how to run e2e."
- **CI-friendly build** — `pnpm build` produces deployable `dist/` with no extra copy step.

### Scope clarifications

**In:** Angular 21 scaffold under `next/web/`; standalone + signals + control-flow; Tailwind v3 configured + one visible utility class; ESLint flat config + `@angular-eslint` + Prettier; `ng lint` wired; unit-test runner (Vitest with Karma fallback) + one passing test; Playwright under `next/web/e2e/` with one smoke; strict TS family flags; `.nvmrc` + `engines.node`; pnpm + lockfile; `proxy.conf.json` stub; `<base href>` + `<html lang>` + CSP stub; layout shell; bundle-size budgets in `angular.json`.

**Out:** Auth (S-021); i18n library pick (S-005); state lib (S-006); reactive-forms convention (S-007); component primitives kit (S-008); API codegen (S-004); CI pipeline (separate); SSR / prerendering; Sentry / error-tracker integration (S-034); response-header CSP (S-041); landing-page content (S-097); public flows (S-098/099); Storybook; PWA service worker.

**Ambiguous (resolved):**
- Vitest commitment level: Vitest primary, Karma fallback only if AnalogJS lags. See Open questions.
- Zoneless: chosen.
- Tailwind v3 chosen.
- `<base href="/">` (absolute).
- E2E at `next/web/e2e/`.
- Prefix: `fls-`.
- pnpm.
- `provideForms`/`provideAnimations` not pre-wired.

### NFR call-outs

- **Performance:** initial-bundle budget 300 KB warning / 500 KB error (gzipped) — see Performance plan. NFR p95 < 3s page load requires this from story 1.
- **Security:** no auth surface; CSP stub in `index.html`; no `bypassSecurityTrust*`; ESLint blocks `localStorage`/`sessionStorage` writes (legacy R10 calcification risk).
- **Observability:** skeleton commits NO `window.onerror` handler; S-034 owns telemetry.
- **Accessibility:** WCAG 2.1 AA target; enable `@angular-eslint/template/recommended-extra` a11y rules even on placeholder.
- **i18n:** `<html lang="de">` per C15; reserve `public/i18n/de.json` stub. No `/api/v1/translations` consumer.
- **Compliance (C4):** no Tailwind / Angular plugin should phone home; no `<link>` to Google Fonts / CDN; verify default `index.html`.

## Security plan

### Threat model

- **CSP absent (Angular CLI default):** MED — once components and 3rd-party assets land, missing CSP turns any DOM-XSS into account takeover. Mitigation: stub `<meta http-equiv="Content-Security-Policy">` baseline in `src/index.html` now; harden via response headers at reverse proxy (S-041).
- **`bypassSecurityTrust*` creep:** MED — once any component bypasses Angular sanitization, XSS lives forever. Mitigation: ESLint rule `@angular-eslint/template/no-bypass-trust` + `no-restricted-syntax` for `DomSanitizer.bypassSecurityTrust*`; CI-blocking.
- **Token/PII written to web-storage by convention:** HIGH (downstream) — legacy `sessionStorage` pattern (R10) is the calcification risk. Mitigation: ESLint rule `no-restricted-globals` for `localStorage`/`sessionStorage` writes in app code (allowlist via per-file disable in S-021's explicitly auth-owned files).
- **Open redirect from skeleton route config:** LOW — placeholder route has no redirect surface. Note for S-021: OIDC `redirect_uri` + post-login `returnTo` must be allowlisted to same-origin.
- **Dev proxy as credential leakage vector:** MED — operator could proxy `/Token` to a real production URL during dev. Mitigation: pin `proxy.conf.json` target to literal `http://localhost:8080`; top-comment forbids other targets; document in README.
- **Source maps in production build:** MED — leaks server-call shapes + route logic. Mitigation: confirm `angular.json` prod `sourceMap: false`.
- **`environment.ts` mistaken for `.env`:** HIGH if violated — anything imported from `environment.ts` ships to the browser. Mitigation: lint rule + README forbid placing secrets there; runtime config (OIDC client ID, API base URL — both public by design) is the only legitimate use.
- **Reverse-proxy mis-routing via wrong `<base href>`:** MED — legacy `BASE_URL: '../..'` is the bug this avoids. Mitigation: pin `<base href="/">` in `src/index.html`; Playwright smoke asserts `document.baseURI`.
- **Vulnerable transitive dependencies:** MED (cumulative). Mitigation: lockfile committed; follow-up for Renovate/Dependabot + `npm audit` in CI.
- **Playwright artifacts as auth-token sinks (post-S-021):** MED — traces and videos capture network bodies. Mitigation: `.gitignore` covers `next/web/e2e/.auth/`; CI retention bounded; flag for S-021.
- **Sub-resource integrity for future CDN assets:** LOW — Tailwind compiles locally. Note: any future CDN asset requires `integrity=` + `crossorigin=anonymous`.
- **Locale default leaks bias:** LOW — `<html lang="de">` is operator-correct default per C15.

### Authorization
N/A — no endpoints, no role-gated surfaces in this story. S-021 owns Angular OIDC client; S-020 owns server-side Spring Security. Skeleton commits zero auth code.

### Input validation
N/A — no forms, no user inputs. Conventions for downstream:
- Reactive Forms with typed `FormGroup<T>` (S-007); never untyped `FormBuilder`.
- DTOs from OpenAPI codegen (S-004); client-side validation is UX-only — server is the gate.
- `tsconfig.json` strict flags committed in this story close the door on FlightStateMapper-style drift (R5).

### PII handling
N/A — no PII rendered or transmitted. Conventions documented in `next/web/README.md`:
- **Templates:** Angular interpolation `{{ x }}` only; `[innerHTML]` requires reviewer approval + comment.
- **Logging:** `console.log` of DTOs OK in dev; prod build strips via Terser (Angular CLI default).
- **Telemetry (S-034):** when GlitchTip/Sentry lands, configure `beforeSend` to redact email, member numbers, license numbers, medical-cert fields, IP. Skeleton must not commit `window.onerror` handler that conflicts.

### Audit-log events
N/A — client emits no audit events. Server (S-027) is the authority.

### Cross-tenant leakage
N/A — no API calls, no `clubId` in scope. Conventions:
- State stores (S-006) must expose `reset()` hook keyed on `clubId` change (system-admin impersonation).
- HTTP interceptors (S-021/S-022) must inject `X-Tenant-Id` server-side or rely on principal — never client-supplied query param.

### OWASP applicability

- **A01 Broken Access Control:** N/A here.
- **A02 Cryptographic Failures:** N/A. `environment.ts` is not a secret store.
- **A03 Injection (XSS):** stub CSP `<meta>` baseline now; ESLint blocks `bypassSecurityTrust*`; `[innerHTML]` requires review.
- **A04 Insecure Design:** pin storage / token / proxy conventions in skeleton README → prevents legacy R10/R6 calcification.
- **A05 Security Misconfiguration: DOMINANT RISK.** See Skeleton-specific section.
- **A06 Vulnerable Components:** lockfile committed; `npm audit --omit=dev --audit-level=high` in CI (follow-up).
- **A07 Auth Failures:** N/A (S-021).
- **A08 Software & Data Integrity Failures:** lockfile committed; SRI required if any future asset is CDN-served; CI build is reproducible.
- **A09 Logging & Monitoring Failures:** S-034. Skeleton does NOT commit `window.onerror` global.
- **A10 SSRF:** N/A — client-side only.

### Skeleton-specific items

- **`src/index.html` baseline (commit in this story):**
  - `<html lang="de">` — matches C15.
  - `<base href="/">` — explicit, not relative.
  - CSP stub `<meta>`, strict-`'self'` baseline:
    ```html
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'">
    ```
    (`style-src 'unsafe-inline'` is Tailwind/Angular dev-friendly; tighten on prod once styles extract. Replace with response headers at reverse proxy in S-041.)

- **`angular.json` prod build:** `sourceMap: false`, `optimization: true`, `namedChunks: false`, `outputHashing: "all"`, no `fileReplacements` pointing at secret-bearing files.

- **`proxy.conf.json`:** single entry, literal `http://localhost:8080`, `secure: false`, `changeOrigin: true`. Top-of-file comment forbids remote targets.

- **`environment.ts` policy** (README): "Anything in `environment.*.ts` SHIPS TO THE BROWSER. Do not place secrets here."

- **ESLint rules committed in this story:**
  - `@angular-eslint/recommended` + `@angular-eslint/template/recommended`.
  - `@angular-eslint/template/no-bypass-trust`.
  - `no-restricted-syntax` blocking `DomSanitizer.bypassSecurityTrust*`.
  - `no-restricted-globals` blocking `localStorage`/`sessionStorage` direct use.
  - `no-restricted-imports` blocking secret-storage patterns.

- **`tsconfig.json`:** strict family flags (see Build-tool decisions).

- **`.gitignore`:** `dist/`, `coverage/`, `next/web/e2e/test-results/`, `next/web/e2e/playwright-report/`, `next/web/e2e/.auth/`.

- **Playwright artifact policy (flag for S-021):** once auth lands, configure `use.trace: 'retain-on-failure'` + `mask` selectors over auth-bearing requests, or scrub `Authorization` via `extraHTTPHeaders` redaction.

## Test plan

### Coverage contract

**S-002 owns:**
- Skeleton smoke: `ng serve` boots, `/` renders "Hello FLS", Tailwind utility `text-blue-600` produces blue text.
- Build-time gates: `ng lint` exits 0, `tsc --noEmit` exits 0, Prettier check exits 0, `ng build` produces dist bundle.
- One unit test on the placeholder `LandingComponent`.
- One Playwright e2e against `ng serve`.
- Pre-shape `next/web/e2e/playwright.config.ts` so S-109's port is mechanical.

**S-002 defers:**
- Real auth flow → S-021.
- Real component/feature tests → per-feature stories (S-097, S-062b/c, etc.).
- Full legacy Playwright parity port → S-109.
- Bundle-size regression budget enforcement → follow-up.
- A11y (`axe-core`), visual regression, cross-browser → follow-up.

### Test pyramid

- **Build-time gates:** 4 — `ng lint`, `tsc --noEmit`, Prettier `--check`, `ng build`.
- **Unit:** 2 — placeholder component renders + carries Tailwind utility class.
- **Integration (TestBed):** 0 — covered implicitly by Playwright smoke.
- **E2E (Playwright, new harness):** 1 — landing page renders + Tailwind class + computed style.
- **Parity:** 0 — no legacy oracle.

### Unit tests

- `LandingComponent renders hello message` — `TestBed.createComponent(LandingComponent)`, `fixture.detectChanges()`, assert `<h1>.textContent` matches `/Hello FLS/i`. SUT — `next/web/src/app/features/landing/landing.component.ts`. File — `next/web/src/app/features/landing/landing.component.spec.ts`.
- `LandingComponent applies tailwind utility class` — after `detectChanges()`, query `<h1>` and assert `classList.contains('text-blue-600')`. Proves Tailwind content paths include `src/**/*.{html,ts}` — common skeleton misconfig. Same file.

Both tests run under Vitest (`@analogjs/vitest-angular`); Karma+Jasmine fallback uses identical assertions.

### Integration tests
None — no router config beyond the placeholder, no HTTP, no DI graph worth slicing.

### E2E tests

- `e2e/tests/landing.spec.ts` — `landing page renders + has tailwind class`:
  - `page.goto('/')`
  - `await expect(page).toHaveTitle(/FLS/i)`
  - `await expect(page.locator('h1')).toBeVisible()`
  - `await expect(page.locator('h1')).toHaveText(/Hello FLS/i)`
  - `await expect(page.locator('h1')).toHaveClass(/text-blue-600/)`
  - `await expect(page.locator('h1')).toHaveCSS('color', 'rgb(37, 99, 235)')` — catches "class present but CSS didn't load."
  
  Mirrors the shape of legacy `e2e/tests/landing.spec.ts` so S-109's port is mechanical.

### Parity tests
None — no legacy oracle. Forward-compat note for S-109: `next/web/e2e/playwright.config.ts` should mirror legacy config shape — `baseURL` from env (default `http://localhost:4200`), `webServer` block running `ng serve` with `reuseExistingServer: !process.env.CI`, `testDir: './tests'`, `projects` array ready to be split into `read` / `mutate` later.

### Test data + fixtures
N/A — no DB, no domain entities, no fixtures, no seed.

### Coverage gaps (deferred)

- Real component/feature tests → per-feature stories.
- Auth flow → S-021.
- Full legacy Playwright parity port → S-109.
- Bundle-size CI regression → follow-up.
- A11y (`@axe-core/playwright`) → follow-up.
- Visual regression (`toHaveScreenshot`) → follow-up.
- Cross-browser (Firefox, WebKit) → defer; smoke runs Chromium only.

### Risks

- **Vitest on Angular 21:** `@analogjs/vitest-angular` peer-dep range may lag Angular 21.x at impl time. Mitigation: pin known-good versions; fall back to Karma+Jasmine with identical test names. See Open design questions.
- **Playwright `webServer` startup flake:** `ng serve` on 4200 may already run locally. Mitigation: `reuseExistingServer: !process.env.CI`, `port: 4200`, `timeout: 120_000` for cold esbuild startup.
- **Tailwind v3 PostCSS pipeline divergence** between `ng serve` and Vitest's jsdom: jsdom doesn't run PostCSS. Mitigation: unit test asserts only `classList.contains` (class presence); computed-style assertion lives in Playwright e2e where full CSS pipeline runs.
- **`ng lint` not a built-in command** since Angular 12+: wire via `@angular-eslint/builder` schematic. Implementer verifies `pnpm ng lint` exits 0.
- **Playwright browser install in CI:** `npx playwright install --with-deps chromium` step required. Document.
- **Title assertion brittleness:** literal title `"FLS"` recommended; regex stays cheap.
- **Skeleton scope creep:** anything beyond a single placeholder component + one route invalidates the "no fixtures, no integration tests" call. Reviewer should reject PRs that add `provideRouter` config, `provideHttpClient`, auth scaffolding — those belong later.

## Performance plan

### Hot paths
None in this story. First real hot path is the flight list in S-062b.

### Required indexes
N/A — client-only.

### N+1 risks
N/A — no API calls; no `HttpClient` wired. S-006 introduces first fetch patterns; revisit fan-out signal resolves there.

### Caching strategy
- **Server-side:** N/A.
- **Client-side (Signal Store):** deferred to S-006 — do NOT pre-stub any cache layer in S-002.
- **HTTP / Service Worker:** do NOT install `@angular/service-worker` or `provideServiceWorker()` in S-002. PWA caching is a separate, later decision.

### Latency budget

- **`ng serve` cold-start:** < **8 s** on a dev laptop (esbuild `application` builder).
- **Incremental rebuild (hot reload):** < **500 ms** median for single-file `.ts`/`.html` edit; < **1.5 s** for Tailwind class addition.
- **`ng build` (prod, cold):** < **30 s** for empty skeleton.
- **Initial bundle (gzipped, prod, `main + runtime + polyfills + styles`):** target **≤ 150 KB**. Bare Angular 21 standalone + Tailwind purge + zoneless typically 130–160 KB. If S-002 ships above 200 KB, something is wrong (Tailwind not purging, zone.js still imported, stray `@angular/animations` import).
- **`angular.json` budgets (hard wall, committed in this story):**
  - `initial`: `maximumWarning: 300kb`, `maximumError: 500kb`.
  - `anyComponentStyle`: `maximumWarning: 4kb`, `maximumError: 8kb`.
- **LCP target on Fast 3G:** N/A in S-002 — no content. S-062c's LCP/INP measurement uses S-002's TTFB + bundle-parse cost as lower bound.

### Memory considerations
N/A — no streaming, no large in-memory state.

### Performance test plan

- **Bundle-size assertion:** rely on `angular.json` budgets to fail `ng build --configuration production` in CI when initial bundle exceeds 500 KB gzipped. **Only automated perf gate in S-002.**
- **Manual checkpoint at PR review:** record `dist/next-web/browser/` gzipped sizes for `main-*.js`, `polyfills-*.js`, `runtime-*.js`, `styles-*.css` in PR description. These form the S-002 baseline cited by downstream stories.
- **Hot-reload smoke:** documented target only; not automated. Re-check manually when adding heavyweight deps (S-006, S-008).
- **k6 / Lighthouse:** N/A. Lighthouse CI starts in S-062b.

### Configuration choices that affect future perf

`angular.json` `projects.next-web.architect.build.configurations.production`:
- `"optimization": true` — full minification + dead-code elim.
- `"outputHashing": "all"` — long-term caching via content hashes.
- `"buildOptimizer": true` — keeps tree-shaking aggressive.
- `"sourceMap": false` — smaller payload + IP protection.
- `"namedChunks": false` — content-hash-only.
- `"subresourceIntegrity": true` — supply-chain hardening; cost negligible.
- `"extractLicenses": true` (default).
- `"budgets"`: as specified above.

`app.config.ts`:
- `provideZonelessChangeDetection()` — drops zone.js ~30 KB gzipped. Verify `polyfills` array in `angular.json` is empty / no `zone.js` entry.
- `bootstrapApplication(AppComponent, appConfig)` — no `AppModule`.
- `provideRouter([...], withComponentInputBinding(), withViewTransitions())` — zero-overhead enablers; pay off in S-062b.
- Do NOT add `provideAnimations()` in S-002. Add `provideAnimationsAsync()` later only when a component needs it; saves ~20 KB.
- Do NOT add `provideHttpClient()` yet — wait until S-005/S-006 introduces first real fetch.

`tsconfig.json`:
- `"target": "ES2022"` (Angular 21 default) — do NOT lower; balloons bundle and disables modern syntax esbuild emits efficiently.
- `"useDefineForClassFields": true` (default).
- `"moduleResolution": "bundler"` — required for correct ESM tree-shaking with esbuild.

Tailwind v4:
- Source-scanning is automatic (no `content` glob). Default scan covers files reachable from the entry CSS's `@import` graph + the project root. If a UI primitive lives outside the standard scan, add `@source "../path/**/*.{html,ts}";` to `styles.css`. Enumerate specific paths — never `@source "./node_modules/**/*"`, which blows CSS up.
- Tokens live in `styles.css` `@theme { ... }` block. No `tailwind.config.js`.
- PostCSS: single `@tailwindcss/postcss` plugin. No `autoprefixer` (built in), no `cssnano` (esbuild handles minification).

Routing convention (README + code-review enforcement from S-003 onward):
- **Every feature route after S-002 must use `loadComponent: () => import(...)` for lazy loading.** Eager routes are reserved for auth/login shell + placeholder. This is the lever that keeps `initial` under 500 KB as feature work compounds.
- One route file per feature folder.

ESM / tree-shaking:
- Named imports from `@angular/*` and 3rd-party libs.

Preconnect / DNS prefetch:
- Same-origin assumed. No preconnect/dns-prefetch in `index.html`.

### Risks

- **Tailwind v4 source-scan divergence between `ng serve` and `ng build`.** v4's automatic scan can pick up files differently when dev-server's file watcher races with esbuild's incremental output, especially for new UI files added to non-default scan paths. Mitigation: run `ng build --configuration production` locally + visually verify before merging S-002. Canonical smoke for any `@source` directive or `styles.css` change.
- **Initial-bundle budget too tight tripping on first real feature.** 500 KB error ceiling is generous now (~150 KB) but tightens fast once `@angular/forms` + `HttpClient` + first OpenAPI client land (S-005/S-006). Mitigation: revisit budget in S-006's perf plan; expect to raise `maximumError` to ~750 KB once data fetching + reactive forms are in. Never raise without measuring.
- **Source maps accidentally on in prod.** Mitigation: CI check greps `dist/next-web/browser/*.map` after prod build and fails if any exist (or asserts only present in `staging` config).
- **`ng serve` cold-start regression as deps accumulate.** Each new Angular sub-package adds ~0.5–1 s to esbuild's dep pre-bundle. Mitigation: track cold-start in each perf plan starting S-006; if > 15 s, audit imports.
- **Zoneless edge cases with future 3rd-party libs.** Some older `ng2-*` or RxJS-heavy state libs assume change-detection ticks. When evaluating libs in S-006 (state) and S-008 (primitives), explicitly check zoneless compatibility. If a critical lib requires zone.js, that's an architect-level revisit — do NOT silently re-add zone.js.
- **Tailwind v4 source-path drift.** If a future story adds component files outside the default scan (e.g. `next/web/libs/`), Tailwind v4's automatic scan won't pick them up. Mitigation: when introducing any new source folder, add an `@source "../path/**/*.{html,ts}";` directive in `styles.css` in the same PR.

## Open design questions

These specialists' analyses disagreed (or hedged): surfaced for operator input.

1. **Vitest now, or Karma+Jasmine fallback for the initial scaffold?**
   - **Solution-architect (Vitest now):** Story prefers Vitest; AnalogJS preset is the established 2026 path; Karma is officially deprecated by Angular. Fallback to Karma only if AnalogJS plugin peer-deps lag Angular 21.x at impl time.
   - **Requirements-engineer (Karma now, follow-up story for Vitest):** Karma+Jasmine is Angular CLI's supported default. Shipping a third-party adapter as the foundation of every later test is risk at major-version boundaries. Recommend a dedicated "S-002b: migrate to Vitest" once AnalogJS confirms Angular 21 support.
   - **Trade-off:** Vitest-first is forward-looking but couples the project to a third-party adapter's release cadence. Karma-first is boring + stable but ships with a deprecated runner that S-109's port (when it lands) inherits. **Implementer's call at PR time;** either choice keeps test names + assertions stable.

<!-- modernize-refine: end -->
