package ch.alpenflight.migration.bundle;

import static ch.alpenflight.migration.bundle.ProducerDropReconciliation.AIRCRAFT_NO_MANAGING_CLUB;
import static ch.alpenflight.migration.bundle.ProducerDropReconciliation.RESERVATION_NO_PILOT;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.ProducerDropReconciliation.ClubEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProducerDropReconciliationTest {

    private static final UUID CLUB_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void foldsOnlyRowDropCodesGroupedByClubAndEntity() {
        List<ProducerDropWarning> warnings = List.of(
                new ProducerDropWarning(AIRCRAFT_NO_MANAGING_CLUB, EntityType.AIRCRAFT,
                        CLUB_A, UUID.randomUUID(), "owner has no club"),
                new ProducerDropWarning(AIRCRAFT_NO_MANAGING_CLUB, EntityType.AIRCRAFT,
                        CLUB_A, UUID.randomUUID(), "owner has no club"),
                new ProducerDropWarning(RESERVATION_NO_PILOT, EntityType.AIRCRAFT_RESERVATION,
                        CLUB_A, UUID.randomUUID(), "no pilot"),
                // Whole-bundle reject — never folded into row counts.
                new ProducerDropWarning("ARTICLE_DUPLICATE_NUMBER", EntityType.ARTICLE,
                        CLUB_A, UUID.randomUUID(), "duplicate article number"),
                // A row-scoped drop with no Club context keys under the empty-string
                // bucket, matching the diff engine's null-Club convention.
                new ProducerDropWarning(RESERVATION_NO_PILOT, EntityType.AIRCRAFT_RESERVATION,
                        null, UUID.randomUUID(), "no pilot"));

        Map<ClubEntity, Long> counts =
                ProducerDropReconciliation.dropCountsByClubAndEntity(warnings);

        assertThat(counts)
                .containsEntry(new ClubEntity(EntityType.AIRCRAFT, CLUB_A.toString()), 2L)
                .containsEntry(
                        new ClubEntity(EntityType.AIRCRAFT_RESERVATION, CLUB_A.toString()), 1L)
                .containsEntry(new ClubEntity(EntityType.AIRCRAFT_RESERVATION, ""), 1L);
        assertThat(counts.keySet()).noneMatch(key -> key.entity() == EntityType.ARTICLE);
    }

    @Test
    void rowDropCodesExcludeTheArticleDuplicateRejectCode() {
        assertThat(ProducerDropReconciliation.ROW_DROP_CODES)
                .containsExactlyInAnyOrder(AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT)
                .doesNotContain("ARTICLE_DUPLICATE_NUMBER");
    }
}
