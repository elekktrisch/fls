package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.flight.LocationMapper;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("slow")
class ReferenceLookupResolverIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void rewrites_synthetic_lookup_uuids_to_real_seed_pks() throws Exception {
        LocationMapper mapper = new LocationMapper();

        ObjectNode row = JSON.createObjectNode();
        row.put(locationTypeColumn(), Coercions.legacyIntIdToUuidString(2));
        row.put(elevationColumn(), Coercions.legacyIntIdToUuidString(1));
        row.put(runwayLengthColumn(), Coercions.legacyIntIdToUuidString(2));

        UUID expectedLocationType = seedPk("t_location_type", 2);
        UUID expectedElevation = seedPk("t_elevation_unit_type", 1);
        UUID expectedRunwayLength = seedPk("t_length_unit_type", 2);

        try (Connection connection = dataSource.getConnection();
                ReferenceLookupResolver resolver = new ReferenceLookupResolver(connection)) {
            resolver.rewriteReferenceLookups(mapper, row);
        }

        assertThat(UUID.fromString(row.get(locationTypeColumn()).asText()))
                .as("location_type_id synthetic UUID must resolve to t_location_type.id")
                .isEqualTo(expectedLocationType);
        assertThat(UUID.fromString(row.get(elevationColumn()).asText()))
                .as("elevation_unit_type_id must resolve to t_elevation_unit_type.id")
                .isEqualTo(expectedElevation);
        assertThat(UUID.fromString(row.get(runwayLengthColumn()).asText()))
                .as("runway_length_unit_type_id must resolve to t_length_unit_type.id")
                .isEqualTo(expectedRunwayLength);
        assertThat(expectedLocationType).isNotEqualTo(new UUID(0L, 2L));
    }

    @Test
    void null_lookup_column_is_left_untouched() throws Exception {
        LocationMapper mapper = new LocationMapper();
        ObjectNode row = JSON.createObjectNode();
        row.put(locationTypeColumn(), Coercions.legacyIntIdToUuidString(1));
        row.putNull(elevationColumn());
        row.putNull(runwayLengthColumn());

        try (Connection connection = dataSource.getConnection();
                ReferenceLookupResolver resolver = new ReferenceLookupResolver(connection)) {
            resolver.rewriteReferenceLookups(mapper, row);
        }

        assertThat(row.get(elevationColumn()).isNull()).isTrue();
        assertThat(row.get(runwayLengthColumn()).isNull()).isTrue();
    }

    @Test
    void unknown_legacy_int_id_fails_closed() throws Exception {
        LocationMapper mapper = new LocationMapper();
        ObjectNode row = JSON.createObjectNode();
        row.put(locationTypeColumn(), Coercions.legacyIntIdToUuidString(9999));

        try (Connection connection = dataSource.getConnection();
                ReferenceLookupResolver resolver = new ReferenceLookupResolver(connection)) {
            assertThatThrownBy(() -> resolver.rewriteReferenceLookups(mapper, row))
                    .isInstanceOf(BundleIngestException.class)
                    .hasMessageContaining("legacy_int_id 9999")
                    .extracting(e -> ((BundleIngestException) e).getErrorCode())
                    .isEqualTo(BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK);
        }
    }

    private UUID seedPk(String seedTable, int legacyIntId) {
        return jdbc.queryForObject(
                "SELECT id FROM " + seedTable + " WHERE legacy_int_id = ?",
                UUID.class, legacyIntId);
    }

    private static String locationTypeColumn() {
        return new LocationMapper().referenceLookups().get(0).column();
    }

    private static String elevationColumn() {
        return new LocationMapper().referenceLookups().get(1).column();
    }

    private static String runwayLengthColumn() {
        return new LocationMapper().referenceLookups().get(2).column();
    }
}
