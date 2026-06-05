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

/**
 * Role-vocabulary single-source cross-check (S-165 open question, ridden on J-3).
 *
 * <p>The AlpenFlight realm-role vocabulary is hand-maintained in (at least) FOUR
 * places that must stay in agreement — J-3's {@code /start} variant routing and
 * every {@code @PreAuthorize}/{@code isClubAdmin} gate read exactly this vocabulary,
 * so silent drift mis-routes users or opens an authz hole:
 *
 * <ol>
 *   <li>{@link Role} — the canonical backend enum (wire-format == enum name).</li>
 *   <li>{@code MeService.KNOWN_REALM_ROLES} — the filter set that pre-strips JWT
 *       realm roles before the {@code /api/v1/me} wire boundary. Duplicated as a
 *       {@code Set<String>} (not the enum) because Spring Modulith treats
 *       {@code users.domain} as module-internal — see the field's own comment.</li>
 *   <li>{@code AppRole} — the SPA union in {@code web/.../session.store.ts}.</li>
 *   <li>{@code realm-export.json} — the Keycloak realm's {@code roles.realm[].name}.</li>
 * </ol>
 *
 * <h2>Intended contract (encoded below — NOT a blind all-equal)</h2>
 * <ul>
 *   <li><b>{@code Role} enum == {@code KNOWN_REALM_ROLES}</b> — <i>exact equality</i>.
 *       These are the two most-coupled copies (both backend, both the literal
 *       wire vocabulary); a difference is always a bug.</li>
 *   <li><b>{@code Role} enum == {@code AppRole} union</b> — <i>exact equality</i>.
 *       The SPA mirrors the wire enum 1:1 (the OpenAPI snapshot projects {@code Role}
 *       as a string enum and the SPA filters anything unknown). The FE deliberately
 *       carries the full set (it role-gates on a subset like CLUB_ADMINISTRATOR /
 *       SYSTEM_ADMINISTRATOR, but the union must still enumerate every wire value or
 *       a future role silently becomes an un-typeable string).</li>
 *   <li><b>{@code Role} enum &sube; realm-export realm roles</b> — <i>subset</i>.
 *       Every application role MUST exist as a Keycloak realm role (else a token can
 *       never carry it). The realm additionally defines plumbing roles
 *       ({@code offline_access}, {@code uma_authorization}, {@code default-roles-*},
 *       {@code proffix-sync}) that are deliberately NOT in the enum — they are realm
 *       wiring, stripped at the wire boundary. Those extras are the EXPECTED, asserted
 *       difference; any OTHER non-plumbing realm role not in the enum is flagged.</li>
 * </ul>
 *
 * <p>Pure JUnit (no Spring context): reads the enum + reflects the private filter
 * set, and parses the two cross-repo files via relative paths. The server Gradle
 * project root (== the test working directory, see build.gradle.kts) is
 * {@code alpenflight/server}, so the SPA file resolves at {@code ../web/...} and the
 * realm export at {@code ../auth/...} — the same layout CI checks out.
 */
class RoleVocabularySingleSourceTest {

    /** Relative to the server Gradle project dir (= test working dir). */
    private static final Path SESSION_STORE_TS =
            Path.of("../web/src/app/core/session/session.store.ts");

    private static final Path REALM_EXPORT_JSON = Path.of("../auth/realm-export.json");

    /**
     * Keycloak built-in / client plumbing roles that live in the realm export but
     * are deliberately excluded from {@link Role} — they are realm wiring, not
     * application authority, and are stripped at the wire boundary (see Role javadoc
     * + MeService.KNOWN_REALM_ROLES comment).
     */
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
        // CONTRACT: exact equality. KNOWN_REALM_ROLES is a hand-copied mirror of the
        // Role enum (kept separate only for the Modulith boundary). Any drift here
        // means /me silently drops or admits a role the rest of the app doesn't expect.
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
        // CONTRACT: exact equality. The SPA AppRole union must enumerate every wire
        // value 1:1 (the OpenAPI snapshot projects Role as a string enum).
        Set<String> appRoleMembers = parseAppRoleUnion(readFile(SESSION_STORE_TS));

        assertThat(appRoleMembers)
                .as("AppRole union in session.store.ts must equal the Role enum exactly "
                        + "(a wire role absent in the union becomes an un-typeable string client-side)")
                .isEqualTo(enumRoleNames());
    }

    @Test
    void everyRoleEnumConstantIsADefinedRealmRole() throws IOException {
        // CONTRACT: enum ⊆ realm realm-roles. Every application role must exist as a
        // Keycloak realm role or a token can never carry it (variant routing breaks).
        Set<String> realmRoleNames = parseRealmRoleNames(readFile(REALM_EXPORT_JSON));

        assertThat(realmRoleNames)
                .as("every Role enum constant must be defined as a realm role in realm-export.json")
                .containsAll(enumRoleNames());
    }

    @Test
    void realmRolesBeyondTheEnumAreOnlyTheExpectedPlumbing() throws IOException {
        // CONTRACT: the realm export may carry MORE roles than the enum, but only the
        // documented Keycloak/client plumbing. Catches an app role added to Keycloak
        // (e.g. a new "TREASURER") that nobody mirrored into the enum/filter/SPA.
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
        // Fail loudly with the resolved absolute path if the cross-repo layout shifts,
        // rather than silently passing on an empty parse.
        if (!Files.exists(relative)) {
            throw new IllegalStateException(
                    "role-vocab guard could not locate " + relative.toAbsolutePath()
                            + " — the test working dir must be the server Gradle root (alpenflight/server). "
                            + "Verify the relative layout ../web and ../auth still holds.");
        }
        return Files.readString(relative, StandardCharsets.UTF_8);
    }

    /**
     * Parses {@code export type AppRole = 'A' | 'B' | …;} into its string-literal
     * members. Tolerant of formatting (one-per-line or inline) and a leading pipe.
     */
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

    /**
     * Parses the {@code roles.realm[]} array of the Keycloak realm export and returns
     * the {@code name} of every realm role. The export is machine-generated and stable;
     * a scoped regex over the realm-roles block keeps this dependency-free.
     */
    static Set<String> parseRealmRoleNames(String json) {
        // Narrow to the realm-roles array: "realm": [ … ] inside the top-level "roles".
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
