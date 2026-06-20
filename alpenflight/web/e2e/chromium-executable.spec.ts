import { describe, expect, it } from 'vitest';
import {
  resolveChromiumExecutablePath,
  chromiumLaunchArgs,
  SYSTEM_CHROMIUM_PATHS,
} from './chromium-executable';

describe('resolveChromiumExecutablePath', () => {
  const noFiles = () => false;

  it('prefers the env override when set', () => {
    const path = resolveChromiumExecutablePath(
      { PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH: '/custom/chromium' },
      noFiles,
    );
    expect(path).toBe('/custom/chromium');
  });

  it('ignores a blank/whitespace env override and probes the filesystem', () => {
    const path = resolveChromiumExecutablePath(
      { PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH: '   ' },
      (p) => p === '/usr/bin/chromium',
    );
    expect(path).toBe('/usr/bin/chromium');
  });

  it('returns the first existing apk chromium path', () => {
    const path = resolveChromiumExecutablePath(
      {},
      (p) => p === '/usr/bin/chromium' || p === '/usr/bin/chromium-browser',
    );
    // probe order: /usr/lib/chromium/chromium first, then /usr/bin/chromium
    expect(path).toBe('/usr/bin/chromium');
  });

  it('honours the documented probe order (lib before bin)', () => {
    const path = resolveChromiumExecutablePath({}, () => true);
    expect(path).toBe(SYSTEM_CHROMIUM_PATHS[0]);
  });

  it('returns undefined when no override and no chromium present (CI fallback)', () => {
    expect(resolveChromiumExecutablePath({}, noFiles)).toBeUndefined();
  });
});

describe('chromiumLaunchArgs', () => {
  it('adds the system-chromium stability flags only when a system executable is resolved', () => {
    expect(chromiumLaunchArgs('/usr/bin/chromium')).toEqual([
      '--no-sandbox',
      '--disable-dev-shm-usage',
    ]);
  });

  it('adds no args when falling back to the bundled browser (CI)', () => {
    expect(chromiumLaunchArgs(undefined)).toEqual([]);
  });
});
