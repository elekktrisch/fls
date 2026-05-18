// S-048: DELETE WITH PARENT DIRECTORY when S-019/S-020 land. See README.md.

import type { HttpInterceptorFn } from '@angular/common/http';

/**
 * Stamps a placeholder Bearer token on every `/api/v1/**` HttpClient call so
 * the backend's MockAuthenticationFilter sees a token-shaped request. The
 * literal `mock-sysadmin` is a sentinel — the backend filter ignores the
 * value entirely and builds the principal from its hard-coded {@code Jwt}.
 */
export const mockAuthInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.includes('/api/v1/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: 'Bearer mock-sysadmin' } }));
};
