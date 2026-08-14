package ch.alpenflight.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.infra.JpaMemberStateRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantsRunAsIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_TRA_";
    private static final String KEY_PREFIX = "IT_T_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JpaMemberStateRepository memberStates;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }


    @Test
    void runAs_writes_through_to_target_club() {
        TenantTestContext.runAs(clubA, () -> {
            Tenants.runAs(clubB, () -> memberStates.save(new MemberState("Bravo-only status")));
            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_member_state WHERE club_id = ?::uuid AND name = ?",
                    Integer.class, clubB.toString(), "Bravo-only status");
            assertThat(matches)
                    .as("Tenants.runAs(B) must persist with club_id = B even when JWT clubId = A")
                    .isEqualTo(1);
        });
    }

    @Test
    void runAs_does_not_leak_back_to_outer_tenant() {
        TenantTestContext.runAs(clubA, () -> {
            memberStates.save(new MemberState("A-only status"));
            Tenants.runAs(clubB, () -> memberStates.save(new MemberState("B-only status")));
            assertThat(memberStates.findAll())
                    .extracting(MemberState::getName)
                    .as("only A's rows visible after the runAs block returns")
                    .containsExactly("A-only status");
        });
    }

    @Test
    void runAs_reads_under_target_tenant() {
        TenantTestContext.runAs(clubA, () -> {
            Tenants.runAs(clubB, () -> memberStates.save(new MemberState("B-rd")));
            Tenants.runAs(clubB, () ->
                    assertThat(memberStates.findAll())
                            .extracting(MemberState::getName)
                            .containsExactly("B-rd"));
        });
    }

    @Test
    void runAs_works_without_outer_tenant_context() {
        Tenants.runAs(clubA, () -> memberStates.save(new MemberState("solo")));
        assertThat(memberStates.findAll())
                .as("after the runAs block the carrier is empty again — outer reads see NO_TENANT")
                .isEmpty();
    }

    @Test
    void runAs_with_nil_uuid_is_rejected_at_entry_point() {
        TenantTestContext.runAs(clubA, () -> {
            UUID nil = new UUID(0L, 0L);
            assertThatThrownBy(() ->
                    Tenants.runAs(nil, () -> memberStates.save(new MemberState("would-poison"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NO_TENANT");
        });
    }

    @Test
    void runAs_supplier_returns_body_value_under_target_tenant() {
        TenantTestContext.runAs(clubA, () -> {
            Long countUnderB = Tenants.runAs(clubB, () -> {
                memberStates.save(new MemberState("counted-1"));
                memberStates.save(new MemberState("counted-2"));
                return (long) memberStates.findAll().size();
            });
            assertThat(countUnderB).isEqualTo(2);
        });
    }

    @Test
    void test_context_runAs_and_platform_runAs_share_a_carrier() {
        TenantTestContext.runAs(clubA, () -> {
            TenantTestContext.runAs(clubB, () ->
                    Tenants.runAs(clubB, () -> memberStates.save(new MemberState("dual-wrapper"))));
            Tenants.runAs(clubB, () ->
                    assertThat(memberStates.findAll())
                            .extracting(MemberState::getName)
                            .contains("dual-wrapper"));
        });
    }

}
