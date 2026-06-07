/**
 * Shared-kernel web helpers. {@link ch.alpenflight.platform.web.ProblemResponses}
 * centralizes the RFC 7807 {@code application/problem+json} response builder and
 * the generic {@code 400 bad-request} factory every per-resource
 * {@code @RestControllerAdvice} repeated verbatim.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.web;
