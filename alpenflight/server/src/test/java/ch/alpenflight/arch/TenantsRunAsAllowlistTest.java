package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.platform.tenancy.Tenants;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S-137 security plan — only the sanctioned callers may open a tenant
 * override via {@link Tenants#runAs}:
 *
 * <ul>
 *   <li>{@code ch.alpenflight.audit.application.MutationAuditEventListener}
 *       — pushes the captured request tenant onto the audit write.</li>
 *   <li>{@code ch.alpenflight.audit.web.RequestAuditFilter} — re-asserts
 *       the audit-target tenant for the synthetic-failure path.</li>
 *   <li>{@code ch.alpenflight.deployments.application.DeploymentContext}
 *       — iterates child Clubs of a Deployment under per-Club scope.</li>
 *   <li>{@code ch.alpenflight.tenancy.provisioning.application} (S-138)
 *       — wraps each newly-provisioned Club in its tenant scope so the
 *       per-Club reference-data seed runs under the right Hibernate
 *       {@code @TenantId} carrier.</li>
 *   <li>{@code ch.alpenflight.platform.tenancy} — the carrier owner.</li>
 * </ul>
 *
 * <p>Adding a class to this allow-list is a deliberate decision: an
 * unguarded {@link Tenants#runAs} silently elevates the calling thread's
 * effective tenant. Pair with a surface-level authorisation gate or a
 * cross-tenant operational rationale documented in code.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class TenantsRunAsAllowlistTest {

    @ArchTest
    static final ArchRule production_callers_of_runAs_are_allowlisted =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "ch.alpenflight.platform.tenancy..",
                            "ch.alpenflight.audit.application..",
                            "ch.alpenflight.audit.web..",
                            "ch.alpenflight.deployments.application..",
                            "ch.alpenflight.tenancy.provisioning.application..")
                    .should().callMethod(Tenants.class, "runAs", UUID.class, Runnable.class)
                    .orShould().callMethod(Tenants.class, "runAs", UUID.class, Supplier.class)
                    .as("Only allow-listed packages may call Tenants.runAs. Adding a caller is a "
                            + "deliberate security decision — see the class javadoc.");
}
