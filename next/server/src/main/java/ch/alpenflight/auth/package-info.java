/**
 * S-048 walking-skeleton mock-auth seam. DELETE IN ONE COMMIT when the
 * S-019 / S-020 / S-022 auth chain lands.
 *
 * <p>Profile-gated ({@code mock-auth}) injection of a fixed
 * SYSTEM_ADMINISTRATOR principal so the rest of the SPA + REST surface can
 * exercise real {@code @PreAuthorize} predicates without a working OAuth2
 * resource server. The shape of the {@link
 * org.springframework.security.oauth2.jwt.Jwt} built here mirrors what
 * Keycloak will emit under the real realm — when S-020 swaps the principal
 * source, the predicates / authorities / claim accessors do not move.
 *
 * <p>Files deleted in the rip-out commit (all of this package):
 * {@code MockSecurityConfig.java}, {@code MockAuthenticationFilter.java},
 * {@code package-info.java}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.auth;
