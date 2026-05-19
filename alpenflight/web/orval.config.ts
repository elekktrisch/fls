// Codegen config for the AlpenFlight TypeScript API client.
// Reads alpenflight/web/openapi/openapi.json (the committed snapshot of the live
// springdoc spec, refreshed by `./gradlew generateOpenApiSnapshot` in
// alpenflight/server) and emits an Angular client under
// src/app/api/generated/.
//
// retrievalClient: 'both' produces, per @Tag:
//   • *.service.ts          @Injectable, inject(HttpClient), classic for writes
//   • *.httpResource.ts     signal-first httpResource() for zoneless reads
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
        angular: { retrievalClient: 'both' },
        useTypeOverInterfaces: true,
      },
    },
  },
});
