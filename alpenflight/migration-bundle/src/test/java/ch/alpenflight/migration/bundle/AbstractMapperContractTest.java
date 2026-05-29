package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

/**
 * Per-mapper contract suite. Subclass returns the mapper instance and one
 * deterministic legacy row; this class asserts the {@link Mapper} contract.
 * The two abstract hooks are the only subclass surface — every other
 * assertion is shared so the per-mapper unit test stays a 10-line stub.
 *
 * @param <M> concrete mapper type.
 */
public abstract class AbstractMapperContractTest<M extends Mapper> {

    private static final long FAKER_SEED = 42L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /** Mapper under test. */
    protected abstract M mapper();

    /**
     * One deterministic legacy row keyed by the <em>legacy</em> ResultSet
     * column name the mapper's {@code writeNdjson} reads. {@link Faker} is
     * pre-seeded so subclass output is repeatable.
     */
    protected abstract Map<String, Object> legacyRow(Faker faker);

    /** Seeded {@link Faker} matching {@link #legacyRow}. */
    protected final Faker seededFaker() {
        return new Faker(new Random(FAKER_SEED));
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
    void readEntityBindsEveryColumnInDeclaredOrderModuloParityIgnore() throws Exception {
        M underTest = mapper();
        JsonNode emitted = invokeWriteNdjson(underTest, legacyRow(seededFaker()));

        Map<Integer, Object> binds = new TreeMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(invocation -> {
            binds.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ps).setObject(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());

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

    private JsonNode invokeWriteNdjson(M underTest, Map<String, Object> legacy)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value == null ? null : value.toString();
        });
        lenient().when(rs.getObject(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
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

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(sink)) {
            underTest.writeNdjson(rs, generator);
        }
        return JSON.readTree(sink.toByteArray());
    }
}
