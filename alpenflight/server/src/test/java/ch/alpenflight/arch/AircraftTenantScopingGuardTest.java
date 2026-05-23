package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import ch.alpenflight.aircraft.domain.Aircraft;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.hibernate.annotations.TenantId;

/**
 * Structural guard: the {@link Aircraft} aggregate is <strong>tenant-scoped</strong>
 * per S-159 — every aircraft is registered by exactly one tenant (the
 * managing club). The {@code @TenantId} annotation on
 * {@code managingClubId} carries that contract; removing it would silently
 * un-filter reads + writes and reintroduce the IDOR-by-default failure mode.
 *
 * <p>Aggregate-internal entities ({@code AircraftStateHistoryEntry},
 * {@code AircraftOperatingCounter}) ride through the parent's tenant via
 * FK chain; they intentionally do <strong>not</strong> carry their own
 * {@code @TenantId}, so this guard targets only the AR.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight.aircraft.domain",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class AircraftTenantScopingGuardTest {

    @ArchTest
    static final ArchRule aircraft_managing_club_id_must_carry_tenant_id_annotation =
            fields()
                    .that()
                    .areDeclaredIn(Aircraft.class)
                    .and().haveName("managingClubId")
                    .should()
                    .beAnnotatedWith(TenantId.class)
                    .as("Aircraft.managingClubId is the @TenantId discriminator (S-159) — "
                            + "removing the annotation un-filters Hibernate queries and "
                            + "reintroduces R1 (multi-tenancy by convention).");
}
