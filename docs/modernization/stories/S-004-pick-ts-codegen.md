---
id: S-004
title: Pick + wire TypeScript API client codegen
epic: E-01
status: in_progress
started_at: 2026-05-17
depends_on: [S-002, S-003]
acceptance:
  - Codegen tool is committed: orval, hey-api/openapi-ts, or openapi-typescript-codegen.
  - A `pnpm run generate-api` (or equivalent) regenerates TS types + an Angular HttpClient service from the snapshot OpenAPI spec under `next/web/openapi/`.
  - The hello endpoint from S-001 is reachable via the generated client from a sample Angular component.
  - Generated output is committed (not gitignored) so the SPA builds without server access.
estimate: M
adr_refs: [0005]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
context7_last_checked: 2026-05-17
github_issue: 50
github_pr: 51
---

## Context
ADR 0005 chose REST + OpenAPI + generated TS client. The library choice was deferred to a phase-4 story — this is it. Closes R5 (FlightStateMapper enum drift) at the build-system level.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Generate sample output from each candidate (orval, hey-api, openapi-typescript) against the snapshot spec.
- [ ] Compare: readability of generated code, discriminated-union handling for `FlightAircraftType`, enum output for `FlightProcessState`, Angular HttpClient idioms.
- [ ] Pick one; document decision in `next/web/openapi/README.md` (~5 lines).
- [ ] Wire `generate-api` script into `package.json`; commit generated output.
- [ ] Smoke test: a component calls the hello endpoint via the generated client.

## Notes
Soft recommendation: **orval** (best Angular HttpClient idioms + per-endpoint hooks); fallback **hey-api/openapi-ts** (cleanest typescript output, manually integrated with Angular `HttpClient`).

**Refinement collapses the three-candidate evaluation Task** (originally "generate sample output from each candidate"). Per ADR 0022 directive 1, that was gold-plate. Context7 verified that orval's `client: 'angular'` + `retrievalClient: 'both'` (signal-first reads via `httpResource()` + classic `HttpClient` services for writes) is the strongest fit for our zoneless / signals / `@ngrx/signals`-store stack. The decision is captured in `next/web/openapi/README.md` (~5 lines per AC); no per-candidate output directories shipped.

<!-- modernize-refine: start -->

## Design notes

### Tool pick

**orval** with `client: 'angular'`, `mode: 'tags-split'`, `retrievalClient: 'both'`.

Why orval over the alternatives: it's the only candidate that ships a first-party Angular generator emitting **both** signal-first `httpResource()` reads **and** `HttpClient` services side-by-side from one config — exactly the split per-feature signal stores will consume (reads → resource for view-layer, writes → service for store effects), with no extra Angular provider to wire. hey-api/openapi-ts is cleaner TS output but mandates `provideHeyApiClient(client)` + the separate `@hey-api/client-angular` install — extra surface for the same result. openapi-typescript is types-only and fails AC2 ("must emit an Angular HttpClient service"). `tags-split` aligns 1:1 with the `@Tag(name = ...)` per-domain discipline S-003's CONVENTIONS.md already pins.

### File layout

```
next/web/
├── orval.config.ts                      NEW — codegen config; sibling of angular.json
├── openapi/
│   ├── openapi.json                     existing (S-003 snapshot)
│   └── README.md                        NEW — operator manual (~5 lines per AC)
├── package.json                         + devDep "orval" + script "generate-api"
├── tsconfig.json                        + path aliases for @api/generated/*
├── eslint.config.mjs                    + ignores: ['src/app/api/generated/**']
├── .prettierignore                      already covers src/app/api/generated/** (S-002)
└── src/app/
    ├── api/generated/                   committed orval output (tags-split layout)
    │   ├── alpenflight.schemas.ts
    │   ├── model/
    │   │   └── helloResponse.ts
    │   └── hello/                       one folder per @Tag
    │       ├── hello.service.ts         @Injectable + inject(HttpClient) (writes)
    │       ├── hello.httpResource.ts    signal-first reads (Angular ≥19.2)
    │       └── index.ts
    ├── app.config.ts                    + provideHttpClient(withFetch())
    └── features/hello/                  NEW smoke feature
        ├── hello.routes.ts              exports HELLO_ROUTES
        ├── hello.component.ts           consumes generated client
        └── hello.component.spec.ts      vitest mocks the generated module
```

