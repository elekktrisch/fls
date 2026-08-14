package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.core.JsonGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleWriterStreamTest {

    @TempDir
    Path workDir;

    private static final int ROWS_SPANNING_MANY_BUFFER_FLUSHES_AND_FETCH_WINDOWS = 5000;

    @Test
    void streamsEveryRowAcrossManyBufferFlushesWithoutClosingTheTarget() throws Exception {
        BundleWriter writer = new BundleWriter(null, workDir, false);
        ResultSet cursor = forwardOnlyCursor(ROWS_SPANNING_MANY_BUFFER_FLUSHES_AND_FETCH_WINDOWS);
        Mapper mapper = countingMapper();

        EntityStreamResult[] result = new EntityStreamResult[1];
        assertThatCode(() -> result[0] = writer.drainCursor(EntityType.COUNTRY, mapper, cursor))
                .as("per-row gen.close() must not close the shared NDJSON stream "
                        + "— a ClosedChannelException at a buffer boundary is the T-13 bug")
                .doesNotThrowAnyException();

        assertThat(result[0].rowCount())
                .as("every fake row streamed, none lost to a premature channel close")
                .isEqualTo(ROWS_SPANNING_MANY_BUFFER_FLUSHES_AND_FETCH_WINDOWS);

        List<String> lines = Files.readAllLines(result[0].ndjsonTempFile(), StandardCharsets.UTF_8);
        assertThat(lines)
                .as("one NDJSON line per row, last row present (not truncated mid-stream)")
                .hasSize(ROWS_SPANNING_MANY_BUFFER_FLUSHES_AND_FETCH_WINDOWS);
        assertThat(lines.get(0)).isEqualTo("{\"row\":0}");
        int lastRow = ROWS_SPANNING_MANY_BUFFER_FLUSHES_AND_FETCH_WINDOWS - 1;
        assertThat(lines.get(lastRow)).isEqualTo("{\"row\":" + lastRow + "}");
    }

    private static ResultSet forwardOnlyCursor(int rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                BundleWriterStreamTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            cursor[0]++;
                            return cursor[0] < rows;
                        case "getInt":
                            return cursor[0];
                        case "close":
                            return null;
                        case "getStatement":
                            return null;
                        default:
                            Class<?> ret = method.getReturnType();
                            if (ret == boolean.class) {
                                return false;
                            }
                            if (ret == int.class || ret == long.class) {
                                return 0;
                            }
                            return null;
                    }
                });
    }

    private static Mapper countingMapper() {
        return new Mapper() {
            @Override
            public EntityType entityType() {
                return EntityType.COUNTRY;
            }

            @Override
            public String[] columns() {
                return new String[] {"row"};
            }

            @Override
            public List<EntityType> foreignKeys() {
                return List.of();
            }

            @Override
            public void writeNdjson(ResultSet source, JsonGenerator target) throws java.io.IOException {
                try {
                    target.writeStartObject();
                    target.writeNumberField("row", source.getInt("row"));
                    target.writeEndObject();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void readEntity(com.fasterxml.jackson.databind.JsonNode source,
                                   java.sql.PreparedStatement target) {
                throw new UnsupportedOperationException("export-only test mapper");
            }
        };
    }
}
