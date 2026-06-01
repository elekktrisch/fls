package ch.alpenflight.migration.bundle;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Streams (legacy_guid, new_uuid) pairs in Postgres COPY <em>binary</em>
 * format. S-141 wires {@code PgConnection.getCopyAPI().copyIn(
 * "COPY legacy_id_map_&lt;entity&gt; FROM STDIN BINARY", outputStream)} —
 * this class owns the byte format so producer (S-139) and consumer (S-141)
 * stay in lockstep.
 *
 * <p>Binary chosen over text to avoid per-row text parsing + UUID
 * stringification at &gt;1M-row scale.
 *
 * <p>Byte layout per the
 * <a href="https://www.postgresql.org/docs/17/sql-copy.html#id-1.9.3.55.9.4">
 * Postgres 17 binary COPY format</a>:
 * <pre>
 *   File header (19 bytes):
 *     11 bytes  "PGCOPY\n\377\r\n\0" signature
 *      4 bytes  int32  flags             (zero)
 *      4 bytes  int32  header extension  (zero)
 *
 *   Per 2-column row (SYSTEM_GLOBAL / CLUB / identity entities):
 *      2 bytes  int16  field count       (2: legacy_guid, new_uuid)
 *      4 bytes  int32  legacy_guid length (16)
 *     16 bytes        legacy_guid raw bytes
 *      4 bytes  int32  new_uuid length    (16)
 *     16 bytes        new_uuid raw bytes
 *
 *   Per 3-column row (fan-out entities — {@link EntityType#fansOut()}):
 *      2 bytes  int16  field count       (3: legacy_guid, club_id, new_uuid)
 *      4 bytes  int32  legacy_guid length (16)
 *     16 bytes        legacy_guid raw bytes
 *      4 bytes  int32  club_id length     (16)
 *     16 bytes        club_id raw bytes
 *      4 bytes  int32  new_uuid length    (16)
 *     16 bytes        new_uuid raw bytes
 *
 *   Trailer (2 bytes):
 *      2 bytes  int16  field count        (-1 — EOF marker)
 * </pre>
 *
 * <p>A single writer instance must emit rows of one shape only — the COPY
 * target table's column count is fixed, so callers pick the 2-arg or 3-arg
 * {@code write} per id-map table, never mix them on one stream.
 *
 * <p>This class wraps the caller's {@link OutputStream} in
 * {@link DataOutputStream} for big-endian primitives. Caller owns the
 * underlying stream's lifecycle ({@link #close()} writes the trailer +
 * flushes but does NOT close the wrapped stream — S-141 holds the COPY
 * connection.)
 *
 * <p><strong>Callers must not pass a disk-backed stream.</strong> The
 * Security plan (S-183) keeps plaintext bundle bytes off local disk; the
 * ArchUnit "no disk sinks" rule inside this module catches the structural
 * cases, but the constructor cannot verify the caller's choice. S-141 wires
 * {@code PgConnection.getCopyAPI().copyIn(...)} directly to avoid any
 * intermediate disk-backed stream.
 */
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

    /**
     * Emits a 2-column {@code (legacy_guid, new_uuid)} row. Used by
     * SYSTEM_GLOBAL / CLUB / identity entities whose id-map table is not
     * fan-out (see {@link EntityType#fansOut()}).
     */
    public void write(UUID legacyGuid, UUID newUuid) throws IOException {
        if (trailerWritten) {
            throw new IllegalStateException("LegacyIdMapWriter closed");
        }
        out.writeShort(TWO_COLUMN_FIELD_COUNT);
        writeUuidField(legacyGuid);
        writeUuidField(newUuid);
    }

    /**
     * Emits a 3-column {@code (legacy_guid, club_id, new_uuid)} row for a
     * fan-out entity ({@link EntityType#fansOut()}): one shared legacy
     * masterdata GUID maps to a {@code club_id}-distinct replica id (typically
     * {@link Coercions#deriveFanOutId}). {@code club_id} is the legacy club id
     * the row fans out for, so a downstream FK resolves the composite
     * {@code (legacy_guid, club_id)} key to the referencer's own replica.
     */
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
