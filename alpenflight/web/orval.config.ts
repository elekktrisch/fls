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
