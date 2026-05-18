package ch.alpenflight.clubs.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.WithTenant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC4 + AC5 + AC3 evidence: with the resolver wired and {@code @TenantId} on
 * {@link MemberState}, {@code findAll()} returns only the current tenant's
 * rows; mid-test switch via {@link TenantTestContext#runAs} sees the other
 * tenant's rows; inserts under the sentinel context fail at the
 * {@code fk_member_state_club_id} foreign-key constraint (nil UUID is not
 * present in {@code club}).
 *
 * <p>Per ADR 0021: per-test unique club keys; pre-clean by stable test name;
 * no {@code @AfterEach} cleanup.
 */
class MemberStateTenantIsolationIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a2");
    private static final String TEST_NAME_PREFIX = "IT_MSTI_";
    private static final String TEST_KEY_PREFIX = "IT_M_"; // club_key is VARCHAR(10) — keep prefix tight.

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MemberStateRepository memberStates;

    @BeforeEach
    void seed() {
        cleanupPreviousRun();
        seedClub(CLUB_A, "alpha");
        seedClub(CLUB_B, "bravo");
        TenantTestContext.runAs(CLUB_A, () -> memberStates.save(new MemberState("Active member")));
        TenantTestContext.runAs(CLUB_A, () -> memberStates.save(new MemberState("Suspended")));
        TenantTestContext.runAs(CLUB_B, () -> memberStates.save(new MemberState("Trial flight")));
    }

    @Test
    @WithTenant("019e30c3-2c00-7001-8000-0000000000a1")
    void findAll_under_tenant_A_returns_only_A_rows() {
        assertThat(memberStates.findAll())
                .extracting(MemberState::getName)
                .containsExactlyInAnyOrder("Active member", "Suspended");
    }

    @Test
    @WithTenant("019e30c3-2c00-7001-8000-0000000000a1")
    void runAs_switches_tenant_inside_test() {
        assertThat(memberStates.findAll()).hasSize(2);
        TenantTestContext.runAs(CLUB_B, () ->
                assertThat(memberStates.findAll())
                        .extracting(MemberState::getName)
                        .containsExactly("Trial flight"));
        assertThat(memberStates.findAll()).hasSize(2);
    }

    @Test
    @WithTenant("019e30c3-2c00-7001-8000-0000000000a1")
    void insert_writes_correct_club_id_to_db() {
        MemberState saved = memberStates.save(new MemberState("Honorary"));
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM member_state WHERE id = ?::uuid AND club_id = ?::uuid",
                Integer.class, saved.getId().toString(), CLUB_A.toString());
        assertThat(matches).isEqualTo(1);
    }

    @Test
    void no_tenant_context_yields_empty_findAll() {
        assertThat(memberStates.findAll()).isEmpty();
    }

    @Test
    void no_tenant_context_inserts_fail_at_fk_constraint() {
        assertThatThrownBy(() -> memberStates.save(new MemberState("would-poison")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_member_state_club_id");
    }

    @Test
    void explicit_runUnscoped_inserts_fail_at_fk_constraint() {
        TenantTestContext.runUnscoped(() ->
                assertThatThrownBy(() -> memberStates.save(new MemberState("would-poison-unscoped")))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("fk_member_state_club_id"));
    }

    private void cleanupPreviousRun() {
        jdbc.update("DELETE FROM member_state WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM club WHERE id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
    }

    private void seedClub(UUID id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                """,
                id.toString(),
                TEST_NAME_PREFIX + slug,
                TEST_KEY_PREFIX + slug.charAt(0),
                countryId.toString(),
                clubStateId.toString(),
                TEST_NAME_PREFIX + slug);
    }
}
