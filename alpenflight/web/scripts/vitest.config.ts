import { defineConfig } from 'vitest/config';

// Standalone vitest config for the `scripts/` tooling. Kept separate
// from the Angular `ng test` builder because that builder confines
// discovery to `sourceRoot: src/` — tooling specs would never be
// picked up there. Run via `pnpm test:scripts`.

export default defineConfig({
  test: {
    include: ['**/*.spec.ts'],
    environment: 'node',
    globals: true,
  },
});
