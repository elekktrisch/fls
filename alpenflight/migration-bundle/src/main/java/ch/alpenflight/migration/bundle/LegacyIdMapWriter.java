package ch.alpenflight.migration.bundle;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public final class LegacyIdMapWriter implements AutoCloseable {

    static final byte[] PGCOPY_SIGNATURE = {
            'P', 'G', 'C', 'O', 'P', 'Y', '\n', (byte) 0xFF, '\r', '\n', '\0'
    };

    private static final int TWO_COLUMN_FIELD_COUNT = 2;
    private static final int THREE_COLUMN_FIELD_COUNT = 3;
    private static final int UUID_LENGTH_BYTES = 16;

    private final DataOutputStream out;
    private boolean trailerWritten;

    public LegacyIdMapWriter(OutputStream destination) throws IOException {
        this.out = new DataOutputStream(destination);
        writeHeader();
    }

    public void write(UUID legacyGuid, UUID newUuid) throws IOException {
        if (trailerWritten) {
            throw new IllegalStateException("LegacyIdMapWriter closed");
        }
        out.writeShort(TWO_COLUMN_FIELD_COUNT);
        writeUuidField(legacyGuid);
        writeUuidField(newUuid);
    }

    public void write(UUID legacyGuid, UUID clubId, UUID newUuid) throws IOException {
        if (trailerWritten) {
            throw new IllegalStateException("LegacyIdMapWriter closed");
        }
        out.writeShort(THREE_COLUMN_FIELD_COUNT);
        writeUuidField(legacyGuid);
        writeUuidField(clubId);
        writeUuidField(newUuid);
    }

    @Override
    public void close() throws IOException {
        if (trailerWritten) {
            return;
        }
        out.writeShort(-1);
        out.flush();
        trailerWritten = true;
    }

    private void writeHeader() throws IOException {
        out.write(PGCOPY_SIGNATURE);
        out.writeInt(0);
        out.writeInt(0);
    }

    private void writeUuidField(UUID uuid) throws IOException {
        out.writeInt(UUID_LENGTH_BYTES);
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }
}
