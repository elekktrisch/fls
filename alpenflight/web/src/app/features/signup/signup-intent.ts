
export type SignupIntent = 'join' | 'migrate';

export const POST_SIGNUP_DEFAULT_PATH = '/join';

export function resolveSignupIntent(raw: string | null | undefined): SignupIntent {
  return raw === 'migrate' ? 'migrate' : 'join';
}

export function postSignupLandingPath(intent: SignupIntent): string {
  switch (intent) {
    case 'join':
      return '/join';
    case 'migrate':
      return '/migrate/start';
  }
}
