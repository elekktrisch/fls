/**
 * Cross-cutting security configuration kept past the S-019/S-020 rip-out.
 * Houses the default {@link org.springframework.security.web.SecurityFilterChain}
 * baseline (no-auth-yet permissive for public endpoints, deny for /api/v1/**)
 * and the {@link
 * org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter}
 * the real OAuth2 resource server will plug in.
 *
 * <p>The S-048 walking-skeleton {@code mock-auth} layer lives in
 * {@code ch.alpenflight.auth} and is deleted in one commit when the real
 * auth chain lands. This package stays untouched.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.security;