Path alias addition to `tsconfig.json` (extending S-002's existing `@api/*`):
```jsonc
"@api/generated":   ["src/app/api/generated"],
"@api/generated/*": ["src/app/api/generated/*"]
```

### `orval.config.ts` shape

```ts
import { defineConfig } from 'orval';

export default defineConfig({
  alpenflight: {
    input: { target: './openapi/openapi.json' },
    output: {
      target:  './src/app/api/generated/alpenflight.ts',
      schemas: './src/app/api/generated/model',
      mode:    'tags-split',
      client:  'angular',
      indexFiles: true,
      prettier: false,             // tree is prettier-ignored
      tslint:   false,
      mock:     false,             // YAGNI; revisit if MSW lands
      clean:    true,              // wipe stale files on regen
      override: {
        angular: { retrievalClient: 'both' },  // signal-first reads + service-based writes
        useTypeOverInterfaces: true,
      },
    },
  },
});
```

`retrievalClient: 'both'` is the load-bearing knob — `'httpResource'`-only forfeits write/command operations; `'httpClient'`-only forfeits the zoneless signal-first read story the rest of the stack is built on. `clean: true` lets the implementer trust regenerate-from-scratch.

### `package.json` additions

```jsonc
"scripts": {
  "generate-api": "orval --config ./orval.config.ts"
},
"devDependencies": {
  "orval": "^7.16.0"
}
```

orval's bundled TS loader handles `orval.config.ts` directly — no `tsx`/`ts-node` devDep needed.

### `app.config.ts` wiring

S-002 deferred `provideHttpClient()`. orval's generated services + `httpResource()` both inject `HttpClient`. Edit `app.config.ts`:

```ts
import { provideHttpClient, withFetch } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch()),                            // NEW
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
  ],
};
```

`withFetch()` over the default `XMLHttpRequest` backend: smaller polyfill surface, native streaming, zoneless-friendly. Dev proxy (`proxy.conf.json`) already routes `/api/v1/*` → `http://localhost:8080`. Interceptor slot stays empty here — S-020/S-021 add the auth interceptor via `withInterceptors([...])`.

### Smoke feature

`features/hello/hello.component.ts` — consumes the signal-first `httpResource` (uses `af-` selector per CLAUDE.md / ESLint rule, NOT `fls-`):

```ts
@Component({
  selector: 'af-hello',
  standalone: true,
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (hello.isLoading()) { <p>Loading…</p> }
    @else if (hello.error()) { <p class="text-red-600">Failed: {{ hello.error()?.message }}</p> }
    @else if (hello.value(); as r) {
      <h1 class="text-blue-600">{{ r.message }}</h1>
      <p>{{ r.timestamp | date:'medium' }}</p>
    }
  `,
})
export class HelloComponent {
  protected readonly hello = useHello();   // exact hook name follows orval's emit
}
```

`hello.routes.ts` exporting `HELLO_ROUTES`; registered in `app.routes.ts` via `loadChildren` per S-002's feature/routing convention. The route is dev-time-only — `TODO(S-020)` to remove or auth-gate before cutover.

### Drift detection — CI gate

orval has no built-in drift guard. Mirror S-003's pattern with a CI shell step in the existing `next-build` job:

```yaml
- name: Regenerate API client and assert no drift
  if: steps.detect.outputs.web == 'true'
  working-directory: next/web
  run: |
    pnpm run generate-api
    if ! git diff --exit-code src/app/api/generated/; then
      echo "::error::Generated API client is stale. Run 'pnpm run generate-api' locally and commit."
      exit 1
    fi
```

Developer flow (documented in `next/web/openapi/README.md`):
1. Change a controller / DTO on the server.
2. `cd next/server && ./gradlew generateOpenApiSnapshot` — refreshes `openapi.json`.
3. `cd next/web && pnpm run generate-api` — regenerates the TS client.
4. Commit all three (controller, snapshot, client) in one PR.

Failure modes: server-side stale snapshot → S-003's `compareOpenApiSnapshot` IT fires. Client stale → this story's CI gate fires. The two together close the drift class.

### Boyscout — fix `*/*` → `application/json` on the hello operation

`openapi.json:26` currently has `"*/*"` as the response content key (springdoc default when `@GetMapping` doesn't pin `produces`). orval / hey-api emit weaker types under `*/*`. Cheap fix: add `produces = MediaType.APPLICATION_JSON_VALUE` to `HelloController.hello()`, regenerate the S-003 snapshot, then this story's codegen. Folds in as a one-line boyscout in this PR.

### What this story does NOT include

- Auth interceptor / `Authorization: Bearer ...` — S-021 adds `withInterceptors([authInterceptor])` to the `provideHttpClient(...)` call landed here.
- Real domain endpoints — S-047+ (entity stories drive server `@Operation` + regenerated client incrementally).
- Discriminated-union proof on `FlightAircraftType` / `FlightProcessState` — lands with the entity stories that introduce those types into the spec.
- Signal-store wiring (services consumed inside `withMethods`) — S-006.
- MSW / mock generation (`output.mock: false`) — revisit if a story needs offline component dev.

