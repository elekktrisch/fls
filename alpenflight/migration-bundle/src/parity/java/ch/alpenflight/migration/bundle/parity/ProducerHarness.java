package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Producer side of the round-trip: invoke {@link Mapper#writeNdjson} once
 * per row over the legacy {@link Connection}, gather per-entity NDJSON
 * streams, and emit a {@code tar.gz} envelope via {@link BundleStream}.
 *
 * <p>The {@code SELECT} per entity is hand-curated against the legacy table
 * name + the legacy column list the mapper reads. {@link MapperLegacyBindings}
 * holds the binding so it lives alongside the mapper rather than scattered.
 *
 * <p><strong>In-process producer is the temporary affordance.</strong>
 * Until {@code :migration-tool:shadowJar} (S-139) lands, this class wires
 * the producer side directly. The sibling task {@code S-139a} swaps in
 * {@code ProcessBuilder} invocation once the JAR exists.
 */
public final class ProducerHarness {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection legacyConnection;
    private final List<Mapper> mappers;

    public ProducerHarness(Connection legacyConnection, List<Mapper> mappers) {
        this.legacyConnection = legacyConnection;
        this.mappers = mappers;
    }

    public byte[] produceTarGz(byte[] manifestBytes) throws IOException, SQLException {
        Map<String, byte[]> entityNdjsonByName = new LinkedHashMap<>();
        for (Mapper mapper : mappers) {
            entityNdjsonByName.put(mapper.entityType().name(), produceForOne(mapper));
        }
        return BundleStream.writeTarGz(entityNdjsonByName, manifestBytes);
    }

    private byte[] produceForOne(Mapper mapper) throws IOException, SQLException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String selectStatement = MapperLegacyBindings.selectForProducer(mapper.entityType());
        try (PreparedStatement ps = legacyConnection.prepareStatement(selectStatement);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                emitOneRow(mapper, rs, sink);
            }
        }
        return sink.toByteArray();
    }

    private static void emitOneRow(Mapper mapper, ResultSet rs, ByteArrayOutputStream sink)
            throws IOException, SQLException {
        // Per-row generator — the harness pays the allocation cost; the
        // hot-path mapper does not, matching the production producer where
        // S-139 will pool the generator. Trades throughput for diff
        // clarity (one self-contained line per row).
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sink)) {
            mapper.writeNdjson(rs, gen);
        }
        sink.write('\n');
    }

    public static ObjectMapper sharedJson() {
        return JSON;
    }
}
