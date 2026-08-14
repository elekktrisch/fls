package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

public abstract class AbstractMapperContractTest<M extends Mapper> {

    private static final long FAKER_SEED = 42L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    protected abstract M mapper();

    protected abstract Map<String, Object> legacyRow(Faker faker);

    protected Map<String, Set<Number>> permittedSparseEnumValues() {
        return Map.of();
    }

    protected final Faker seededFaker() {
        return new Faker(new Random(FAKER_SEED));
    }

    protected static String randomUuidString(Faker faker) {
        return new java.util.UUID(
                faker.random().nextLong(), faker.random().nextLong()).toString();
    }

    protected static int positionOf(Mapper mapper, String columnName) {
        String[] columns = mapper.columns();
        for (int index = 0; index < columns.length; index++) {
            if (columns[index].equals(columnName)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException(
                "Column " + columnName + " not declared by " + mapper.getClass().getSimpleName()
                        + ". Declared: " + java.util.Arrays.toString(columns));
    }

    @Test
    void entityTypeIsNotNull() {
        assertThat(mapper().entityType()).isNotNull();
    }

    @Test
    void columnsAreNotEmpty() {
        assertThat(mapper().columns()).isNotEmpty();
    }

    @Test
    void columnsAreUnique() {
        assertThat(mapper().columns()).doesNotHaveDuplicates();
    }

    @Test
    void columnsAreCallerSafeMutation() {
        Mapper underTest = mapper();
        String[] first = underTest.columns();
        String original = first[0];
        first[0] = "MUTATED";
        assertThat(underTest.columns()[0])
                .as("Mapper.columns() invariant — callers must not be able to mutate "
                        + "the shared column list.")
                .isEqualTo(original);
    }

    @Test
    void foreignKeyTargetsPrecedeSelfInIngestOrder() {
        EntityType self = mapper().entityType();
        for (EntityType target : mapper().foreignKeys()) {
            if (target == self) {
                continue;
            }
            assertThat(target.ordinal())
                    .as("FK target %s must precede %s in EntityType declaration order "
                            + "so ingest can resolve targets before sources",
                            target, self)
                    .isLessThan(self.ordinal());
        }
    }

    @Test
    void writeNdjsonEmitsEveryColumnDeclaredByTheMapper() throws Exception {
        M underTest = mapper();
        JsonNode emitted = invokeWriteNdjson(underTest, legacyRow(seededFaker()));
        assertThat(emitted.isObject())
                .as("writeNdjson must emit a JSON object")
                .isTrue();
        for (String column : underTest.columns()) {
            assertThat(emitted.has(column))
                    .as("JSON output is missing column %s declared by columns()", column)
                    .isTrue();
        }
    }

    @Test
    void emittedSparseEnumValuesStayInsideTheDeclaredSet() throws Exception {
        Map<String, Set<Number>> permitted = permittedSparseEnumValues();
        if (permitted.isEmpty()) {
            return;
        }
        M underTest = mapper();
        JsonNode emitted = invokeWriteNdjson(underTest, legacyRow(seededFaker()));
        for (Map.Entry<String, Set<Number>> entry : permitted.entrySet()) {
            String column = entry.getKey();
            JsonNode node = emitted.get(column);
            assertThat(node)
                    .as("Mapper declared %s as a sparse-enum column but did not "
                            + "emit it — every permittedSparseEnumValues key must "
                            + "appear in columns()", column)
                    .isNotNull();
            if (node == null || node.isNull()) {
                continue;
            }
            int actual = node.intValue();
            assertThat(entry.getValue().stream().map(Number::intValue))
                    .as("Mapper emitted sparse-enum value %d for column %s — "
                            + "subclass legacyRow must draw from %s so the parity "
                            + "oracle (S-187) never seeds a never-seen value the "
                            + "aggregate would reject downstream",
                            actual, column, entry.getValue())
                    .contains(actual);
        }
    }

    @Test
    void readEntityBindsEveryColumnInDeclaredOrderModuloParityIgnore() throws Exception {
        M underTest = mapper();
        JsonNode emitted = invokeWriteNdjson(underTest, legacyRow(seededFaker()));

        Map<Integer, Object> binds = new TreeMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        org.mockito.stubbing.Answer<Void> capture = invocation -> {
            binds.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        };
        doAnswer(capture).when(ps).setObject(anyInt(), any());
        doAnswer(capture).when(ps).setObject(anyInt(), any(), anyInt());
        doAnswer(capture).when(ps).setString(anyInt(), any());
        doAnswer(capture).when(ps).setBigDecimal(anyInt(), any());
        doAnswer(capture).when(ps).setDate(anyInt(), any());
        doAnswer(capture).when(ps).setTimestamp(anyInt(), any());
        doAnswer(capture).when(ps).setBytes(anyInt(), any());
        doAnswer(capture).when(ps).setShort(anyInt(), org.mockito.ArgumentMatchers.anyShort());
        doAnswer(capture).when(ps).setInt(anyInt(), anyInt());
        doAnswer(capture).when(ps).setLong(anyInt(), org.mockito.ArgumentMatchers.anyLong());
        doAnswer(capture).when(ps).setNull(anyInt(), anyInt());

        assertThatCode(() -> underTest.readEntity(emitted, ps))
                .as("readEntity must accept what writeNdjson emitted")
                .doesNotThrowAnyException();

        String[] columns = underTest.columns();
        Set<String> parityIgnored = ParityMarkers.ignored(underTest.getClass());
        for (int i = 0; i < columns.length; i++) {
            int position = i + 1;
            assertThat(binds)
                    .as("readEntity must setObject at position %d for column %s",
                            position, columns[i])
                    .containsKey(position);
            if (parityIgnored.contains(columns[i])) {
                continue;
            }
            assertThat(binds.get(position))
                    .as("Round-trip value for column %s (position %d) must not be null "
                            + "— writeNdjson emitted a field; readEntity dropped it",
                            columns[i], position)
                    .isNotNull();
        }
    }

    protected JsonNode invokeWriteNdjson(M underTest, Map<String, Object> legacy)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value == null ? null : value.toString();
        });
        lenient().when(rs.getObject(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getObject(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            if (value == null) {
                return null;
            }
            Class<?> target = invocation.getArgument(1);
            if (target == Integer.class && value instanceof Number number) {
                return number.intValue();
            }
            if (target == Long.class && value instanceof Number number) {
                return number.longValue();
            }
            if (target == Short.class && value instanceof Number number) {
                return number.shortValue();
            }
            return value;
        });
        lenient().when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number number ? number.intValue() : 0;
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number number ? number.longValue() : 0L;
        });
        lenient().when(rs.getBoolean(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Boolean bool && bool;
        });
        lenient().when(rs.getBigDecimal(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getDate(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getTimestamp(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getBytes(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(sink)) {
            underTest.writeNdjson(rs, generator);
        }
        return JSON.readTree(sink.toByteArray());
    }
}
