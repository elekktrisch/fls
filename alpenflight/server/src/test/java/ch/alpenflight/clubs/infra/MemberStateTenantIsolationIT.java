package ch.alpenflight.clubs.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class MemberStateTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_MSTI_";
    private static final String CLUB_KEY_PREFIX_KEPT_SHORT_FOR_VARCHAR10 = "IT_M_";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JpaMemberStateRepository memberStates;

    @Autowired
    private ClubRepository clubs;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture = new TwoClubFixture(
                jdbc, clubs, countries, clubStates,
                TEST_NAME_PREFIX, CLUB_KEY_PREFIX_KEPT_SHORT_FOR_VARCHAR10);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        TenantTestContext.runAs(clubA, () -> memberStates.save(new MemberState("Active member")));
        TenantTestContext.runAs(clubA, () -> memberStates.save(new MemberState("Suspended")));
        TenantTestContext.runAs(clubB, () -> memberStates.save(new MemberState("Trial flight")));
    }

    @Test
    void findAll_under_tenant_A_returns_only_A_rows() {
        TenantTestContext.runAs(clubA, () ->
                assertThat(memberStates.findAll())
                        .extracting(MemberState::getName)
                        .containsExactlyInAnyOrder("Active member", "Suspended"));
    }

    @Test
    void runAs_switches_tenant_inside_test() {
        TenantTestContext.runAs(clubA, () -> {
            assertThat(memberStates.findAll()).hasSize(2);
            TenantTestContext.runAs(clubB, () ->
                    assertThat(memberStates.findAll())
                            .extracting(MemberState::getName)
                            .containsExactly("Trial flight"));
            assertThat(memberStates.findAll()).hasSize(2);
        });
    }

    @Test
    void insert_writes_correct_club_id_to_db() {
        MemberState saved = TenantTestContext.runAs(clubA,
                () -> memberStates.save(new MemberState("Honorary")));
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_member_state WHERE id = ?::uuid AND club_id = ?::uuid",
                Integer.class, saved.getId().toString(), clubA.toString());
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

}