### Alternatives considered

- **openapi-typescript (types-only)** — rejected. Fails AC's "Angular HttpClient service"; would re-introduce R5's drift class via a hand-written client.
- **hey-api/openapi-ts + @hey-api/client-angular** — rejected, narrow margin. Cleaner TS output, but mandates `provideHeyApiClient(client)` + separate plugin install. orval's first-party Angular emit is more idiomatic + ships signal-first `httpResource` natively.
- **openapi-typescript-codegen** — rejected up-front. Maintenance-only project.
- **Hand-written TypeScript types** — rejected on first principles. The exact R5 bug ADR 0005 closes.
- **`retrievalClient: 'httpResource'` only** vs `'both'` (chosen) — `'httpResource'`-only doesn't map to mutations; signal stores still need `inject(HelloService)` for writes. `'both'` is tree-shaken so unused services cost zero runtime.
- **Generate-on-build vs commit generated output (chosen: commit)** — already settled by AC4; committed output is reviewable, greppable, build-runnable without server access.
- **Three-candidate comparison spike** (original Task list) — collapsed by Context7 + directive 1. Decision captured in `openapi/README.md`; no per-candidate output directories shipped.

### Per ADR 0022 directive 2

Zero schema/migration touch. Front-end only. If the implementer feels a pull toward a `codegen_*` Flyway migration or stashing the generated tree in a DB table — stop and re-read this section.

## Edge cases & hidden requirements

- **`provideHttpClient()` is absent from `app.config.ts`** (S-002 deferred). The generated service injects `HttpClient`; this story MUST add `provideHttpClient(withFetch())` to `appConfig.providers`.
- **ESLint flat config has no `ignores` for the generated tree.** Add `{ ignores: ['src/app/api/generated/**'] }` block; codegen output usually fails the project's `af-` selector + strict-typing rules.
- **`.prettierignore` already covers `src/app/api/generated/**`** (landed in S-002). No change needed.
- **`.gitignore` does NOT exclude the generated tree** (correct — AC says committed). Implementer must NOT add it.
- **`*/*` media type on the hello 200 response** (`openapi.json:26`). Codegen emits weaker types. Boyscout fix in the same PR: add `produces = MediaType.APPLICATION_JSON_VALUE` to `HelloController.hello()` + regenerate snapshot.
- **OpenAPI 3.1 has no native `Instant` type** — `timestamp` lands as `type: string, format: date-time`. orval emits `timestamp: string` (not `Date`). Consumer-side `Date` coercion is OUT of scope — defer until a domain story needs it.
- **`bearerAuth` declared in `Components` only**, no global `SecurityRequirement`. orval will NOT emit an `Authorization` header for the hello operation under this spec. Correct posture for now; S-020 changes the spec shape + the client follows.
- **`@Tag(name = "Hello")` + `tags-split`** → orval emits files under `generated/hello/` matching the tag. Layout is deterministic; document in the README so reviewers know what to expect.
- **`exactOptionalPropertyTypes: true` + `noUncheckedIndexedAccess: true` in `tsconfig.json`** — orval-emitted optional properties (`property?: T` without `| undefined`) may need a `tsconfig` override for the generated folder. If the smoke build fails, add a localized override (not project-wide relaxation).
- **Component selector prefix must be `af-`** (CLAUDE.md + ESLint rule). The pre-existing `fls-root` in `index.html` is an inconsistency from S-002; do NOT extend it. The smoke component is `af-hello`.
- **Smoke component placement.** A routed feature at `features/hello/` is preferred over a pure vitest test — proves runtime DI works end-to-end. Add `TODO(S-020): remove or auth-gate before cutover` to the route.
- **`orval.config.ts` runs via orval's bundled TS loader** — no `tsx`/`ts-node` devDep needed.
- **`info.version` is absent from the committed snapshot** (S-003 strips it). orval handles missing `info.version` fine — emits no version constant.
- **`pnpm exec orval` vs `npx orval`** — use the bare `orval` in `package.json scripts` (pnpm resolves from `node_modules/.bin`). Avoids `npx` pulling a different version through its own cache.
- **CI working-directory.** The drift step runs `working-directory: next/web` (not repo root). pnpm install in `next/web/` already wires the bin.
- **`clean: true` in orval config** — first regeneration of an outdated tree wipes stale files. Safe because the tree is committed; reviewer sees the diff.
- **CRLF/LF churn on Windows** — add `next/web/.gitattributes` entry or repo-root `.gitattributes` rule pinning `src/app/api/generated/** text eol=lf` to keep the drift step deterministic across platforms.
- **Path alias `@api/generated/*` resolution in vitest** — verify `vitest.config.ts`'s `resolve.alias` picks up the same `tsconfig.json paths`. vitest-angular + the existing config should handle it; smoke test catches a misconfiguration at TestBed boot.
- **Three-candidate spike collapsed.** Original Task list had "generate sample output from each candidate"; per directive 1 + Context7 facts, the decision is captured in `openapi/README.md` (~5 lines). No per-candidate output directories committed.

