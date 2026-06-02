import { defineConfig } from 'vitest/config';

// Standalone vitest config for non-`src/` tooling specs. Kept separate from the
// Angular `ng test` builder because that builder confines discovery to
// `sourceRoot: src/` — tooling specs would never be picked up there. Run via
// `pnpm test:scripts`.
//
// `root` is the web project dir (the config lives in `scripts/`, so `..`) so
// both the `scripts/` migration tooling and the `e2e/proof-gallery/` generator
// specs are discoverable under one runner.

export default defineConfig({
  root: __dirname + '/..',
  test: {
    include: ['scripts/**/*.spec.ts', 'e2e/proof-gallery/**/*.spec.ts'],
    environment: 'node',
    globals: true,
  },
});
