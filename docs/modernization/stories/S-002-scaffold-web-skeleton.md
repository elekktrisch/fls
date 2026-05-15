---
id: S-002
title: Scaffold next/web/ Angular skeleton
epic: E-01
status: todo
depends_on: []
acceptance:
  - `ng serve` runs the dev server; a placeholder "Hello FLS" route renders.
  - TailwindCSS is wired and a sample utility class (`text-blue-600`) renders correctly.
  - ESLint + Prettier are configured; `ng lint` passes on the skeleton.
  - Unit-test runner (Vitest preferred over Karma+Jasmine — modern, Vite-fast) is configured; one passing component test exists.
  - Playwright is wired against the new app (separate from the legacy `e2e/`); one passing landing-page test exists.
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
- See frontmatter. Plus: project uses standalone components (no NgModules); the `inject()` DI pattern; signal-based reactivity; control-flow syntax (`@if`/`@for`).

## Tasks
- [ ] Generate skeleton via `ng new next-web --standalone --routing --style=css --strict`.
- [ ] Add TailwindCSS via official Angular guide; commit `tailwind.config.js`.
- [ ] Configure ESLint with `@angular-eslint` recommended; add Prettier.
- [ ] Replace Karma+Jasmine with Vitest (or Jest if Vitest's Angular support has edges — re-evaluate at impl time).
- [ ] Add Playwright in a separate `next/web/e2e/` directory; write one smoke spec hitting `ng serve`.
- [ ] Confirm `tsconfig.json` is strict, no `any`.

## Notes
Modern Angular (signal-based, Angular 21 line per ADR 0004). Closes — by virtue of TypeScript strict mode + S-003/S-004 — the precondition for R5's fix.

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
├── eslint.config.mjs               # flat config (ESLint 9+ standard in 2026)
├── .prettierrc
├── .prettierignore
├── tailwind.config.js              # v3 chosen — see Alternatives
├── postcss.config.js               # tailwindcss + autoprefixer
├── proxy.conf.json                 # /api/v1/* + /Token + /oauth2/* + /realms/* → http://localhost:8080
├── vitest.config.ts                # @analogjs/vitest-angular preset (Karma fallback if AnalogJS lags)
├── .editorconfig
├── .gitignore                      # node_modules, dist, .angular, coverage, playwright-report, .auth/
├── .nvmrc                          # 22 (active LTS in 2026)
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
│   ├── styles.css                  # @tailwind base/components/utilities
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
│       ├── features/               # one folder per domain feature (S-062b, S-097, ...)
│       │   └── landing/            # placeholder route /
│       │       ├── landing.component.ts
│       │       └── landing.component.html  # "Hello FLS" + text-blue-600 smoke
│       │
│       ├── shared/
│       │   ├── ui/                 # S-008 primitives kit drops here
│       │   │   └── .gitkeep
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
  "@app/*":      ["src/app/*"],
  "@core/*":     ["src/app/core/*"],
  "@features/*": ["src/app/features/*"],
  "@shared/*":   ["src/app/shared/*"],
  "@api/*":      ["src/app/api/*"]
}
```

### Build-tool / framework decisions

| Knob | Decision | Rationale |
|---|---|---|
| **Angular CLI** | 21.x | ADR 0004. Use `application` builder (esbuild) — CLI default. |
| **Tailwind** | **v3.4.x** | v4 (Oxide) is faster but its Angular CLI PostCSS integration is rougher in 2026. v3 is the safer foundation; upgrade is one story post-cutover. |
| **Package manager** | **pnpm** | Faster installs, content-addressable store saves disk with legacy `flsweb/` still present, Angular CLI supports `--package-manager=pnpm` natively. Signals "this is the new module" cleanly vs. legacy yarn. |
| **Component prefix** | **`fls`** | Legacy uses `<fls-navigation-bar>` (`flsweb/src/index.html`). Preserves brand + paste-from-legacy. |
| **Style language** | **CSS** | Tailwind only needs CSS. Reject SCSS — utilities replace nesting + variables. |
| **TS strict** | `strict: true` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `noImplicitOverride` + `noFallthroughCasesInSwitch` + `noImplicitReturns` | Angular CLI strict is baseline; extras close doors that bite when scaling. Closes R5's structural precondition. |
| **Zoneless change detection** | **Enabled** via `provideZonelessChangeDetection()` | Angular 21 stabilizes zoneless. Signals + control-flow + zoneless is the modern spine; drops zone.js (~30 KB gzipped). Greenfield deps are signal-first. |
| **SSR / prerendering** | **Off** (`--ssr=false`) | CSR-only SPA. SSR buys nothing for internal tenant SaaS. |
| **Standalone APIs** | Required everywhere | No NgModules. ESLint enforces. |
| **Test runner** | **Vitest** via `@analogjs/vitest-angular`; Karma+Jasmine fallback if AnalogJS lags Angular 21 at impl time | Story prefers Vitest. See Open design questions. |
| **Linter** | ESLint 9 flat config + `@angular-eslint` v21 | Flat config (`eslint.config.mjs`) is the ESLint 9 default. |
| **Formatter** | Prettier 3.x + `eslint-config-prettier` | ESLint defers to Prettier (avoid `eslint-plugin-prettier` — slower, noisier diffs). |
| **Node** | 22 LTS | `.nvmrc` = `22`; `engines.node` = `>=22`. Angular 21 requires Node 22.12+. |

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

| Path | Component | `data` | Guard |
|---|---|---|---|
| `/` | `LandingComponent` (placeholder) | `{ showNavBar: false }` | none |
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

- **Tailwind v3 (chosen) vs. v4.** v3 has rock-solid Angular CLI + PostCSS integration; v4 Oxide is faster but the Angular integration story is still rougher in 2026. Foundation story → pick boring.
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
- Reload break when `tailwind.config.js` changes: dev-server `watch` paths must cover Tailwind config + content globs.

**AC2 — TailwindCSS wired; `text-blue-600` renders**
- Tailwind v3 vs. v4: v3 chosen; story phrasing (`tailwind.config.js`) implies v3 mental model.
- Content-scan misconfig: `content: ["./src/**/*.{html,ts}"]` must cover `.ts` (for inline templates / `[ngClass]` string templates).
- PurgeCSS over-eagerness on dynamic class names (`bg-${color}-500`) — document `safelist` escape hatch.
- Tailwind `preflight` clashes with Angular CDK overlays + native form controls landing in S-007/S-008 — keep `preflight: true`; flag.
- PostCSS plugin order: `tailwindcss` → `autoprefixer`.

**AC3 — ESLint + Prettier; `ng lint` passes**
- `@angular-eslint` major must match Angular major (21).
- ESLint 9+ flat config (`eslint.config.mjs`) — `@angular-eslint` v21+ supports it. Pick flat (forward path).
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

- **Node engine pin** — `.nvmrc` + `package.json` `engines.node`.
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

Tailwind v3:
- `content: ['./src/**/*.{html,ts}']` — narrow purge globs are the single biggest CSS-size lever. Do NOT add `'./node_modules/**/*'` even when a UI lib later wants it; enumerate specific files. Wide globs blow CSS up to 50–100 KB instantly.
- `corePlugins`: default.
- PostCSS via `postcss.config.js` with `tailwindcss` + `autoprefixer`; do NOT add `cssnano` — esbuild handles minification.

Routing convention (README + code-review enforcement from S-003 onward):
- **Every feature route after S-002 must use `loadComponent: () => import(...)` for lazy loading.** Eager routes are reserved for auth/login shell + placeholder. This is the lever that keeps `initial` under 500 KB as feature work compounds.
- One route file per feature folder.

ESM / tree-shaking:
- Named imports from `@angular/*` and 3rd-party libs.

Preconnect / DNS prefetch:
- Same-origin assumed. No preconnect/dns-prefetch in `index.html`.

### Risks

- **Tailwind v3 PostCSS pipeline divergence between `ng serve` and `ng build`.** JIT can purge differently when dev-server's file watcher races with esbuild's incremental output. Mitigation: run `ng build --configuration production` locally + visually verify before merging S-002. Canonical smoke for any Tailwind config change.
- **Initial-bundle budget too tight tripping on first real feature.** 500 KB error ceiling is generous now (~150 KB) but tightens fast once `@angular/forms` + `HttpClient` + first OpenAPI client land (S-005/S-006). Mitigation: revisit budget in S-006's perf plan; expect to raise `maximumError` to ~750 KB once data fetching + reactive forms are in. Never raise without measuring.
- **Source maps accidentally on in prod.** Mitigation: CI check greps `dist/next-web/browser/*.map` after prod build and fails if any exist (or asserts only present in `staging` config).
- **`ng serve` cold-start regression as deps accumulate.** Each new Angular sub-package adds ~0.5–1 s to esbuild's dep pre-bundle. Mitigation: track cold-start in each perf plan starting S-006; if > 15 s, audit imports.
- **Zoneless edge cases with future 3rd-party libs.** Some older `ng2-*` or RxJS-heavy state libs assume change-detection ticks. When evaluating libs in S-006 (state) and S-008 (primitives), explicitly check zoneless compatibility. If a critical lib requires zone.js, that's an architect-level revisit — do NOT silently re-add zone.js.
- **Tailwind content-glob drift.** If a future story adds component files outside `src/` (e.g. `next/web/libs/`), Tailwind won't purge them and won't pick up utility classes either. Mitigation: when introducing any new source folder, update `content` globs in the same PR.

## Open design questions

These specialists' analyses disagreed (or hedged): surfaced for operator input.

1. **Vitest now, or Karma+Jasmine fallback for the initial scaffold?**
   - **Solution-architect (Vitest now):** Story prefers Vitest; AnalogJS preset is the established 2026 path; Karma is officially deprecated by Angular. Fallback to Karma only if AnalogJS plugin peer-deps lag Angular 21.x at impl time.
   - **Requirements-engineer (Karma now, follow-up story for Vitest):** Karma+Jasmine is Angular CLI's supported default. Shipping a third-party adapter as the foundation of every later test is risk at major-version boundaries. Recommend a dedicated "S-002b: migrate to Vitest" once AnalogJS confirms Angular 21 support.
   - **Trade-off:** Vitest-first is forward-looking but couples the project to a third-party adapter's release cadence. Karma-first is boring + stable but ships with a deprecated runner that S-109's port (when it lands) inherits. **Implementer's call at PR time;** either choice keeps test names + assertions stable.

<!-- modernize-refine: end -->
