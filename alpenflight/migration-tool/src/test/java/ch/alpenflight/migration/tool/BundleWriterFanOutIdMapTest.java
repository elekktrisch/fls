package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class BundleWriterFanOutIdMapTest {

    private static final int HEADER_LENGTH = 11 + 4 + 4;
    private static final int THREE_COLUMN_ROW_LENGTH = 2 + 4 + 16 + 4 + 16 + 4 + 16;

    @TempDir
    Path workDir;

    @Test
    void locationIdMapHasOneThreeColumnEntryPerFannedOutReplica() throws Exception {
        UUID sharedLocation = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID clubA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID clubB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID replicaA = Coercions.deriveFanOutId(sharedLocation, clubA);
        UUID replicaB = Coercions.deriveFanOutId(sharedLocation, clubB);

        Path ndjson = workDir.resolve("location.ndjson");
        Files.writeString(ndjson,
                fanOutLine(replicaA, sharedLocation, clubA) + "\n"
                        + fanOutLine(replicaB, sharedLocation, clubB) + "\n",
                StandardCharsets.UTF_8);

        BundleWriter writer = new BundleWriter(null, workDir, false);
        Path pgcopy = writer.writeIdentityPgcopy(
                new EntityStreamResult(EntityType.LOCATION, ndjson, 2, "sha"));

        List<long[]> rows = readThreeColumnRows(Files.readAllBytes(pgcopy));
        assertThat(rows)
                .as("two fanned-out replicas -> two 3-column id-map entries")
                .hasSize(2);
        assertThat(rows.get(0))
                .as("entry 0 = (legacy LocationId, clubA, replicaA derived id)")
                .containsExactly(
                        sharedLocation.getMostSignificantBits(),
                        sharedLocation.getLeastSignificantBits(),
                        clubA.getMostSignificantBits(),
                        clubA.getLeastSignificantBits(),
                        replicaA.getMostSignificantBits(),
                        replicaA.getLeastSignificantBits());
        assertThat(rows.get(1))
                .as("entry 1 = (legacy LocationId, clubB, replicaB derived id) — "
                        + "shared legacy_guid, distinct club + distinct derived id")
                .containsExactly(
                        sharedLocation.getMostSignificantBits(),
                        sharedLocation.getLeastSignificantBits(),
                        clubB.getMostSignificantBits(),
                        clubB.getLeastSignificantBits(),
                        replicaB.getMostSignificantBits(),
                        replicaB.getLeastSignificantBits());
    }

    @Test
    void personClubBundleAssemblesWithoutAnIdentityMap() throws Exception {
        Path ndjson = workDir.resolve("person_club.ndjson");
        Files.writeString(ndjson,
                "{\"person_id\":\"" + UUID.randomUUID()
                        + "\",\"club_id\":\"" + UUID.randomUUID() + "\"}\n",
                StandardCharsets.UTF_8);

        BundleWriter writer = new BundleWriter(null, workDir, false);
        Path bundle = workDir.resolve("bundle.tar.gz");

        writer.assembleTarGz(
                "{}".getBytes(StandardCharsets.UTF_8),
                List.of(new EntityStreamResult(EntityType.PERSON_CLUB, ndjson, 1, "sha")),
                bundle);

        assertThat(Files.size(bundle))
                .as("the bundle assembles even though PERSON_CLUB emits no id-map")
                .isPositive();
    }

    @Test
    void describeNamesTheClassAndCauseChainEvenWhenMessageIsNull() {
        NullPointerException npe = new NullPointerException();
        assertThat(BundleWriter.describe(npe))
                .as("null-message throwable still names its type")
                .isEqualTo("java.lang.NullPointerException");

        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException("required column 'created_on' is NULL"));
        assertThat(BundleWriter.describe(wrapped))
                .as("cause chain is rendered with class + message at each level")
                .contains("java.lang.RuntimeException: outer")
                .contains("caused by")
                .contains("java.lang.IllegalStateException: required column 'created_on' is NULL");
    }

    private static String fanOutLine(UUID id, UUID legacyGuid, UUID clubId) {
        return "{\"id\":\"" + id + "\",\"legacy_guid\":\"" + legacyGuid
                + "\",\"club_id\":\"" + clubId + "\"}";
    }

    private static List<long[]> readThreeColumnRows(byte[] bytes) {
        List<long[]> rows = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.position(HEADER_LENGTH);
        while (buffer.remaining() >= THREE_COLUMN_ROW_LENGTH) {
            short fieldCount = buffer.getShort();
            if (fieldCount == -1) {
                break;
            }
            assertThat(fieldCount).as("fan-out rows are 3-column").isEqualTo((short) 3);
            long[] row = new long[6];
            for (int field = 0; field < 3; field++) {
                assertThat(buffer.getInt()).as("UUID length prefix").isEqualTo(16);
                row[field * 2] = buffer.getLong();
                row[field * 2 + 1] = buffer.getLong();
            }
            rows.add(row);
        }
        return rows;
    }
}
