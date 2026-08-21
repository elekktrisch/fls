package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.arch.AuditCallSiteSnapshotFieldDecisionGuard.UndecidedSnapshotFields;
import ch.alpenflight.arch.AuditRedactionCoverageTest.RedactionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditCallSiteSnapshotFieldDecisionGuardTest {

    static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    static final Path REGISTER_OF_CALL_SITES_THIS_REPOSITORY_HAS_NOT_FIXED_YET =
            Path.of("config/audit/undecided-audit-snapshot-fields.txt");

    @Test
    void every_audit_call_site_snapshot_field_is_decided_or_stands_in_the_shrinking_register()
            throws Exception {
        RedactionPolicy policy = AuditRedactionCoverageTest.loadPolicy();
        List<Path> callSiteSources =
                AuditCallSiteSnapshotFieldDecisionGuard.javaSourcesUnder(MAIN_SOURCE_ROOT);

        assertThat(callSiteSources)
                .as("Sanity: the scan must find the audit call-site sources under %s",
                        MAIN_SOURCE_ROOT.toAbsolutePath())
                .isNotEmpty();

        List<UndecidedSnapshotFields> actual = AuditCallSiteSnapshotFieldDecisionGuard.scan(
                callSiteSources,
                System.getProperty("java.class.path"),
                policy,
                AuditCallSiteSnapshotFieldDecisionGuardTest.class.getClassLoader());

        List<UndecidedSnapshotFields> registered = readRegister();
        List<UndecidedSnapshotFields> unregistered = actual.stream()
                .filter(violation -> !registered.contains(violation))
                .toList();
        List<UndecidedSnapshotFields> stale = registered.stream()
                .filter(violation -> !actual.contains(violation))
                .toList();

        assertThat(AuditCallSiteSnapshotFieldDecisionGuard.renderRefusal(unregistered, stale))
                .as("%s must list exactly the audit call sites whose snapshot fields the recorded "
                        + "entityType does not decide", REGISTER_OF_CALL_SITES_THIS_REPOSITORY_HAS_NOT_FIXED_YET)
                .satisfies(refusal -> assertThat(unregistered).as(refusal).isEmpty())
                .satisfies(refusal -> assertThat(stale).as(refusal).isEmpty());
    }

    private static List<UndecidedSnapshotFields> readRegister() throws Exception {
        return Files.readAllLines(REGISTER_OF_CALL_SITES_THIS_REPOSITORY_HAS_NOT_FIXED_YET).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(UndecidedSnapshotFields::parse)
                .toList();
    }
}
