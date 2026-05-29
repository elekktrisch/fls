package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

/**
 * Per-mapper contract suite. Subclass returns the mapper instance and one
 * deterministic legacy row; this class asserts the {@link Mapper} contract.
 * The two abstract hooks are the only subclass surface — every other
 * assertion is shared.
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
        String[] columns = mapper().columns();
        assertThat(columns).doesNotHaveDuplicates();
    }

    @Test
    void columnsAreCallerSafeMutation() {
        Mapper underTest = mapper();
        String[] first = underTest.columns();
        if (first.length == 0) {
            return;
        }
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
    void writeNdjsonThenReadEntityCompletesRoundTrip() throws Exception {
        M underTest = mapper();
        Map<String, Object> legacy = legacyRow(new Faker(new java.util.Random(FAKER_SEED)));

        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            Object value = legacy.get(column);
            return value == null ? null : value.toString();
        });
        lenient().when(rs.getObject(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number n ? n.intValue() : 0;
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number n ? n.longValue() : 0L;
        });
        lenient().when(rs.getBoolean(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Boolean b && b;
        });

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(sink)) {
            underTest.writeNdjson(rs, generator);
        }

        JsonNode parsed = JSON.readTree(sink.toByteArray());
        assertThat(parsed.isObject())
                .as("writeNdjson must emit a JSON object")
                .isTrue();
        for (String column : underTest.columns()) {
            assertThat(parsed.has(column))
                    .as("JSON output is missing column %s", column)
                    .isTrue();
        }

        PreparedStatement ps = mock(PreparedStatement.class);
        assertThatCode(() -> underTest.readEntity(parsed, ps))
                .as("readEntity must accept what writeNdjson emitted")
                .doesNotThrowAnyException();

        verify(ps, atLeast(underTest.columns().length))
                .setObject(anyInt(), any());
    }
}
