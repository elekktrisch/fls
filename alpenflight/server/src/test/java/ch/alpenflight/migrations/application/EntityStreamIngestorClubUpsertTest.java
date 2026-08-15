package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import org.junit.jupiter.api.Test;

class EntityStreamIngestorClubUpsertTest {

    private final EntityStreamIngestor ingestor = new EntityStreamIngestor(KnownMappers.all());

    @Test
    void club_insert_upserts_on_id_and_overlays_legacy_columns() {
        String sql = ingestor.insertStatementFor(EntityType.CLUB);

        assertThat(sql).startsWith("INSERT INTO t_club (");
        assertThat(sql).contains("ON CONFLICT (id) DO UPDATE SET");
        assertThat(sql).contains("clubname = EXCLUDED.clubname");
        assertThat(sql).contains("address = EXCLUDED.address");
        assertThat(sql)
                .as("id is the conflict target, never a SET assignment")
                .doesNotContain("id = EXCLUDED.id");
    }

    @Test
    void club_upsert_cannot_touch_provisioning_owned_columns() {
        String sql = ingestor.insertStatementFor(EntityType.CLUB);

        assertThat(sql)
                .as("slug is minted by provisioning — a bundle cannot rename a Club's slug")
                .doesNotContain("slug");
        assertThat(sql)
                .as("a bundle cannot flip a Club's public registration")
                .doesNotContain("public_registration_enabled");
        assertThat(sql)
                .as("a bundle cannot move a Club to another deployment")
                .doesNotContain("deployment_id");
    }

    @Test
    void non_club_entity_uses_a_plain_insert() {
        String sql = ingestor.insertStatementFor(EntityType.USER);

        assertThat(sql).startsWith("INSERT INTO t_user (");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }
}
