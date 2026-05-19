# alpenflight/web — AlpenFlight modern frontend

Greenfield Angular 21 SPA. See [`CLAUDE.md`](./CLAUDE.md) for day-to-day conventions; [ADR 0004](../../docs/modernization/adrs/0004-frontend-framework-and-build-tool.md) for the framework decision; [S-002](../../docs/modernization/stories/S-002-scaffold-web-skeleton.md) for the skeleton scope.

## Stack

- **Angular 21** — standalone, signals, zoneless, control-flow (`@if`/`@for`).
- **TailwindCSS v4** — CSS-first via `@theme` in `src/styles.css`, single `@tailwindcss/postcss` plugin.
- **TypeScript** — strict family (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, …).
- **Vitest** — Angular 21 CLI default test runner.
- **Playwright** — separate `e2e/` directory (not the legacy top-level `/e2e/`).
- **ESLint 10 flat config** + `@angular-eslint` v21 + Prettier.

## Prerequisites

- Node **22.13+** (see `.nvmrc`).
- pnpm **11+** via Corepack (`corepack enable && corepack prepare pnpm@latest --activate`).

## One-command start

```bash
pnpm install
pnpm start             # ng serve on http://localhost:4200
```

The dev server proxies `/api/v1/*`, `/Token`, `/oauth2/*`, `/realms/*` to `http://localhost:8080` per `proxy.conf.json`. Run the matching `alpenflight/server/` instance there.

## Scripts

| Script | Purpose |
|---|---|
| `pnpm start` | `ng serve` (dev, with proxy) |
| `pnpm build` | `ng build` (defaults to production config) |
| `pnpm build:prod` | explicit production build with bundle-budget enforcement |
| `pnpm test` | Vitest via `ng test` |
| `pnpm lint` | `ng lint` (ESLint flat config + Angular template rules) |
| `pnpm format` | Prettier check |
| `pnpm format:fix` | Prettier write |
| `pnpm e2e:install` | install Playwright browsers (Chromium) |
| `pnpm e2e` | Playwright against `pnpm start` |

## Deploy artifact

Production build emits **`dist/web/browser/`** (Angular 17+ `application` builder). Static-serve with SPA fallback to `index.html` (handled by Caddy in S-041).

## Environment / proxy conventions

- **Never** hardcode absolute server URLs in client code. The dev proxy + same-origin assumption keeps `/api/...` working uniformly across dev, staging, prod.
- `proxy.conf.json` targets are pinned to `http://localhost:8080`. Adding remote targets is forbidden (this is the legacy `start-test` / `start-prod` foot-gun this skeleton structurally prevents).
- Runtime config (OIDC client ID, public API base URL) belongs in `environment.ts` — **anything in `environment*.ts` ships to the browser; never put secrets there.**

## Layout

```
src/
├── main.ts                            bootstrap entry
├── index.html                         <af-root/>, lang="de", base="/"
├── styles.css                         @import "tailwindcss"; @theme { … }
└── app/
    ├── app.config.ts                  providers (zoneless + router + view transitions)
    ├── app.routes.ts                  top-level: loadChildren per feature
    ├── app.ts                         <router-outlet />
    ├── core/                          cross-cutting (interceptors, error handling)
    ├── features/
    │   └── landing/                   placeholder route `/`
    ├── shared/
    │   ├── ui/{atoms,molecules,organisms}/   atomic-design (filled by S-008)
    │   └── util/
    └── api/generated/                 OpenAPI codegen output (S-004)

public/                                 Angular 17+ assets (replaces src/assets)
└── i18n/                               de.json / en.json / fr.json / it.json (S-005)

e2e/                                    Playwright with AlpenFlight app
├── playwright.config.ts
└── tests/landing.spec.ts
```

## Sandbox environment quirks

The mounted Windows host filesystem at `/c/Users/...` cannot reliably `rmdir` deeply-nested directories during `pnpm install`. To work around this:

- `node_modules/` is a symlink to a Linux-local directory.
- `pnpm-workspace.yaml` sets `nodeLinker: hoisted` + `packageImportMethod: copy`.
- Install scripts are globally disabled (`pnpm config set ignore-scripts true`); esbuild's platform binary is selected via the `ESBUILD_BINARY_PATH` env var.

See [`CLAUDE.md` §9](./CLAUDE.md#9-local-environment-quirks-sandbox) for details.
