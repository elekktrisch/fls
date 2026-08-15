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

@Service
public class MeService {

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
                    null,
                    null,
                    null,
                    null,
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

    private UserPersonRow project(User user) {
        UUID userId = Objects.requireNonNull(user.getId(), "persisted User must have an id").value();

        @Nullable UUID personId = user.getPersonId();
        @Nullable Person person =
                personId == null ? null : persons.findActiveById(personId).orElse(null);

        @Nullable Language language = languages.findById(user.getLanguageId()).orElse(null);

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
