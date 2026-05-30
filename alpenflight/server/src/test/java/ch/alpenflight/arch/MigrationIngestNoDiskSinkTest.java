package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * S-141 structural plaintext-leak gate. The bundle-decrypt pipeline
 * classes (anything matching {@code *Bundle*} or
 * {@code MigrationBundle*} under {@code ch.alpenflight.migrations..})
 * MUST NOT touch any filesystem sink that would spool the decrypted
 * body to local disk — defense-in-depth against a future code path
 * that imports {@code Files.write} or wraps a body in
 * {@code ByteArrayOutputStream}.
 *
 * <p>Scope is class-name-based rather than package-scoped because the
 * S-140 {@code MigrationCryptoConfig} legitimately reads a Tink keyset
 * file at startup via {@code java.nio.file.Files} — that path is NOT
 * on the decrypted-bundle hot path.
 */
@AnalyzeClasses(
        packagesOf = MigrationIngestNoDiskSinkTest.class,
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class MigrationIngestNoDiskSinkTest {

    @ArchTest
    static final ArchRule bundle_classes_have_no_disk_sinks =
            noClasses()
                    .that().resideInAPackage("ch.alpenflight.migrations..")
                    .and().haveSimpleNameContaining("Bundle")
                    .should().dependOnClassesThat(
                            new DescribedPredicate<>("a disk-sink type") {
                                @Override
                                public boolean test(JavaClass clazz) {
                                    String name = clazz.getFullName();
                                    return "java.io.FileOutputStream".equals(name)
                                            || "java.io.FileWriter".equals(name)
                                            || "java.io.RandomAccessFile".equals(name)
                                            || "java.nio.channels.FileChannel".equals(name)
                                            || "java.io.ByteArrayOutputStream".equals(name);
                                }
                            })
                    .as("ch.alpenflight.migrations..*Bundle* classes must not depend on disk-sink APIs "
                            + "(FileOutputStream / FileChannel / RandomAccessFile / ByteArrayOutputStream) "
                            + "— decrypted bundle bytes never touch disk (S-141 Security plan).");
}
