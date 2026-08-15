package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class TenantBypassGuardTest {

    @ArchTest
    static final ArchRule production_must_not_call_tenant_context_carrier_set =
            noClasses()
                    .that().resideOutsideOfPackage("ch.alpenflight.platform.tenancy..")
                    .should().callMethod(TenantContextCarrier.class, "set", java.util.UUID.class)
                    .as("Only the platform.tenancy package (carrier owner + Tenants.runAs) may call "
                            + "TenantContextCarrier.set; production callers would bypass the JWT-driven "
                            + "tenant resolver.");
}
