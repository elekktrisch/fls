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
 *   <li>{@code ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService}
 *       — wraps each newly-provisioned Club in its tenant scope so the
 *       per-Club reference-data seed runs under the right Hibernate
 *       {@code @TenantId} carrier.</li>
 *   <li>{@code ch.alpenflight.me.application.SystemDashboardService}
 *       — sums {@code @TenantId}-scoped flight counts one club at a time so
 *       the sysadmin dashboard tile spans all tenants; gated by a
 *       SYSTEM_ADMINISTRATOR surface authorisation.</li>
 *   <li>{@code ch.alpenflight.clubs.application.ClubsService} — validates a
 *       club's homebase against that club's own {@code @TenantId}-scoped
 *       Locations, which is a different tenant from the editing principal's.</li>
 *   <li>{@code ch.alpenflight.platform.tenancy} — the carrier owner.</li>
 * </ul>
 *
 * <p>Allow-list is keyed by fully-qualified class name (not by package
 * wildcard) so a new class added to any of these packages does not
 * silently inherit the privilege — it must be explicitly added here.
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

    private static final Set<String> ALLOWED_CALLERS = Set.of(
            "ch.alpenflight.platform.tenancy.Tenants",
            "ch.alpenflight.audit.application.MutationAuditEventListener",
            "ch.alpenflight.audit.web.RequestAuditFilter",
            "ch.alpenflight.deployments.application.DeploymentContext",
            "ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService",
            // Showcase demo-data loader (@Profile("showcase"), never on the
            // request path): writes tenant-scoped Location rows on behalf of
            // multiple clubs in one pass — the documented cross-tenant
            // bulk-seed case for Tenants.runAs.
            "ch.alpenflight.tenancy.showcase.ShowcaseSeeder",
            // Sysadmin dashboard totals (J-3 T-10): sums @TenantId-scoped flight
            // counts one club at a time to span all tenants — a deliberate
            // SYSTEM_ADMINISTRATOR-only read, gated by
            // @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')") on its controller.
            "ch.alpenflight.me.application.SystemDashboardService",
            // Flight-report read-model rebuild (ADR 0027 RM-2): re-projects a
            // club's flights under that club's tenant scope at the three
            // repository.save-bypass seams (migration ingest, dev-seed startup,
            // showcase). Never on the request path — invoked by the ingest
            // transaction, a dev/showcase-profile runner, or ops tooling.
            "ch.alpenflight.flights.application.FlightReportRebuildService",
            // Pilot self-serve join (S-178): a tenant-less pilot (no clubId
            // claim, no t_user) submits / withdraws / me-reads their OWN join
            // request, which is @TenantId-scoped. The service resolves the club
            // FIRST — from the join code (submit) or the pilot's own request
            // (withdraw / me, via JoinRequestTenantLookup) — then runs the
            // tenant-scoped JPA work under runAs(thatClub). Withdraw re-gates on
            // keycloak_sub == jwt.sub after the load, so the scope only ever
            // admits the caller's own row.
            "ch.alpenflight.joinrequests.application.JoinRequestsService",
            // Submit abuse guard (S-178, T-07): the 24h deny cooldown derives
            // from the most-recent DENIED row for (sub, club), which is
            // @TenantId-scoped. The guard reads it under the already-resolved
            // club's scope — the same club the submit path resolved from the
            // join code — never widening beyond that single tenant.
            "ch.alpenflight.joinrequests.application.JoinRequestSubmitGuard",
            // Join-request notifications (S-178, T-08): the AFTER_COMMIT email +
            // SSE listeners re-establish the tenant window for the committed
            // transition's OWN club to resolve the club's admin recipients (the
            // @TenantId-scoped t_user lookup), so the request transaction made no
            // directory round-trip. The scope is the event's clubId — never
            // widened beyond the single tenant the decision belongs to.
            "ch.alpenflight.joinrequests.application.JoinRequestEmailListener",
            "ch.alpenflight.joinrequests.application.JoinRequestSseListener",
            // Anonymous public registration (S-025): there is no principal, so the
            // target tenant comes from the URL slug. PublicClubResolver resolves and
            // allowlist-checks it OUTSIDE any scope first — the 404 / 403 paths never
            // reach runAs — and only the accepted submission opens a window, for
            // exactly that one club. The surface gate is Club.acceptsPublicRegistration
            // (the club's own opt-in), not a role.
            "ch.alpenflight.publicregistration.application.PublicRegistrationIntake",
            // Club homebase validation (J-17): the homebase must be one of the
            // EDITED club's own Locations, and Locations are @TenantId-scoped. The
            // caller's own tenant is the wrong one for a SYSTEM_ADMINISTRATOR
            // editing another club — it would accept the sysadmin club's Location
            // and reject the edited club's — so the check runs under the edited
            // club's scope. Read-only, one club, and the club id is the already
            // role-gated path variable (@tenant.isOwnClub for a CLUB_ADMINISTRATOR).
            "ch.alpenflight.clubs.application.ClubsService"
    );

    @ArchTest
    static final ArchRule production_callers_of_runAs_are_allowlisted =
            noClasses()
                    .that(describe("are not on the Tenants.runAs allow-list",
                            (JavaClass c) -> !ALLOWED_CALLERS.contains(c.getFullName())))
                    .should().callMethod(Tenants.class, "runAs", UUID.class, Runnable.class)
                    .orShould().callMethod(Tenants.class, "runAs", UUID.class, Supplier.class)
                    .as("Only allow-listed FQNs may call Tenants.runAs. Adding a caller is a "
                            + "deliberate security decision — see the class javadoc.");
}
