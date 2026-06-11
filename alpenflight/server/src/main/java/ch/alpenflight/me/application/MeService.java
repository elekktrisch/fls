package ch.alpenflight.me.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.referencedata.domain.Language;
import ch.alpenflight.referencedata.domain.LanguageRepository;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated principal into a single read projection for
 * {@code GET /api/v1/me}. Three sources, in priority order:
 *
 * <ol>
 *   <li>The {@code user} row matched on {@code keycloak_sub} (via
 *       {@code users.domain.UserRepository#findActiveByKeycloakSub}) —
 *       supplies {@code id}, {@code clubId}, {@code username}, {@code email},
 *       {@code personId}. Same lookup column as
 *       {@code platform.tenancy.UserPrincipalLookup}; the two overlap
 *       deliberately — both serve the JWT-sub-to-internal-row resolution
 *       seam but project different field sets (and the lookup must stay
 *       JDBC, since it runs in Hibernate's session-open path).</li>
 *   <li>The linked {@code t_person} row (when {@code user.person_id} is set)
 *       — supplies {@code firstName} + {@code lastName}.</li>
 *   <li>JWT claims — fallbacks for {@code username} ({@code preferred_username}),
 *       {@code email}, {@code firstName} ({@code given_name}),
 *       {@code lastName} ({@code family_name}) when no {@code user} / no
 *       {@code t_person} is linked.</li>
 * </ol>
 *
 * <p>All reads are plain JPA finders against cross-tenant aggregates
 * (none of User / Person / Club / Language carries {@code @TenantId}), per
 * ADR 0027 — this service's former {@code JdbcTemplate} join was retired
 * with the J-7 review (PR #215).
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

    // Realm-role catalogue. The same set lives in `users.domain.Role` —
    // kept as a plain String set because this is the wire-boundary filter
    // over raw JWT claim values, not domain authority. (The original
    // reason — Modulith treating `users.domain` as internal — lapsed when
    // the users module went OPEN; this class now imports its repository
    // port.) Drift between the two lists is detected by
    // `RoleVocabularySingleSourceTest` (and the SPA's `AppRole` test), not
    // by sharing a type. Roles outside this set — Keycloak built-ins
    // (`uma_authorization`, `offline_access`, `default-roles-*`) and the
    // `proffix-sync` client role — are dropped at the wire boundary.
    private static final Set<String> KNOWN_REALM_ROLES = Set.of(
            "SYSTEM_ADMINISTRATOR",
            "CLUB_ADMINISTRATOR",
            "FLIGHT_OPERATOR",
            "PILOT",
            "OFFICE_USER",
            "GUEST");

    private final UserRepository users;
    private final PersonRepository persons;
    private final ClubRepository clubs;
    private final LanguageRepository languages;

    public MeService(UserRepository users,
                     PersonRepository persons,
                     ClubRepository clubs,
                     LanguageRepository languages) {
        this.users = users;
        this.persons = persons;
        this.clubs = clubs;
        this.languages = languages;
    }

    @Transactional(readOnly = true)
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
                    claim(jwt, "preferred_username"),
                    // No user row → no Account self-fields. The SPA Account form
                    // renders read-only JWT-claim fallbacks and disables save.
                    null,
                    null,
                    null,
                    null,
                    // No user row → no club context → no homebase.
                    null);
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
                row.username,
                row.friendlyName,
                row.phoneNumber,
                row.languageId,
                row.languageCode,
                row.homebaseLocationId);
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
        UUID subUuid;
        try {
            subUuid = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return users.findActiveByKeycloakSub(subUuid)
                .map(this::project)
                .orElse(null);
    }

    /**
     * Projects the active User row plus its decorations — mirrors the
     * former LEFT JOINs: a missing/soft-deleted Person or Club, or an
     * unset {@code homebase_id}, degrade to null fields, never to an
     * absent row.
     */
    private UserPersonRow project(User user) {
        // A repository-loaded entity always carries its id; the getter is
        // @Nullable only for the not-yet-persisted state.
        UUID userId = Objects.requireNonNull(user.getId(), "persisted User must have an id").value();

        @Nullable UUID personId = user.getPersonId();
        @Nullable Person person =
                personId == null ? null : persons.findActiveById(personId).orElse(null);

        // language_id is NOT NULL with an FK onto the static V2 seed, so the
        // row is always present; stay null-tolerant like the old LEFT JOIN.
        @Nullable Language language = languages.findById(user.getLanguageId()).orElse(null);

        // homebase_id is nullable on t_club (a club may have no homebase
        // set); a soft-deleted club likewise yields no homebase.
        @Nullable Club club = clubs.findActiveById(user.getClubId()).orElse(null);

        return new UserPersonRow(
                userId,
                user.getClubId(),
                user.getUsername(),
                user.getNotificationEmail(),
                personId,
                person == null ? null : person.getFirstname(),
                person == null ? null : person.getLastname(),
                user.getFriendlyName(),
                user.getPhoneNumber(),
                user.getLanguageId(),
                language == null ? null : language.getCode(),
                club == null ? null : club.getHomebaseId());
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
            @Nullable String lastName,
            String friendlyName,
            @Nullable String phoneNumber,
            UUID languageId,
            @Nullable String languageCode,
            @Nullable UUID homebaseLocationId) {}
}
