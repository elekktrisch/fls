/**
 * Public-registration HTTP adapter. The endpoints under
 * {@code /api/v1/public/**} are the only ones reachable without a bearer token
 * ({@code SecurityConfig}); the exception handler owns the anonymous error
 * contract (404 unknown slug / 403 registration closed), deliberately with an
 * empty body so a rejection discloses nothing beyond the status.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.publicregistration.web;
