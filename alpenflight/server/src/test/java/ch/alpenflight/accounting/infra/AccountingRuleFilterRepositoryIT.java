package ch.alpenflight.accounting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.AccountingRuleFilterRepository;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level proof of the J-8 T-04 tenant-scoped, soft-delete-filtered
 * finders + the next-{@code sort_indicator} helper, driven through the real
 * {@code @TenantId} discriminator via {@link Tenants#runAs}.
 *
 * <p>Seeding is ADR-0027-clean: the two clubs come from the
 * {@link TwoClubFixture} production-create path (minted ids) and every
 * AccountingRuleFilter row is created via {@link AccountingRuleFilter#create}
 * + {@link AccountingRuleFilterRepository#save} — no raw-JDBC seed of the
 * aggregate. The only JDBC is resolving the V4-seeded filter-type FK
 * (reference data, not the aggregate-under-test).
 */
class AccountingRuleFilterRepositoryIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_ARF_";
    private static final String KEY_PREFIX = "IT_AF_";

    @Autowired private AccountingRuleFilterRepository filters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private final Clock clock = Clock.systemUTC();

    private UUID clubA;
    private UUID clubB;
    private UUID filterTypeId;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();

        // V4-seeded reference row (RECIPIENT, legacy_int_id=10) — the FK target.
        filterTypeId = jdbc.queryForObject(
                "SELECT id FROM t_accounting_rule_filter_type WHERE legacy_int_id = 10",
                UUID.class);
    }

    @Test
    void list_returns_only_callers_tenant_rows_ordered_by_sort_indicator() {
        // Two club-A rows (sort 1 then 0) + one club-B row.
        UUID first = createFilter(clubA, "A second", 1);
        UUID second = createFilter(clubA, "A first", 0);
        createFilter(clubB, "B row", 0);

        Tenants.runAs(clubA, () -> {
            assertThat(filters.findAllActiveOrderedBySort())
                    .as("club A sees only its own rows, ordered by sort_indicator asc")
                    .extracting(AccountingRuleFilter::getId)
                    .containsExactly(second, first);
            return null;
        });
    }

    @Test
    void findActiveById_is_tenant_scoped() {
        UUID bRow = createFilter(clubB, "B private", 0);

        Tenants.runAs(clubA, () -> {
            assertThat(filters.findActiveById(bRow))
                    .as("club A cannot load club B's filter by id (cross-tenant 404 foundation)")
                    .isEmpty();
            return null;
        });
        Tenants.runAs(clubB, () -> {
            assertThat(filters.findActiveById(bRow))
                    .as("club B loads its own filter")
                    .isPresent();
            return null;
        });
    }

    @Test
    void soft_deleted_rows_are_excluded_and_next_sort_indicator_advances() {
        UUID alive = createFilter(clubA, "Alive", 0);
        UUID doomed = createFilter(clubA, "Doomed", 1);

        Tenants.runAs(clubA, () -> {
            // Two alive rows → next slot is 2.
            assertThat(filters.nextSortIndicator())
                    .as("next sort indicator = max(1) + 1")
                    .isEqualTo(2);

            AccountingRuleFilter row = filters.findActiveById(doomed).orElseThrow();
            row.softDelete(null, clock);
            filters.save(row);
            filters.flush();

            assertThat(filters.findAllActiveOrderedBySort())
                    .as("soft-deleted row excluded from the active list")
                    .extracting(AccountingRuleFilter::getId)
                    .containsExactly(alive);
            assertThat(filters.findActiveById(doomed))
                    .as("soft-deleted row not loadable")
                    .isEmpty();
            // The deleted row's sort=1 no longer counts → next slot drops to 1.
            assertThat(filters.nextSortIndicator())
                    .as("next sort indicator ignores soft-deleted rows")
                    .isEqualTo(1);
            return null;
        });
    }

    /**
     * Creates one filter under {@code clubId} via the production
     * {@code create} → {@code save} path, stamping the given sort position.
     */
    private UUID createFilter(UUID clubId, String name, int sort) {
        return Tenants.runAs(clubId, () -> {
            AccountingRuleFilter arf = AccountingRuleFilter.create(
                    filterTypeId, name, null, null,
                    true, false, false, null, null, FilterConfig.empty());
            arf.assignSortIndicator(sort);
            AccountingRuleFilter saved = filters.save(arf);
            UUID id = saved.getId();
            assertThat(id).isNotNull();
            return id;
        });
    }
}
