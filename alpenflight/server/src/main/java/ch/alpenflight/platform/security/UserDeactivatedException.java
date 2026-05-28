package ch.alpenflight.platform.security;

/**
 * Raised by {@link JitUserMaterializer#materialize} when the authenticated
 * principal matches a soft-deleted {@code t_user} row. Closes the
 * in-window stale-token gap (≤ 15 min per ADR 0007): an admin's deactivate
 * has no effect on a valid JWT until this gate refuses the request.
 *
 * <p>The {@link JitUserMaterializationFilter} translates this into a 403
 * RFC 7807 response directly — the filter chain runs before any
 * {@code @RestControllerAdvice}, so a global handler can't see it.
 */
public class UserDeactivatedException extends RuntimeException {

    public UserDeactivatedException(String message) {
        super(message);
    }
}
