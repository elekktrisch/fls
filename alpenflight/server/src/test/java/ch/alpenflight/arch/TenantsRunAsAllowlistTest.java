package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.platform.tenancy.Tenants;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class TenantsRunAsAllowlistTest {

    private static final Set<String> ALLOWED_CALLER_FQNS = Set.of(
            "ch.alpenflight.platform.tenancy.Tenants",
            "ch.alpenflight.audit.application.MutationAuditEventListener",
            "ch.alpenflight.audit.application.ClientIpRetentionJob",
            "ch.alpenflight.audit.web.RequestAuditFilter",
            "ch.alpenflight.deployments.application.DeploymentContext",
            "ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService",
            "ch.alpenflight.tenancy.showcase.ShowcaseSeeder",
            "ch.alpenflight.tenancy.sandbox.SandboxSeeder",
            "ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge",
            "ch.alpenflight.me.application.SystemDashboardService",
            "ch.alpenflight.flights.application.FlightReportRebuildService",
            "ch.alpenflight.joinrequests.application.JoinRequestsService",
            "ch.alpenflight.joinrequests.application.JoinRequestSubmitGuard",
            "ch.alpenflight.joinrequests.application.JoinRequestEmailListener",
            "ch.alpenflight.joinrequests.application.JoinRequestSseListener",
            "ch.alpenflight.publicregistration.application.PublicRegistrationIntake",
            "ch.alpenflight.clubs.application.ClubsService"
    );

    @ArchTest
    static final ArchRule production_callers_of_runAs_are_allowlisted =
            noClasses()
                    .that(describe("are not on the Tenants.runAs allow-list",
                            (JavaClass c) -> !ALLOWED_CALLER_FQNS.contains(c.getFullName())))
                    .should().callMethod(Tenants.class, "runAs", UUID.class, Runnable.class)
                    .orShould().callMethod(Tenants.class, "runAs", UUID.class, Supplier.class)
                    .as("Only allow-listed FQNs may call Tenants.runAs — an unguarded call "
                            + "silently elevates the calling thread's effective tenant. Adding a "
                            + "caller asserts it is paired with a surface-level authorisation gate "
                            + "or a cross-tenant operational rationale.");
}
