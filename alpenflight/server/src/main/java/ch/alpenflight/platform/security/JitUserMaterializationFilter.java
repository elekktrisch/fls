package ch.alpenflight.platform.security;

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
import tools.jackson.databind.ObjectMapper;

public class JitUserMaterializationFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE =
            JitUserMaterializationFilter.class.getName() + ".userId";

    public static final Object ABSENT = new Object() {
        @Override public String toString() { return "USER_ID_ABSENT"; }
    };

    static final URI PROBLEM_TYPE_DEACTIVATED =
            URI.create("urn:alpenflight:problem:user-deactivated");

    private static final String PII_FREE_DEACTIVATED_MESSAGE = "User account is deactivated";

    @SuppressWarnings("unused")
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
            writeDeactivated(response);
            return;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException("Unexpected checked exception from JIT materialise", e);
        }
        Object userIdOrAbsent = (userId != null && userId.isPresent()) ? userId.get() : ABSENT;
        request.setAttribute(USER_ID_ATTRIBUTE, userIdOrAbsent);
        chain.doFilter(request, response);
    }

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

    private void writeDeactivated(HttpServletResponse response) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(PROBLEM_TYPE_DEACTIVATED);
        pd.setTitle(PII_FREE_DEACTIVATED_MESSAGE);
        pd.setDetail(PII_FREE_DEACTIVATED_MESSAGE);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
