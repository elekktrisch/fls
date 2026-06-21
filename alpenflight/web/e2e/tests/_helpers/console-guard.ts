import { test as base, expect, type Page, type TestInfo } from '@playwright/test';

/**
 * Suite-wide "no uncaught browser errors" guard.
 *
 * Every test that imports this `test` (instead of the raw `@playwright/test`
 * one) fails if the browser emitted a `console.error` or a `pageerror` that no
 * allowlist entry and no per-test opt-out covers. The guard is the standing
 * proof that a screen mounts, renders, and interacts WITHOUT throwing — a
 * regression the green-on-the-happy-path assertions silently miss (a component
 * can satisfy its visible assertions while logging a binding/zoneless error).
 *
 * It runs as an `auto` fixture: the assertion fires in teardown for every test,
 * with no per-spec `afterEach`. Specs that drive their OWN page (a
 * `browser.newContext().newPage()` rather than the injected `page`) opt that
 * page in with `watchConsoleErrors(page)` — the same per-test collector backs
 * both, so the single teardown assertion covers every watched page.
 */

/**
 * Curated benign patterns — framework / third-party noise the app does NOT own
 * and cannot fix. Seed CONSERVATIVELY: an app-caused error belongs in a bug
 * fix, never here. Each entry needs a one-line why. A `string` matches as a
 * substring; a `RegExp` is tested against the full message.
 *
 * NOTE: the `[af-icon]` unregistered-icon `console.error` and `[signup]`
 * authorize-failure log are APP errors the guard exists to catch — they are
 * deliberately absent.
 */
const BENIGN_PATTERNS: readonly (string | RegExp)[] = [
  // The dev server serves no favicon; the browser logs a resource-load error
  // for it on every cold load. Not app behaviour, not fixable here.
  /Failed to load resource.*favicon\.ico/i,
];

function isBenign(message: string): boolean {
  return BENIGN_PATTERNS.some((p) =>
    typeof p === 'string' ? message.includes(p) : p.test(message),
  );
}

/** Per-test bag of collected browser errors + the test's declared opt-out patterns. */
interface ConsoleGuard {
  readonly errors: string[];
  /** Caller-declared patterns for errors a test DELIBERATELY triggers. */
  readonly allowed: (string | RegExp)[];
}

/**
 * The `WeakMap<Page, …>` keys a collector to each watched page so a spec's own
 * `browser.newContext()` page funnels into the SAME per-test bag the auto
 * fixture asserts on. Keyed by `TestInfo` (one bag per running test); cleaned
 * up implicitly when the test object is collected.
 */
const guards = new WeakMap<TestInfo, ConsoleGuard>();

function guardFor(testInfo: TestInfo): ConsoleGuard {
  let g = guards.get(testInfo);
  if (!g) {
    g = { errors: [], allowed: [] };
    guards.set(testInfo, g);
  }
  return g;
}

/**
 * Subscribe `page` to the running test's error collector. The injected `page`
 * is wired automatically by the fixture; call this ONLY for a page from a
 * spec-owned context (`browser.newContext().newPage()`), which the fixture
 * cannot see. Idempotent enough for normal use — call once per page.
 */
export function watchConsoleErrors(page: Page, testInfo: TestInfo): void {
  const g = guardFor(testInfo);
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = `console.error: ${msg.text()}`;
    if (!isBenign(text)) g.errors.push(text);
  });
  page.on('pageerror', (err) => {
    const text = `pageerror: ${err.message}`;
    if (!isBenign(text)) g.errors.push(text);
  });
}

/**
 * Declare error patterns the current test DELIBERATELY triggers (a 403/404/412/
 * 409 / optimistic-concurrency path the app legitimately logs). Matched errors
 * are excluded from the teardown assertion; everything else still fails the
 * test. Call inside the test body, before driving the error path. Prefer a
 * SPECIFIC pattern over a broad one so a real adjacent error still trips.
 */
export function allowConsoleErrors(testInfo: TestInfo, ...patterns: (string | RegExp)[]): void {
  guardFor(testInfo).allowed.push(...patterns);
}

function isAllowed(message: string, allowed: readonly (string | RegExp)[]): boolean {
  return allowed.some((p) => (typeof p === 'string' ? message.includes(p) : p.test(message)));
}

export const test = base.extend<{ consoleGuard: void }>({
  consoleGuard: [
    async ({ page }, use, testInfo) => {
      watchConsoleErrors(page, testInfo);
      await use();

      const g = guardFor(testInfo);
      const unexpected = g.errors.filter((e) => !isAllowed(e, g.allowed));
      expect(
        unexpected,
        `uncaught browser errors (console.error / pageerror) during the test:\n${unexpected.join(
          '\n',
        )}\n\nIf a test DELIBERATELY triggers one, declare it via allowConsoleErrors(testInfo, /pattern/).`,
      ).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
