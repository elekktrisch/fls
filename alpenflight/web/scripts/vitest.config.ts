import { defineConfig } from 'vitest/config';

export default defineConfig({
  root: __dirname + '/..',
  test: {
    include: [
      'scripts/**/*.spec.ts',
      'e2e/proof-gallery/**/*.spec.ts',
      'e2e/chromium-executable.spec.ts',
    ],
    environment: 'node',
    globals: true,
  },
});
