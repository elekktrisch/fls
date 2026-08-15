package ch.alpenflight.migration.bundle;

import static ch.alpenflight.migration.bundle.ProducerDropReconciliation.AIRCRAFT_NO_MANAGING_CLUB;
import static ch.alpenflight.migration.bundle.ProducerDropReconciliation.PLANNING_DAY_ASSIGNMENT_DUPLICATE;
import static ch.alpenflight.migration.bundle.ProducerDropReconciliation.PLANNING_DAY_DUPLICATE;
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
                new ProducerDropWarning("ARTICLE_DUPLICATE_NUMBER", EntityType.ARTICLE,
                        CLUB_A, UUID.randomUUID(), "duplicate article number"),
                new ProducerDropWarning(PLANNING_DAY_DUPLICATE, EntityType.PLANNING_DAY,
                        CLUB_A, UUID.randomUUID(), "duplicate (ClubId, Day, LocationId)"),
                new ProducerDropWarning(PLANNING_DAY_ASSIGNMENT_DUPLICATE,
                        EntityType.PLANNING_DAY_ASSIGNMENT,
                        CLUB_A, UUID.randomUUID(),
                        "duplicate post-remap (planning_day_id, person, type)"),
                new ProducerDropWarning(RESERVATION_NO_PILOT, EntityType.AIRCRAFT_RESERVATION,
                        null, UUID.randomUUID(), "no pilot"));

        Map<ClubEntity, Long> counts =
                ProducerDropReconciliation.dropCountsByClubAndEntity(warnings);

        assertThat(counts)
                .containsEntry(new ClubEntity(EntityType.AIRCRAFT, CLUB_A.toString()), 2L)
                .containsEntry(
                        new ClubEntity(EntityType.AIRCRAFT_RESERVATION, CLUB_A.toString()), 1L)
                .containsEntry(new ClubEntity(EntityType.AIRCRAFT_RESERVATION, ""), 1L)
                .containsEntry(new ClubEntity(EntityType.PLANNING_DAY, CLUB_A.toString()), 1L)
                .containsEntry(
                        new ClubEntity(EntityType.PLANNING_DAY_ASSIGNMENT, CLUB_A.toString()), 1L);
        assertThat(counts.keySet()).noneMatch(key -> key.entity() == EntityType.ARTICLE);
    }

    @Test
    void rowDropCodesFoldTheKeepFirstDropsButNotTheWholeBundleArticleReject() {
        assertThat(ProducerDropReconciliation.ROW_DROP_CODES)
                .containsExactlyInAnyOrder(
                        AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT, PLANNING_DAY_DUPLICATE,
                        PLANNING_DAY_ASSIGNMENT_DUPLICATE)
                .doesNotContain("ARTICLE_DUPLICATE_NUMBER");
    }
}
