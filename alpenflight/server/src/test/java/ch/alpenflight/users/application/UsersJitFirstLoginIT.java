package ch.alpenflight.users.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S-169 integration test — JIT user-row materialise on first authenticated
 * request. Pins the contract from the design notes: filter runs after JWT
 * decode, creates one local row per principal, idempotent on subsequent
 * hits, refuses tombstoned principals with 403, no-ops on permitted
 * endpoints and on tokens that don't carry the materialise inputs
 * (sysadmin, malformed claims).
 *
 * <p>Hits {@code GET /api/v1/me} as the smoke endpoint — it's
 * {@code @PreAuthorize("isAuthenticated()")} (no role gate to dance
 * around), already wired, and observably independent of the JIT row state
 * (its projection reads JDBC directly).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class UsersJitFirstLoginIT extends PostgresIntegrationTest {

    private static final UUID CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_EN =
            UUID.fromString("019e2e15-2c00-77d3-8000-0000000007d3");
    private static final UUID LANG_DE =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MeterRegistry meters;

    private final List<UUID> mintedSubs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID sub : mintedSubs) {
            jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?",
                    sub.toString());
            jdbc.update("DELETE FROM t_user WHERE keycloak_sub = ?::uuid", sub.toString());
        }
        mintedSubs.clear();
    }

    @Test
    void firstLogin_freshSub_materialisesOneRow() {
        UUID sub = freshSub();
        String token = mintJitReadyRealm(sub, CLUB, "jit-it-fresh", "Fresh",
                "fresh@example.com", "en");
        double createdBefore = counterValue("users.jit.outcome", "outcome", "created");

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(count).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT username FROM t_user WHERE keycloak_sub = ?::uuid",
                String.class, sub.toString()))
                .isEqualTo("jit-it-fresh");
        assertThat(jdbc.queryForObject(
                "SELECT language_id FROM t_user WHERE keycloak_sub = ?::uuid",
                UUID.class, sub.toString()))
                .as("locale=en maps to the English language row")
                .isEqualTo(LANG_EN);
        assertThat(counterValue("users.jit.outcome", "outcome", "created") - createdBefore)
                .as("created counter increments by exactly one")
                .isEqualTo(1.0);
    }

    @Test
    void secondLogin_reusesExistingRow() {
        UUID sub = freshSub();
        String token = mintJitReadyRealm(sub, CLUB, "jit-it-reuse", "Reuse",
                "reuse@example.com", "en");

        get("/api/v1/me", token);
        UUID firstId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE keycloak_sub = ?::uuid",
                UUID.class, sub.toString());

        get("/api/v1/me", token);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(count).as("second login does not create a second row").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM t_user WHERE keycloak_sub = ?::uuid",
                UUID.class, sub.toString()))
                .isEqualTo(firstId);
    }

    @Test
    void softDeletedRow_returns403_andDoesNotResurrect() {
        UUID sub = freshSub();
        UUID rowId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub, deleted_on)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, now())
                """,
                rowId.toString(), CLUB.toString(), "jit-it-tomb", "Tombstone",
                "tomb@example.com", LANG_DE.toString(), sub.toString());

        String token = mintJitReadyRealm(sub, CLUB, "jit-it-tomb", "Tombstone",
                "tomb@example.com", "de");
        double skippedBefore = counterValue(
                "users.jit.outcome", "outcome", "skipped-deactivated");

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode())
                .as("Soft-delete gate refuses the residual-JWT request")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .as("RFC 7807 problem+json on the refusal")
                .startsWith("application/problem+json");

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(activeCount).as("No fresh row materialised").isEqualTo(0);
        assertThat(counterValue("users.jit.outcome", "outcome", "skipped-deactivated") - skippedBefore)
                .as("skipped-deactivated counter increments")
                .isEqualTo(1.0);
    }

    @Test
    void sysadminToken_noClubIdClaim_passesThroughWithoutRow() {
        UUID sub = freshSub();
        String token = jwts.mint(c -> c
                .subject(sub.toString())
                .claim("preferred_username", "jit-it-sysadmin")
                .claim("given_name", "Sys")
                .claim("email", "sys@example.com")
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid",
                Integer.class, sub.toString());
        assertThat(count).as("Sysadmin operates without a t_user row").isEqualTo(0);
    }

    @Test
    void missingPreferredUsername_passesThroughWithoutRow() {
        UUID sub = freshSub();
        String token = jwts.mint(c -> c
                .subject(sub.toString())
                .claim("clubId", CLUB.toString())
                .claim("given_name", "Given")
                .claim("email", "given@example.com")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
        double skippedBefore = counterValue(
                "users.jit.outcome", "outcome", "skipped-malformed");

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode())
                .as("Request proceeds; downstream PreAuthorize handles role-only auth")
                .isEqualTo(HttpStatus.OK);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid",
                Integer.class, sub.toString());
        assertThat(count).as("No row when JIT cannot build a valid User aggregate").isEqualTo(0);
        assertThat(counterValue("users.jit.outcome", "outcome", "skipped-malformed") - skippedBefore)
                .as("materializer increments skipped-malformed when identity claims are missing")
                .isEqualTo(1.0);
    }

    @Test
    void anonymousActuatorHealth_filterIsNoOp() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/actuator/health")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reInviteAfterSoftDelete_subDetached_jitCreatesFreshRow() {
        // Re-entry contract: after the invite flow has cleared the
        // tombstone's keycloak_sub (releasing it from the partial UNIQUE),
        // a JWT for that sub JIT-materialises a fresh row.
        UUID sub = freshSub();
        UUID tombId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub, deleted_on)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, NULL, now())
                """,
                tombId.toString(), CLUB.toString(), "jit-it-reentry", "Reentry",
                "reentry@example.com", LANG_DE.toString());

        String token = mintJitReadyRealm(sub, CLUB, "jit-it-reentry", "Reentry",
                "reentry@example.com", "de");

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(activeCount).as("Fresh active row created with the same sub").isEqualTo(1);

        Integer tombCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE id = ?::uuid AND deleted_on IS NOT NULL",
                Integer.class, tombId.toString());
        assertThat(tombCount).as("Tombstone preserved as audit history").isEqualTo(1);

        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", tombId.toString());
    }

    @Test
    void sameUsername_freshSub_reconcilesRowInPlace_notTenantLess() {
        // J-2 T-23 durable fix: a principal re-appears under a FRESH KC sub
        // but the SAME preferred_username (KC user recreated / realm
        // re-import / a concurrent first-login whose username-winning row
        // carried a sibling's sub). The first sub's row already holds the
        // username, so the second sub's JIT insert hits
        // ux_user_username_lower_alive (23505). Before the fix the by-sub
        // re-read missed and the principal was left tenant-less forever; now
        // JIT reconciles the row's keycloak_sub to the presenting sub.
        UUID firstSub = freshSub();
        UUID secondSub = freshSub();
        String username = "jit-it-reconcile";

        String firstToken = mintJitReadyRealm(firstSub, CLUB, username, "Recon",
                "reconcile@example.com", "en");
        get("/api/v1/me", firstToken);
        UUID rowId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE keycloak_sub = ?::uuid",
                UUID.class, firstSub.toString());
        assertThat(rowId).as("first login materialised one row").isNotNull();

        // Same username, fresh sub.
        String secondToken = mintJitReadyRealm(secondSub, CLUB, username, "Recon",
                "reconcile@example.com", "en");
        ResponseEntity<String> res = get("/api/v1/me", secondToken);
        assertThat(res.getStatusCode())
                .as("re-login under a fresh sub resolves a tenant — not a tenant-less hang")
                .isEqualTo(HttpStatus.OK);

        // Still exactly one active row for that username; its sub is now the
        // SECOND sub (reconciled in place — no duplicate, no orphan).
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE lower(username) = lower(?) AND deleted_on IS NULL",
                Integer.class, username);
        assertThat(activeCount).as("reconcile re-binds in place; no second row").isEqualTo(1);
        UUID boundSub = jdbc.queryForObject(
                "SELECT keycloak_sub FROM t_user WHERE id = ?::uuid",
                UUID.class, rowId.toString());
        assertThat(boundSub).as("the SAME row now points at the fresh sub").isEqualTo(secondSub);
    }

    @Test
    void sameUsername_differentClub_isNotRebound_failsClosed() {
        // Tenant guard: a username row in ANOTHER club must NOT be rebound —
        // that would silently relocate identity across tenants. The principal
        // stays tenant-less (fail-closed) and no row is mutated.
        // t_user.club_id carries fk_user_club_id → t_club, so the "other"
        // club must be a real row. Create a throwaway one (cleaned up below).
        UUID otherClub = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug,
                                    public_registration_enabled)
                VALUES (?::uuid, 'Other Club', ?, ?::uuid, ?::uuid, ?, false)
                """,
                otherClub.toString(), "OTH" + otherClub.toString().substring(0, 5),
                "019e2e15-2c00-74be-8000-0000000004be",  // CH
                "019e2e15-2c00-7bb8-8000-000000000bb8",  // ACTIVE
                "other-club-" + otherClub);
        UUID residentSub = freshSub();
        UUID rowId = UUID.randomUUID();
        String username = "jit-it-crosstenant";
        // Seed an active row for `username` in `otherClub`. The guard we are
        // testing is the club_id-match in reconcileSubByUsername.
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                rowId.toString(), otherClub.toString(), username, "Resident",
                "resident@example.com", LANG_EN.toString(), residentSub.toString());

        UUID intruderSub = freshSub();
        String token = mintJitReadyRealm(intruderSub, CLUB, username, "Intruder",
                "resident@example.com", "en");
        // /api/v1/me itself is isAuthenticated() so it still 200s; the point
        // is no row is created for the intruder sub and the resident row is
        // untouched.
        get("/api/v1/me", token);

        Integer intruderRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid",
                Integer.class, intruderSub.toString());
        assertThat(intruderRows).as("cross-tenant username row is never rebound to the intruder")
                .isEqualTo(0);
        UUID stillResident = jdbc.queryForObject(
                "SELECT keycloak_sub FROM t_user WHERE id = ?::uuid",
                UUID.class, rowId.toString());
        assertThat(stillResident).as("the other club's row keeps its original sub")
                .isEqualTo(residentSub);

        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", rowId.toString());
        jdbc.update("DELETE FROM t_club WHERE id = ?::uuid", otherClub.toString());
    }

    @Test
    void concurrentFirstLogin_sameSub_yieldsExactlyOneRow() throws Exception {
        UUID sub = freshSub();
        String token = mintJitReadyRealm(sub, CLUB, "jit-it-race", "Race",
                "race@example.com", "en");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Callable<Integer> call = () -> {
                gate.await();
                return get("/api/v1/me", token).getStatusCode().value();
            };
            Future<Integer> f1 = pool.submit(call);
            Future<Integer> f2 = pool.submit(call);
            gate.countDown();
            assertThat(f1.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(f2.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE keycloak_sub = ?::uuid AND deleted_on IS NULL",
                Integer.class, sub.toString());
        assertThat(count)
                .as("Partial UNIQUE on keycloak_sub keeps the race to one survivor")
                .isEqualTo(1);
    }

    @Test
    void partialUniqueOnKeycloakSubIsPresent_raceLoserNet() {
        // The partial UNIQUE is the structural net for the concurrent
        // first-login race (the materializer catches
        // DataIntegrityViolationException and re-reads). Verify it exists
        // with the expected `WHERE keycloak_sub IS NOT NULL` predicate so a
        // future migration accidentally dropping the partial qualifier
        // (turning it into a global UNIQUE that would also block re-invite
        // after softDelete) fails this test.
        Map<String, Object> idx = jdbc.queryForMap(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_user_keycloak_sub'");
        String def = (String) idx.get("indexdef");
        assertThat(def)
                .containsIgnoringCase("t_user")
                .containsIgnoringCase("(keycloak_sub)")
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("keycloak_sub IS NOT NULL");
    }

    private String mintJitReadyRealm(UUID sub, UUID clubId, String username,
                                     String firstName, String email, String locale) {
        return jwts.mintJitReady(sub, clubId, c -> c
                .claim("preferred_username", username)
                .claim("given_name", firstName)
                .claim("email", email)
                .claim("locale", locale)
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var counter = meters.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private UUID freshSub() {
        UUID s = UUID.randomUUID();
        mintedSubs.add(s);
        return s;
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }
}
