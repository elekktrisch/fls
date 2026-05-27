package ch.alpenflight.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * JIT-on-first-login port. Implemented in {@code users/application} so the
 * cross-cutting {@link JitUserMaterializationFilter} can call into the
 * Users aggregate without {@code platform} depending on the {@code users}
 * Spring Modulith module.
 *
 * <p>Contract:
 * <ul>
 *   <li>Returns the local {@code t_user.id} for the JWT subject — created
 *       on the call when no active row matches, reused on subsequent
 *       calls. Empty when the JWT shape does not carry the materialise
 *       inputs (sysadmin token without {@code clubId}, malformed
 *       {@code preferred_username} / {@code given_name} / {@code email}).</li>
 *   <li>Throws {@link UserDeactivatedException} when the principal matches
 *       a soft-deleted row (in-window stale-token gate per ADR 0007).</li>
 * </ul>
 */
public interface JitUserMaterializer {

    /** See class javadoc. */
    Optional<UUID> materialize(Jwt jwt);
}
