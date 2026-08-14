package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
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
                emitOneRowAsItsOwnNdjsonLine(mapper, rs, sink);
            }
        }
        return sink.toByteArray();
    }

    private static void emitOneRowAsItsOwnNdjsonLine(
            Mapper mapper, ResultSet rs, ByteArrayOutputStream sink)
            throws IOException, SQLException {
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sink)) {
            mapper.writeNdjson(rs, gen);
        }
        sink.write('\n');
    }

    public static ObjectMapper sharedJson() {
        return JSON;
    }
}
