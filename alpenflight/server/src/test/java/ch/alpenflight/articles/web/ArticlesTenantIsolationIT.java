package ch.alpenflight.articles.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.articles.application.ArticleDtos.ArticleCreateRequest;
import ch.alpenflight.articles.application.ArticleDtos.ArticleDetail;
import ch.alpenflight.articles.application.ArticlesService;
import ch.alpenflight.articles.domain.ArticleNotFoundException;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.ArticleId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-layer tenancy properties for the Article aggregate. The Hibernate
 * {@code @TenantId} discriminator filters reads/writes by the resolved
 * tenant; number uniqueness is per-tenant (V3 partial UNIQUE on
 * {@code (operating_club_id, article_number) WHERE deleted_on IS NULL}).
 *
 * <p>HTTP-layer authz + 404-not-403 matrix lives in
 * {@link ArticlesAuthorizationIT}; aggregate-level rules live in the
 * domain tests under {@code articles.domain}.
 */
class ArticlesTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NUMBER_PREFIX = "IT_ARTI_";
    private static final String TEST_KEY_PREFIX = "IT_AR";

    private static final AtomicInteger NUMBER_COUNTER = new AtomicInteger(0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ArticlesService articles;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, TEST_NUMBER_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void tenant_filter_isolates_reads_and_persists_operating_club_id() {
        // The minted club id is runtime, so tenant A is entered via runAs here
        // rather than a method-level @WithTenant literal.
        TenantTestContext.runAs(clubA, () -> {
            ArticleDetail aRow = articles.registerArticle(payload(uniqueNumber()));
            AtomicReference<ArticleDetail> bRowRef = new AtomicReference<>();
            TenantTestContext.runAs(clubB, () ->
                    bRowRef.set(articles.registerArticle(payload(uniqueNumber()))));

            assertThat(articles.listArticles(false))
                    .extracting(li -> li.id().toString())
                    .contains(aRow.id().toString())
                    .doesNotContain(bRowRef.get().id().toString());

            ArticleId bExternal = bRowRef.get().id();
            assertThatThrownBy(() -> articles.getArticle(bExternal))
                    .isInstanceOf(ArticleNotFoundException.class);

            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_article WHERE id = ?::uuid "
                            + "AND operating_club_id = ?::uuid",
                    Integer.class, aRow.id().value().toString(), clubA.toString());
            assertThat(matches).isEqualTo(1);
        });
    }

    @Test
    void same_number_under_two_clubs_does_not_collide() {
        String shared = uniqueNumber();
        TenantTestContext.runAs(clubA, () -> articles.registerArticle(payload(shared)));
        TenantTestContext.runAs(clubB, () -> articles.registerArticle(payload(shared)));

        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM t_article WHERE article_number = ?",
                Integer.class, shared);
        assertThat(matches).isEqualTo(2);
    }

    @Test
    void no_tenant_context_yields_empty_reads() {
        TenantTestContext.runAs(clubA, () ->
                articles.registerArticle(payload(uniqueNumber())));
        assertThat(articles.listArticles(false)).isEmpty();
    }

    @Test
    void no_tenant_context_writes_fail_at_fk_constraint() {
        // No real row carries the nil UUID, so fk_article_operating_club_id
        // rejects the write — the fail-closed half of the @TenantId contract.
        assertThatThrownBy(() -> articles.registerArticle(payload(uniqueNumber())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static ArticleCreateRequest payload(String number) {
        return new ArticleCreateRequest(number, "Glider hour", null, null, true);
    }

    private static String uniqueNumber() {
        return TEST_NUMBER_PREFIX + NUMBER_COUNTER.incrementAndGet();
    }
}
