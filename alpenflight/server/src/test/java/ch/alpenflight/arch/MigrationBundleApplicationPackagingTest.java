package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * S-141b structural isolation. The orchestrator's helpers —
 * {@code BundleStreamReader} + {@code EntityStreamIngestor} — are
 * deliberately package-private so a future cron / batch / web layer
 * cannot wire them past the orchestrator's principal-owns-upload check.
 * If a future change ever needs cross-package access, the operator
 * should bring the call through {@code MigrationBundleIngestService} or
 * introduce a new public façade in {@code application/} — never make
 * these two classes public.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight.migrations.application",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class MigrationBundleApplicationPackagingTest {

    @ArchTest
    static final ArchRule bundle_stream_reader_stays_package_private =
            classes()
                    .that().haveSimpleName("BundleStreamReader")
                    .should().notBePublic()
                    .as("BundleStreamReader must remain package-private — orchestrator-only "
                            + "(S-141b service split, prevents bypass of upload-owner gate)");

    @ArchTest
    static final ArchRule entity_stream_ingestor_stays_package_private =
            classes()
                    .that().haveSimpleName("EntityStreamIngestor")
                    .should().notBePublic()
                    .as("EntityStreamIngestor must remain package-private — orchestrator-only "
                            + "(S-141b service split, prevents bypass of upload-owner gate)");
}
