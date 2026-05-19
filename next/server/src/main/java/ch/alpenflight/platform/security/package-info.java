/**
 * Cross-cutting security configuration: the production OAuth2 resource
 * server {@link org.springframework.security.web.SecurityFilterChain}, the
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder} wired against
 * the configured issuer's JWKS (per ADR 0007), and the
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter}
 * that maps {@code realm_access.roles[]} into {@code ROLE_*} authorities
 * for method-level {@code @PreAuthorize} predicates.
 *
 * <p>The canonical role-gate matrix lives on {@code ClubsController}
 * (S-026). New controllers cite {@code CONVENTIONS.md §Authorization
 * patterns} and copy the predicate shape that fits their access semantics.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.security;
