package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.users.domain.Role;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RoleVocabularySingleSourceTest {

    private static final Path SESSION_STORE_TS =
            Path.of("../web/src/app/core/session/session.store.ts");

    private static final Path REALM_EXPORT_JSON = Path.of("../auth/realm-export.json");

    private static final Set<String> EXPECTED_NON_APP_REALM_ROLES = Set.of(
            "offline_access",
            "uma_authorization",
            "default-roles-alpenflight",
            "proffix-sync");

    private static Set<String> enumRoleNames() {
        return Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @SuppressWarnings("unchecked")
    void backendFilterSetEqualsRoleEnum() throws Exception {
        Field f = MeService.class.getDeclaredField("KNOWN_REALM_ROLES");
        f.setAccessible(true);
        Set<String> knownRealmRoles = new TreeSet<>((Set<String>) f.get(null));

        assertThat(knownRealmRoles)
                .as("MeService.KNOWN_REALM_ROLES must equal the Role enum exactly "
                        + "(both are the literal wire vocabulary; drift mis-filters JWT roles)")
                .isEqualTo(enumRoleNames());
    }

    @Test
    void frontendAppRoleUnionEqualsRoleEnum() throws IOException {
        Set<String> appRoleMembers = parseAppRoleUnion(readFile(SESSION_STORE_TS));

        assertThat(appRoleMembers)
                .as("AppRole union in session.store.ts must equal the Role enum exactly "
                        + "(a wire role absent in the union becomes an un-typeable string client-side)")
                .isEqualTo(enumRoleNames());
    }

    @Test
    void everyRoleEnumConstantIsADefinedRealmRole() throws IOException {
        Set<String> realmRoleNames = parseRealmRoleNames(readFile(REALM_EXPORT_JSON));

        assertThat(realmRoleNames)
                .as("every Role enum constant must be defined as a realm role in realm-export.json "
                        + "or a token can never carry it and variant routing breaks")
                .containsAll(enumRoleNames());
    }

    @Test
    void realmRolesBeyondTheEnumAreOnlyTheExpectedPlumbing() throws IOException {
        Set<String> realmRoleNames = parseRealmRoleNames(readFile(REALM_EXPORT_JSON));

        Set<String> unexpectedExtras = new TreeSet<>(realmRoleNames);
        unexpectedExtras.removeAll(enumRoleNames());
        unexpectedExtras.removeAll(EXPECTED_NON_APP_REALM_ROLES);

        assertThat(unexpectedExtras)
                .as("realm-export.json defines realm role(s) that are neither in the Role enum "
                        + "nor documented Keycloak/client plumbing %s — mirror it into Role + "
                        + "KNOWN_REALM_ROLES + AppRole, or add it to EXPECTED_NON_APP_REALM_ROLES "
                        + "if it is intentional plumbing", EXPECTED_NON_APP_REALM_ROLES)
                .isEmpty();
    }

    private static String readFile(Path relative) throws IOException {
        if (!Files.exists(relative)) {
            throw new IllegalStateException(
                    "role-vocab guard could not locate " + relative.toAbsolutePath()
                            + " — the test working dir must be the server Gradle root (alpenflight/server). "
                            + "Verify the relative layout ../web and ../auth still holds.");
        }
        return Files.readString(relative, StandardCharsets.UTF_8);
    }

    static Set<String> parseAppRoleUnion(String ts) {
        Matcher decl = Pattern.compile(
                        "export\\s+type\\s+AppRole\\s*=\\s*(.*?);", Pattern.DOTALL)
                .matcher(ts);
        if (!decl.find()) {
            throw new IllegalStateException(
                    "could not find `export type AppRole = …;` in session.store.ts — "
                            + "the FE role union moved or was renamed; update this guard.");
        }
        String body = decl.group(1);
        Matcher members = Pattern.compile("'([A-Z_]+)'").matcher(body);
        Set<String> result = new TreeSet<>();
        while (members.find()) {
            result.add(members.group(1));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("parsed an empty AppRole union from session.store.ts");
        }
        return result;
    }

    static Set<String> parseRealmRoleNames(String json) {
        int rolesIdx = json.indexOf("\"roles\"");
        Matcher realmArray = Pattern.compile("\"realm\"\\s*:\\s*\\[", Pattern.DOTALL)
                .matcher(json);
        if (rolesIdx < 0 || !realmArray.find(rolesIdx)) {
            throw new IllegalStateException(
                    "could not locate roles.realm[] in realm-export.json — structure changed; update this guard.");
        }
        int start = realmArray.end();
        int depth = 1;
        int i = start;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            i++;
        }
        String realmRolesBlock = json.substring(start, i - 1);

        Matcher names = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(realmRolesBlock);
        Set<String> result = new LinkedHashSet<>();
        while (names.find()) {
            result.add(names.group(1));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("parsed zero realm roles from realm-export.json");
        }
        return result;
    }
}
