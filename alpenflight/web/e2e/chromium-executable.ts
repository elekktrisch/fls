import { existsSync } from 'node:fs';


export const SYSTEM_CHROMIUM_PATHS = [
  '/usr/lib/chromium/chromium',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
] as const;

export function resolveChromiumExecutablePath(
  env: NodeJS.ProcessEnv = process.env,
  fileExists: (path: string) => boolean = existsSync,
): string | undefined {
  const override = env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'];
  if (override && override.trim().length > 0) {
    return override;
  }
  for (const candidate of SYSTEM_CHROMIUM_PATHS) {
    if (fileExists(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

export function chromiumLaunchArgs(executablePath: string | undefined): string[] {
  return executablePath ? ['--no-sandbox', '--disable-dev-shm-usage'] : [];
}
