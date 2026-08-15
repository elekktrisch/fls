package ch.alpenflight.accounting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryItem;
import ch.alpenflight.accounting.domain.DeliveryProcessState;
import ch.alpenflight.accounting.domain.DeliveryRecipient;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.DeliveryTestHydrator;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryRepositoryIT extends PostgresIntegrationTest {

    @Autowired private DeliveryRepository deliveries;
    @Autowired private JpaDeliveryRepository jpaDeliveries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private ArticleRepository articles;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLV_", "IT_DLV");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void findActiveById_roundTripsColumnsRecipientStateAndItems() {
        UUID articleId = seedArticle(clubA);
        DeliveryRecipient recipient = new DeliveryRecipient(
                "Petra Pilot", "Petra", "Pilot",
                "Flugplatzstrasse 1", "Hangar 3",
                "8000", "Zürich", "Schweiz", "M-4711");

        UUID id = TenantTestContext.runAs(clubA, () -> {
            DeliveryItem item = DeliveryTestHydrator.item(
                    clubA, 1, articleId, "ART-FT", "first 30min",
                    new BigDecimal("0.5000"), new BigDecimal("120.0000"), 10, "Min");
            Delivery delivery = DeliveryTestHydrator.delivery(
                    DeliveryProcessState.BOOKED, "INV-2026-001", 90L, recipient, List.of(item));
            return jpaDeliveries.save(delivery).getId();
        });

        TenantTestContext.runAs(clubA, () -> {
            Delivery reloaded = deliveries.findActiveById(id).orElseThrow();
            assertThat(reloaded.getOperatingClubId()).isEqualTo(clubA);
            assertThat(reloaded.getProcessState()).isEqualTo(DeliveryProcessState.BOOKED);
            assertThat(reloaded.getDeliveryNumber()).isEqualTo("INV-2026-001");
            assertThat(reloaded.getBatchId()).isEqualTo(90L);
            assertThat(reloaded.getRecipient()).isEqualTo(recipient);
            assertThat(reloaded.getItems())
                    .singleElement()
                    .satisfies(line -> {
                        assertThat(line.getOperatingClubId()).isEqualTo(clubA);
                        assertThat(line.getPosition()).isEqualTo(1);
                        assertThat(line.getArticleId()).isEqualTo(articleId);
                        assertThat(line.getArticleNumber()).isEqualTo("ART-FT");
                        assertThat(line.getItemText()).isEqualTo("first 30min");
                        assertThat(line.getQuantity()).isEqualByComparingTo("0.5000");
                        assertThat(line.getUnitPrice()).isEqualByComparingTo("120.0000");
                        assertThat(line.getDiscountInPercent()).isEqualTo(10);
                        assertThat(line.getUnitTypeCode()).isEqualTo("Min");
                    });
            return null;
        });
    }

    @Test
    void findActivePage_ordersByBatchDescThenRecipientAsc() {
        UUID highest = hydrateAndSaveReadOnlyDelivery(clubA, 30L, recipientNamed("Zulu"));
        UUID middle = hydrateAndSaveReadOnlyDelivery(clubA, 20L, recipientNamed("Mike"));
        UUID unbatchedAlpha = hydrateAndSaveReadOnlyDelivery(clubA, 0L, recipientNamed("Alpha"));
        UUID unbatchedBravo = hydrateAndSaveReadOnlyDelivery(clubA, 0L, recipientNamed("Bravo"));

        TenantTestContext.runAs(clubA, () -> {
            assertThat(deliveries.countActive()).isEqualTo(4);
            assertThat(deliveries.findActivePage(PageRequest.of(0, 10)))
                    .extracting(Delivery::getId)
                    .as("batch desc, then recipient lastname asc within a batch — the within-batch "
                            + "ordering rides the batch_id=0 rows, the only ones ux_dlv_club_batch_partial "
                            + "exempts from per-club batch uniqueness")
                    .containsExactly(highest, middle, unbatchedAlpha, unbatchedBravo);

            assertThat(deliveries.findActivePage(PageRequest.of(1, 2)))
                    .extracting(Delivery::getId)
                    .as("second page of two")
                    .containsExactly(unbatchedAlpha, unbatchedBravo);
            return null;
        });
    }

    @Test
    void findActiveById_isTenantScoped() {
        UUID bRow = hydrateAndSaveReadOnlyDelivery(clubB, 5L, recipientNamed("Bravo"));

        TenantTestContext.runAs(clubA, () -> {
            assertThat(deliveries.findActiveById(bRow))
                    .as("club A cannot load club B's delivery by id (cross-tenant 404 foundation)")
                    .isEmpty();
            return null;
        });
        TenantTestContext.runAs(clubB, () -> {
            assertThat(deliveries.findActiveById(bRow))
                    .as("club B loads its own delivery")
                    .isPresent();
            return null;
        });
    }

    private UUID hydrateAndSaveReadOnlyDelivery(UUID clubId, long batchId,
                                                DeliveryRecipient recipient) {
        return TenantTestContext.runAs(clubId, () -> {
            Delivery delivery = DeliveryTestHydrator.delivery(
                    DeliveryProcessState.PREPARED, null, batchId, recipient, List.of());
            return jpaDeliveries.save(delivery).getId();
        });
    }

    private static DeliveryRecipient recipientNamed(String lastname) {
        return new DeliveryRecipient(lastname, "First", lastname,
                null, null, null, null, null, null);
    }

    private UUID seedArticle(UUID clubId) {
        return TenantTestContext.runAs(clubId, () -> {
            Article article = Article.register(
                    "ART-FT-" + UUID.randomUUID().toString().substring(0, 8),
                    "Flight time", null, null, true);
            return articles.save(article).getId().value();
        });
    }
}
