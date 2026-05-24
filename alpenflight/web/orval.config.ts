// Codegen config for the AlpenFlight TypeScript API client.
// Reads alpenflight/web/openapi/openapi.json (the committed snapshot of the live
// springdoc spec, refreshed by `./gradlew generateOpenApiSnapshot` in
// alpenflight/server) and emits an Angular client under
// src/app/api/generated/.
//
// retrievalClient: 'service' produces, per @Tag:
//   • *.service.ts          @Injectable, inject(HttpClient), used by all Signal Stores
//
// Was previously 'both' (also emitted *.resource.ts httpResource() helpers
// for zoneless reads), but no feature consumes the resource variant today
// and the orval generator emits TS2722-unsafe `body: signal?()` calls for
// POST endpoints whose body is a Signal (e.g. lookupPersonResource). Flip
// back to 'both' once orval ships the fix.
//
// Refresh: `pnpm run generate-api`. CI gate diffs the regenerated output
// against the committed tree (see .github/workflows/ci.yml).

import { defineConfig } from 'orval';

export default defineConfig({
  alpenflight: {
    input: { target: './openapi/openapi.json' },
    output: {
      target: './src/app/api/generated/alpenflight.ts',
      schemas: './src/app/api/generated/model',
      mode: 'tags-split',
      client: 'angular',
      indexFiles: true,
      prettier: false,
      tslint: false,
      mock: false,
      clean: true,
      override: {
        angular: { retrievalClient: 'service' },
        useTypeOverInterfaces: true,
      },
    },
  },
});
