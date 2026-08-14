package ch.alpenflight.migrations.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportJarProducerTest {

    private final ExportJarProducer producer = new ExportJarProducer(Duration.ofSeconds(10));

    @Test
    void exportCommandAssemblesFlagsAndKeepsThePasswordOffArgv() {
        List<String> command = ExportJarProducer.exportCommand(
                Path.of("/build/export.jar"), "jdbc:sqlserver://host", "sa",
                Path.of("/build/handshake.json"), Path.of("/build/out.enc"));

        assertThat(command)
                .containsSubsequence("-jar", "/build/export.jar")
                .contains("--jdbc-url", "jdbc:sqlserver://host", "--user", "sa",
                        "--handshake-file", "/build/handshake.json",
                        "--output", "/build/out.enc");
        assertThat(command).noneMatch(argument -> argument.contains("--password"));
        assertThat(String.join(" ", command)).doesNotContain("s3cret");
    }

    @Test
    void producePassesThePasswordViaEnvironmentNotArgv(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("out.enc");
        List<String> command = List.of(
                "sh", "-c", "printf '%s' \"$ALPF_DB_PASSWORD\" > \"" + output + "\"");

        Path result = producer.produce(command, "s3cret", output);

        assertThat(result).isEqualTo(output);
        assertThat(Files.readString(output)).isEqualTo("s3cret");
    }

    @Test
    void produceReturnsTheOutputOnZeroExitWithANonEmptyBundle(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("out.enc");
        List<String> command = List.of("sh", "-c", "printf bundle-bytes > \"" + output + "\"");

        assertThat(producer.produce(command, "pw", output)).isEqualTo(output);
    }

    @Test
    void produceMapsANonZeroExitToFailureSurfacingStderrAndCode(@TempDir Path dir) {
        Path output = dir.resolve("out.enc");
        List<String> command = List.of("sh", "-c", "printf 'jdbc connect refused' >&2; exit 7");

        assertThatExceptionOfType(ExportJarProcessException.class)
                .isThrownBy(() -> producer.produce(command, "pw", output))
                .satisfies(failure -> {
                    assertThat(failure).hasMessageContaining("producer process failed: 7");
                    assertThat(failure.exitCode()).isEqualTo(7);
                    assertThat(failure.stderr()).contains("jdbc connect refused");
                });
    }

    @Test
    void produceFailsWhenTheBundleIsEmptyDespiteAZeroExit(@TempDir Path dir) {
        Path output = dir.resolve("out.enc");
        List<String> command = List.of("sh", "-c", "exit 0");

        assertThatExceptionOfType(ExportJarProcessException.class)
                .isThrownBy(() -> producer.produce(command, "pw", output))
                .withMessageContaining("empty bundle");
    }

    @Test
    void produceTimesOutAndForciblyDestroysAHungChild(@TempDir Path dir) {
        Path output = dir.resolve("out.enc");
        ExportJarProducer shortTimeout = new ExportJarProducer(Duration.ofMillis(300));
        List<String> command = List.of("sh", "-c", "sleep 30");

        assertThatExceptionOfType(ExportJarProcessException.class)
                .isThrownBy(() -> shortTimeout.produce(command, "pw", output))
                .withMessageContaining("timed out");
    }
}
