package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import org.junit.jupiter.api.Test;

/**
 * S-141c — the CLUB row-INSERT must UPSERT onto the provisioning-minted
 * {@code t_club} (reconcile, not collide) AND must never let the bundle write
 * the provisioning-owned synthetic columns. Pure SQL-shape assertions — no
 * Spring / Postgres — so the security invariant is guarded even where the
 * Docker-backed parity IT can't run.
 */
class EntityStreamIngestorClubUpsertTest {

    private final EntityStreamIngestor ingestor = new EntityStreamIngestor(KnownMappers.all());

    @Test
    void club_insert_upserts_on_id_and_overlays_legacy_columns() {
        String sql = ingestor.insertStatementFor(EntityType.CLUB);

        assertThat(sql).startsWith("INSERT INTO t_club (");
        assertThat(sql).contains("ON CONFLICT (id) DO UPDATE SET");
        assertThat(sql).contains("clubname = EXCLUDED.clubname");
        assertThat(sql).contains("address = EXCLUDED.address");
        // id is the conflict target, never a SET assignment.
        assertThat(sql).doesNotContain("id = EXCLUDED.id");
    }

    @Test
    void club_upsert_cannot_touch_provisioning_owned_columns() {
        String sql = ingestor.insertStatementFor(EntityType.CLUB);

        // slug / public_registration_enabled / deployment_id are minted by the
        // provisioning service and are absent from ClubMapper's column set, so
        // the generated UPSERT can never overwrite them — a bundle cannot rename
        // a Club's slug, flip its public registration, or move its deployment.
        assertThat(sql).doesNotContain("slug");
        assertThat(sql).doesNotContain("public_registration_enabled");
        assertThat(sql).doesNotContain("deployment_id");
    }

    @Test
    void non_club_entity_uses_a_plain_insert() {
        String sql = ingestor.insertStatementFor(EntityType.USER);

        assertThat(sql).startsWith("INSERT INTO t_user (");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }
}
