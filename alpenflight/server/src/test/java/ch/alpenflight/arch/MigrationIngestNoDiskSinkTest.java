package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class MigrationIngestNoDiskSinkTest {

    @ArchTest
    static final ArchRule bundle_classes_have_no_disk_sinks =
            noClasses()
                    .that().resideInAPackage("ch.alpenflight.migrations.application..")
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
                    .as("ch.alpenflight.migrations.application.. classes must not depend on disk-sink APIs "
                            + "(FileOutputStream / FileChannel / RandomAccessFile / ByteArrayOutputStream) "
                            + "— decrypted bundle bytes never touch disk (S-141 Security plan).");
}