## Security plan

(N/A — no auth / tenancy / PII / mutation surface. The generated client is wire-format code; `bearerAuth` is a placeholder scheme from S-003 that no operation references yet (S-020). Once S-020 adds `@SecurityRequirement(name = "bearerAuth")` to protected operations, the generated client will start emitting `Authorization` headers — at which point a security pass on the codegen output is warranted; out of scope here.)

## Test plan

### Pyramid

- Unit: 2 — `HelloComponent` smoke (mocked client) + missing-timestamp graceful render.
- Integration: 0 — generated client is a wiring artifact; service layer under test is a mock.
- E2E: 0 in S-004 scope — Playwright against the live backend deferred to S-110.
- Drift gate: 1 CI shell step (not a vitest test) — `pnpm run generate-api && git diff --exit-code src/app/api/generated/`.
- Compile gate: covered by existing `next build` job's `tsc` over `src/**`.

### Tests

1. **`HelloComponent renders message + timestamp from generated client`** — `TestBed.createComponent(HelloComponent)` with the generated `useHello` (or `HelloService`) mocked via `vi.mock('@api/generated/hello', ...)` returning `{ message: 'Hello AlpenFlight', timestamp: '2026-01-01T00:00:00Z' }`. After `detectChanges()`, assert rendered text contains both fields. Proves the generated client type compiles into the Angular DI graph and the data flow works.

2. **`HelloComponent handles missing timestamp gracefully`** — same harness; mock returns `{ message: 'Hello AlpenFlight', timestamp: undefined }` (optional per OpenAPI schema). Assert the component renders without throwing. Catches a strict-null-checks gap if orval's required/optional mapping is wrong.

### CI shell gates

- **Drift step** (next-build job): `pnpm run generate-api && git diff --exit-code src/app/api/generated/`. Failure message: `Generated API client is stale — run 'pnpm run generate-api' and commit the result.`
- **TS strict compile gate**: existing `pnpm build` (calls `ng build` → `tsc`) covers `src/app/api/generated/**`. No new step. Catches `strictNullChecks` / `noUncheckedIndexedAccess` / `exactOptionalPropertyTypes` violations in orval output.

### Fixtures

- **`vi.mock('@api/generated/hello', ...)`** per test — returns a hand-shaped `useHello()` (`isLoading`, `error`, `value` signals).
- **Committed `src/app/api/generated/`** — generated once via `pnpm run generate-api` and committed. The `.gitkeep` gets overwritten on first regeneration.

### Coverage gaps (deferred)

- Bearer-auth header injection — blocked on S-020 + S-021.
- Discriminator / enum handling for `FlightAircraftType` / `FlightProcessState` — no spec surface yet (S-062 / S-058 area).
- Full-spec codegen exercise — only `hello` exists today; each new controller story owns its own component-test coverage of the generated method.
- Server-roundtrip via Playwright (`e2e/tests/hello/hello-api.spec.ts`) — scaffold as `test.skip` with `TODO(S-110)` so the future port is mechanical.

### Risks

- **Angular 21 + orval's Angular target version mismatch.** Smoke test catches at compile + TestBed bootstrap. Mitigation: pin orval version; bump only after smoke passes.
- **vitest + esbuild handling of the generated tree.** Confirm `vitest.config.ts` `include` covers `src/app/api/generated/**` and that the @analogjs adapter doesn't need extra transform config for plain TS output.
- **`tsc --noEmit` false-green if `generated/` is still a `.gitkeep`.** The drift CI step would fail in that case (empty snapshot vs generated output), so both gates must be green simultaneously.
- **CRLF/LF churn on Windows dev hosts** triggering the drift step. Mitigation: `.gitattributes` rule pinning `src/app/api/generated/** text eol=lf`.

### Parity strategy

`parity_test: none`. The generated client is a wire-format artifact; correctness is "compiles + the smoke component renders the response." No legacy oracle exists for the codegen tool's output.

## Performance plan

(N/A — story has no performance signal: no DB queries, no server hot paths, no large data. orval's codegen runs at developer command + in CI; committed output ships with the SPA bundle, so runtime cost is identical to hand-written code. `pnpm install` grows by orval's transitive deps; negligible at CI scale.)

<!-- modernize-refine: end -->
