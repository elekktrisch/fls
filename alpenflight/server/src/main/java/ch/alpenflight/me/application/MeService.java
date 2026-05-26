package ch.alpenflight.me.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Resolves the authenticated principal into a single read projection for
 * {@code GET /api/v1/me}. Three sources, in priority order:
 *
 * <ol>
 *   <li>The {@code user} row matched on {@code keycloak_sub} — supplies
 *       {@code id}, {@code clubId}, {@code username}, {@code email},
 *       {@code personId}. Same lookup column as
 *       {@code platform.tenancy.UserPrincipalLookup}; the schemas overlap
 *       deliberately — both serve the JWT-sub-to-internal-row resolution
 *       seam but project different field sets.</li>
 *   <li>The linked {@code person} row (when {@code user.person_id} is set)
 *       — supplies {@code firstName} + {@code lastName}.</li>
 *   <li>JWT claims — fallbacks for {@code username} ({@code preferred_username}),
 *       {@code email}, {@code firstName} ({@code given_name}),
 *       {@code lastName} ({@code family_name}) when no {@code user} / no
 *       {@code person} is linked.</li>
 * </ol>
 *
 * <p>Roles are read directly from the JWT's {@code realm_access.roles[]}
 * claim — the same source
 * {@link ch.alpenflight.platform.security.ClubAwareJwtAuthenticationConverter}
 * consumes for the {@code ROLE_*} GrantedAuthority. Reading the JWT
 * directly keeps the service free of Spring Security's {@code Authentication}
 * surface, which simplifies the controller signature and avoids any
 * GrantedAuthority-prefix translation.
 */
@Service
public class MeService {

    // AlpenFlight realm-role catalog (mirrors `alpenflight/auth/realm-export.json`
    // and the FE's AppRole union in `core/session/session.store.ts`). Roles
    // outside this set (Keycloak built-ins like `uma_authorization`,
    // `offline_access`, `default-roles-*`) are dropped at the wire boundary
    // so SPA consumers + downstream stories couple only to the project's
    // own role vocabulary.
    private static final Set<String> KNOWN_REALM_ROLES = Set.of(
            "SYSTEM_ADMINISTRATOR",
            "CLUB_ADMINISTRATOR",
            "FLIGHT_OPERATOR",
            "PILOT",
            "OFFICE_USER",
            "GUEST");

    private static final String SELECT_USER_AND_PERSON = """
            SELECT u.id              AS user_id,
                   u.club_id         AS club_id,
                   u.username        AS username,
                   u.notification_email AS email,
                   u.person_id       AS person_id,
                   p.firstname       AS first_name,
                   p.lastname        AS last_name
            FROM "user" u
            LEFT JOIN person p ON p.id = u.person_id AND p.deleted_on IS NULL
            WHERE u.keycloak_sub = ?::uuid AND u.deleted_on IS NULL
            """;

    private final JdbcTemplate jdbc;

    public MeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MeView resolve(Jwt jwt) {
        List<String> roles = extractRoles(jwt);
        @Nullable UserPersonRow row = loadUserAndPerson(jwt);
        if (row == null) {
            return new MeView(
                    null,
                    null,
                    null,
                    roles,
                    claim(jwt, "given_name"),
                    claim(jwt, "family_name"),
                    claim(jwt, "email"),
                    claim(jwt, "preferred_username"));
        }
        String firstName = row.firstName != null ? row.firstName : claim(jwt, "given_name");
        String lastName = row.lastName != null ? row.lastName : claim(jwt, "family_name");
        return new MeView(
                row.userId,
                row.personId,
                row.clubId,
                roles,
                firstName,
                lastName,
                row.email,
                row.username);
    }

    private static List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(KNOWN_REALM_ROLES::contains)
                .toList();
    }

    private @Nullable UserPersonRow loadUserAndPerson(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            Map<String, Object> row = jdbc.queryForMap(SELECT_USER_AND_PERSON, sub);
            // Casts use requireNonNull for columns the schema (V2) marks
            // NOT NULL (id, club_id, username, notification_email). The
            // JDBC map signature can't carry that through to NullAway.
            return new UserPersonRow(
                    asUuid(row.get("user_id")),
                    asUuid(row.get("club_id")),
                    Objects.requireNonNull((String) row.get("username")),
                    Objects.requireNonNull((String) row.get("email")),
                    asUuidNullable(row.get("person_id")),
                    (String) row.get("first_name"),
                    (String) row.get("last_name"));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static UUID asUuid(@Nullable Object value) {
        if (value instanceof UUID u) {
            return u;
        }
        if (value instanceof String s) {
            return UUID.fromString(s);
        }
        throw new IllegalStateException("Expected UUID column, got: " + value);
    }

    private static @Nullable UUID asUuidNullable(@Nullable Object value) {
        return value == null ? null : asUuid(value);
    }

    private static @Nullable String claim(Jwt jwt, String name) {
        Object v = jwt.getClaim(name);
        return v == null ? null : v.toString();
    }

    private record UserPersonRow(
            UUID userId,
            UUID clubId,
            String username,
            String email,
            @Nullable UUID personId,
            @Nullable String firstName,
            @Nullable String lastName) {}
}
