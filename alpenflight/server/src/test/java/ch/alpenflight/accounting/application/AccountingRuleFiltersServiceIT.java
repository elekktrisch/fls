package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterDetail;
import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.accounting.domain.FilterConfig.MatchList;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountingRuleFiltersServiceIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_ARF_";
    private static final String TEST_KEY_PREFIX = "IT_AR";

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final UUID FILTER_TYPE_RECIPIENT =
            UUID.fromString("019e2e15-2c00-7650-8000-000000004650");
    private static final int LEGACY_RECIPIENT = 10;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountingRuleFiltersService service;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void create_round_trips_every_field_and_assigns_article_target_by_type() {
        TenantTestContext.runAs(clubA, () -> {
            AccountingRuleFilterWriteRequest req = articleRequest(uniqueName());
            AccountingRuleFilterDetail created = service.create(req);

            AccountingRuleFilterDetail loaded = service.getDetail(created.id());

            assertThat(loaded.filterTypeId()).isEqualTo(FILTER_TYPE_FLIGHT_TIME);
            assertThat(loaded.ruleFilterName()).isEqualTo(req.ruleFilterName());
            assertThat(loaded.description()).isEqualTo("desc");
            assertThat(loaded.active()).isTrue();
            assertThat(loaded.stopRuleEngineWhenApplied()).isTrue();
            assertThat(loaded.chargedToClubInternal()).isTrue();
            assertThat(loaded.sortIndicator()).isGreaterThanOrEqualTo(0);

            assertThat(loaded.articleTarget()).isEqualTo("ART-100");
            assertThat(loaded.recipientTarget()).isNull();

            FilterConfig config = loaded.filterConfig();
            assertThat(config.deliveryLineText()).isEqualTo("Landing fee line");
            assertThat(config.recipientName()).isNull();
            assertThat(config.isRuleForGliderFlights()).isTrue();
            assertThat(config.includeThresholdText()).isTrue();
            assertThat(config.thresholdText()).isEqualTo("over 30 min");
            assertThat(config.minFlightTimeInSecondsMatchingValue()).isEqualTo(60);
            assertThat(config.maxFlightTimeInSecondsMatchingValue()).isEqualTo(3600);
            assertThat(config.flightTypeCodes().useAllExcept()).isFalse();
            assertThat(config.flightTypeCodes().matched()).containsExactly("SCH", "PAX");

            assertThat(service.listFilters())
                    .extracting(AccountingRuleFilterDtos.AccountingRuleFilterListItem::id)
                    .contains(created.id());
            assertThat(service.listFilters().stream()
                    .filter(li -> li.id().equals(created.id())).findFirst().orElseThrow().target())
                    .isEqualTo("ART-100 (Landing fee line)");
        });
    }

    @Test
    void recipient_type_assigns_recipient_target_and_clears_article() {
        TenantTestContext.runAs(clubA, () -> {
            AccountingRuleFilterWriteRequest req = new AccountingRuleFilterWriteRequest(
                    FILTER_TYPE_RECIPIENT, LEGACY_RECIPIENT, null, uniqueName(), null,
                    true, false, false,
                    "ART-IGNORED", "ignored delivery",
                    "MEM-42", "Jane Doe",
                    FilterConfig.empty());
            AccountingRuleFilterDetail created = service.create(req);
            AccountingRuleFilterDetail loaded = service.getDetail(created.id());

            assertThat(loaded.recipientTarget()).isEqualTo("MEM-42");
            assertThat(loaded.articleTarget()).isNull();
            assertThat(loaded.filterConfig().recipientName()).isEqualTo("Jane Doe");
            assertThat(loaded.filterConfig().deliveryLineText()).isNull();
            assertThat(service.listFilters().stream()
                    .filter(li -> li.id().equals(created.id())).findFirst().orElseThrow().target())
                    .isEqualTo("Jane Doe (MEM-42)");
        });
    }

    @Test
    void cross_tenant_get_update_delete_surface_not_found() {
        AtomicReference<UUID> bId = new AtomicReference<>();
        TenantTestContext.runAs(clubB, () ->
                bId.set(service.create(articleRequest(uniqueName())).id()));

        TenantTestContext.runAs(clubA, () -> {
            UUID other = bId.get();
            assertThatThrownBy(() -> service.getDetail(other))
                    .isInstanceOf(AccountingRuleFilterNotFoundException.class);
            assertThatThrownBy(() -> service.update(other, updateRequest(uniqueName())))
                    .isInstanceOf(AccountingRuleFilterNotFoundException.class);
            assertThatThrownBy(() -> service.delete(other, null))
                    .isInstanceOf(AccountingRuleFilterNotFoundException.class);
        });
    }

    @Test
    void create_writes_an_audit_row_with_redacted_filter_config() {
        AtomicReference<UUID> id = new AtomicReference<>();
        TenantTestContext.runAs(clubA, () ->
                id.set(service.create(articleRequest(uniqueName())).id()));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, after_state FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND target_entity_id = ?::uuid "
                        + "AND target_entity_type = 'AccountingRuleFilter'",
                clubA.toString(), id.get().toString());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("CREATE");
        String after = String.valueOf(rows.get(0).get("after_state"));
        assertThat(after).contains("[redacted]");
        assertThat(after).doesNotContain("Landing fee line");
        assertThat(after).contains("ruleFilterName");
    }


    private static AccountingRuleFilterWriteRequest articleRequest(String name) {
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false,
                false, false, true,
                "over 30 min", 60, 3600, null, null,
                MatchList.empty(),
                MatchList.empty(),
                new MatchList(false, List.of("SCH", "PAX")),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(),
                "delivery-from-config-overridden", "recipient-from-config-overridden");
        return new AccountingRuleFilterWriteRequest(
                FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, null, name, "desc",
                true, true, true,
                "ART-100", "Landing fee line",
                null, null,
                config);
    }

    private static AccountingRuleFilterWriteRequest updateRequest(String name) {
        return new AccountingRuleFilterWriteRequest(
                FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, null, name, null,
                true, false, false,
                "ART-1", "line", null, null, FilterConfig.empty());
    }

    private static String uniqueName() {
        return TEST_NAME_PREFIX + NAME_COUNTER.incrementAndGet();
    }
}
