package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyIdMapWriterTest {

    private static final int HEADER_LENGTH = 11 + 4 + 4;
    private static final int ROW_LENGTH = 2 + 4 + 16 + 4 + 16;
    private static final int THREE_COLUMN_ROW_LENGTH = 2 + 4 + 16 + 4 + 16 + 4 + 16;
    private static final int TRAILER_LENGTH = 2;

    @Test
    void writesPgcopyBinarySignatureFirst() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter ignored = new LegacyIdMapWriter(sink)) {
        }
        byte[] bytes = sink.toByteArray();
        assertThat(bytes).startsWith(LegacyIdMapWriter.PGCOPY_SIGNATURE);
    }

    @Test
    void emitsHeaderPlusTrailerOnEmptyStream() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter ignored = new LegacyIdMapWriter(sink)) {
        }
        assertThat(sink.toByteArray()).hasSize(HEADER_LENGTH + TRAILER_LENGTH);
    }

    @Test
    void writesEachRowAsBigEndianUuidPair() throws Exception {
        UUID legacy = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID newUuid = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacy, newUuid);
        }
        byte[] bytes = sink.toByteArray();
        assertThat(bytes).hasSize(HEADER_LENGTH + ROW_LENGTH + TRAILER_LENGTH);

        ByteBuffer rowBuffer = ByteBuffer.wrap(bytes, HEADER_LENGTH, ROW_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        assertThat(rowBuffer.getShort()).isEqualTo((short) 2);
        assertThat(rowBuffer.getInt()).isEqualTo(16);
        assertThat(rowBuffer.getLong()).isEqualTo(legacy.getMostSignificantBits());
        assertThat(rowBuffer.getLong()).isEqualTo(legacy.getLeastSignificantBits());
        assertThat(rowBuffer.getInt()).isEqualTo(16);
        assertThat(rowBuffer.getLong()).isEqualTo(newUuid.getMostSignificantBits());
        assertThat(rowBuffer.getLong()).isEqualTo(newUuid.getLeastSignificantBits());

        ByteBuffer trailerBuffer = ByteBuffer.wrap(
                bytes, HEADER_LENGTH + ROW_LENGTH, TRAILER_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        assertThat(trailerBuffer.getShort()).isEqualTo((short) -1);
    }

    @Test
    void writesThreeColumnFanOutRowAndRoundTripsAllThreeUuids() throws Exception {
        UUID legacy = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID clubId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID newUuid = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacy, clubId, newUuid);
        }
        byte[] bytes = sink.toByteArray();
        assertThat(bytes)
                .as("header + one 3-column row + trailer, no padding")
                .hasSize(HEADER_LENGTH + THREE_COLUMN_ROW_LENGTH + TRAILER_LENGTH);

        ByteBuffer row = ByteBuffer.wrap(bytes, HEADER_LENGTH, THREE_COLUMN_ROW_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        assertThat(row.getShort()).as("field count").isEqualTo((short) 3);
        assertThat(row.getInt()).as("legacy_guid length prefix").isEqualTo(16);
        assertThat(row.getLong()).isEqualTo(legacy.getMostSignificantBits());
        assertThat(row.getLong()).isEqualTo(legacy.getLeastSignificantBits());
        assertThat(row.getInt()).as("club_id length prefix").isEqualTo(16);
        assertThat(row.getLong()).isEqualTo(clubId.getMostSignificantBits());
        assertThat(row.getLong()).isEqualTo(clubId.getLeastSignificantBits());
        assertThat(row.getInt()).as("new_uuid length prefix").isEqualTo(16);
        assertThat(row.getLong()).isEqualTo(newUuid.getMostSignificantBits());
        assertThat(row.getLong()).isEqualTo(newUuid.getLeastSignificantBits());

        ByteBuffer trailer = ByteBuffer.wrap(
                bytes, HEADER_LENGTH + THREE_COLUMN_ROW_LENGTH, TRAILER_LENGTH)
                .order(ByteOrder.BIG_ENDIAN);
        assertThat(trailer.getShort()).as("EOF marker").isEqualTo((short) -1);
    }

    @Test
    void rejectsWritesAfterClose() throws Exception {
        LegacyIdMapWriter writer = new LegacyIdMapWriter(new ByteArrayOutputStream());
        writer.close();
        assertThatThrownBy(() -> writer.write(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doubleCloseIsIdempotent() throws Exception {
        LegacyIdMapWriter writer = new LegacyIdMapWriter(new ByteArrayOutputStream());
        writer.close();
        writer.close();
    }
}
