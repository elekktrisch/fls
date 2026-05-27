package ch.alpenflight.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after JWT decode + {@code SecurityContextHolder} population, before
 * controller dispatch. Translates a Keycloak realm-token principal into a
 * local {@code t_user} row when one doesn't already exist (JIT) — the
 * other half of S-052's KC-driven invite flow, which seeds the local
 * projection eagerly. Both flows depend on a single local row per
 * principal for tenant-scoped operations.
 *
 * <p>Short-circuits, in order:
 * <ol>
 *   <li>No {@link JwtAuthenticationToken} on the security context (permitted
 *       endpoints, sysadmin-but-not-yet-authenticated paths) → forward
 *       untouched.</li>
 *   <li>JWT lacks parseable {@code sub} / {@code clubId} (sysadmin per
 *       S-022, federated baseline per S-134) → forward untouched.</li>
 *   <li>Delegate to the {@link JitUserMaterializer} port. The
 *       implementation in {@code users.application} handles the existing-row
 *       fast path + the JIT insert + the soft-delete gate inside a
 *       {@code REQUIRES_NEW} transaction.</li>
 * </ol>
 *
 * <p>On {@link UserDeactivatedException} the filter writes RFC 7807
 * problem+json directly — the chain is upstream of every
 * {@code @RestControllerAdvice}, so a global handler can't see it.
 */
public class JitUserMaterializationFilter extends OncePerRequestFilter {

    /**
     * Sentinel for "JIT decided no row applies to this principal" — set as
     * a request attribute so downstream lookups can short-circuit without
     * re-running the JWT-claim shape check.
     */
    public static final String USER_ID_ATTRIBUTE =
            JitUserMaterializationFilter.class.getName() + ".userId";

    public static final Object ABSENT = new Object() {
        @Override public String toString() { return "USER_ID_ABSENT"; }
    };

    static final URI PROBLEM_TYPE_DEACTIVATED =
            URI.create("urn:alpenflight:problem:user-deactivated");

    private static final Logger LOG = LoggerFactory.getLogger(JitUserMaterializationFilter.class);

    private final JitUserMaterializer materializer;
    private final ObjectMapper objectMapper;
    private final Timer lookupTimer;

    public JitUserMaterializationFilter(JitUserMaterializer materializer,
                                        ObjectMapper objectMapper,
                                        MeterRegistry meters) {
        this.materializer = materializer;
        this.objectMapper = objectMapper;
        this.lookupTimer = Timer.builder("users.jit.lookup")
                .description("End-to-end materialise pass — claim check + lookup + optional insert")
                .register(meters);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = jwtAuth.getToken();
        if (!shouldMaterialise(jwt)) {
            request.setAttribute(USER_ID_ATTRIBUTE, ABSENT);
            chain.doFilter(request, response);
            return;
        }
        Optional<UUID> userId;
        try {
            userId = lookupTimer.recordCallable(() -> materializer.materialize(jwt));
        } catch (UserDeactivatedException e) {
            writeDeactivated(response, e);
            return;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException("Unexpected checked exception from JIT materialise", e);
        }
        if (userId == null) {
            userId = Optional.empty();
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId.<Object>map(uuid -> (Object) uuid).orElse(ABSENT));
        chain.doFilter(request, response);
    }

    /**
     * Pure JWT-claim predicate — DB-free. Tested in
     * {@code JitUserMaterializerShouldMaterialiseTest}. Aligns with skip
     * conditions 1+2 from the design notes; condition 3 (existing-row
     * lookup) lives in the materializer impl.
     */
    public static boolean shouldMaterialise(@Nullable Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        if (!isUuid(jwt.getSubject())) {
            return false;
        }
        return isUuid(jwt.getClaimAsString("clubId"));
    }

    private static boolean isUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void writeDeactivated(HttpServletResponse response, UserDeactivatedException e)
            throws IOException {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(PROBLEM_TYPE_DEACTIVATED);
        pd.setTitle("User account is deactivated");
        pd.setDetail(e.getMessage());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), pd);
        LOG.info("JIT refused deactivated principal — soft-delete gate fired");
    }
}
