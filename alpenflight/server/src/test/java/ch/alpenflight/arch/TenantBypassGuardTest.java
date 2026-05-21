package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Structural guard: production code must not call
 * {@link TenantContextCarrier#set(java.util.UUID)} from outside
 * {@code platform.tenancy} — that method exists so the resolver in
 * {@code src/main/java} can be wired to a thread-local carrier the test
 * support layer + {@code Tenants.runAs} push into. An unscoped production
 * caller could bypass the JWT-driven resolver branch and silently set the
 * effective tenant for the current thread.
 *
 * <p>The method is intentionally {@code public} (the test-side caller
 * {@code ch.alpenflight.server.testsupport.TenantTestContext} lives in a
 * different package). ArchUnit's {@code DoNotIncludeTests} import option
 * scopes this check to {@code src/main/java} only, so the test caller is
 * not flagged.
 *
 * <p>Lives outside {@link LayeringRulesTest} because it guards a single
 * named method rather than a per-module dependency direction.
 */
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
