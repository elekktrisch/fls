/**
 * OpenAPI specification publication. The live spec at {@code /v3/api-docs} is
 * the source of truth that the SPA's TS codegen (S-004) consumes; closes the
 * legacy R5 enum-drift class structurally.
 *
 * <p>Endpoints are dormant unless the active profile sets
 * {@code springdoc.api-docs.enabled=true} (dev, test) — {@code prod} keeps
 * them at 404 to prevent API-shape disclosure. {@link OpenApiOffByDefaultIT}
 * regression-locks that.
 */
@NullMarked
package ch.alpenflight.platform.openapi;

import org.jspecify.annotations.NullMarked;
