package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftOperatingCounter;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.hibernate.annotations.TenantId;

/**
 * Structural guard: the Aircraft aggregate is <strong>cross-tenant</strong>
 * per {@code alpenflight/database/tenant-rules.yaml:275-313} (2026-05-16
 * reclassification). Charter / loan / private aircraft have no owning club
 * and an authenticated principal of any club may see the full fleet
 * (S-050 Security plan §"Reads"). A {@code @TenantId} annotation on
 * {@link Aircraft} or its aggregate-internal entities would silently flip
 * the read path to per-club filtering and break that contract.
 *
 * <p>Counterpart to the test-plan promise: "ArchUnit: 0 new — inherits
 * S-155 rules; add a `cross-tenant` package marker assertion (no
 * {@code @TenantId} annotation on Aircraft / its internals)."
 */
@AnalyzeClasses(
        packages = "ch.alpenflight.aircraft.domain",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class AircraftCrossTenantGuardTest {

    @ArchTest
    static final ArchRule aircraft_aggregate_classes_must_not_carry_tenant_id_annotation =
            noClasses()
                    .that()
                    .belongToAnyOf(Aircraft.class, AircraftStateHistoryEntry.class,
                            AircraftOperatingCounter.class)
                    .should()
                    .beAnnotatedWith(TenantId.class)
                    .as("Aircraft is cross-tenant per tenant-rules.yaml — no @TenantId on the AR "
                            + "or its aggregate-internal entities at the type level.");

    @ArchTest
    static final ArchRule aircraft_aggregate_fields_must_not_carry_tenant_id_annotation =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .belongToAnyOf(Aircraft.class, AircraftStateHistoryEntry.class,
                            AircraftOperatingCounter.class)
                    .should()
                    .beAnnotatedWith(TenantId.class)
                    .as("No field on Aircraft / its aggregate-internal entities may be @TenantId — "
                            + "Hibernate-level club filtering would silently break the charter / "
                            + "loan / full-fleet read contract.");
}
