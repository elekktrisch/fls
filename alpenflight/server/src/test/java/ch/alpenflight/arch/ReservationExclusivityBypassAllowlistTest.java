package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import java.util.Set;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReservationExclusivityBypassAllowlistTest {

    private static final Set<String> ALLOWED_CALLER_FQNS = Set.of(
            "ch.alpenflight.reservations.application.AircraftReservationsService",
            "ch.alpenflight.publicregistration.application.DiscoveryReservationBooker");

    private static final Set<String> ENTITY_MANAGER_WRITES = Set.of("persist", "merge");

    @ArchTest
    static final ArchRule only_allowlisted_callers_create_or_save_a_reservation =
            noClasses()
                    .that(describe("are not on the reservation-bypass allow-list",
                            (JavaClass c) -> !ALLOWED_CALLER_FQNS.contains(c.getFullName())))
                    .should().callCodeUnitWhere(mintsOrPersistsAReservation())
                    .as("Creating or persisting an AircraftReservation outside "
                            + "AircraftReservationsService skips the exclusivity probe that "
                            + "answers 409 for an overlapping booking. Allow-listing a new FQN "
                            + "asserts that caller's bookings are genuinely non-exclusive.");

    private static DescribedPredicate<JavaCall<?>> mintsOrPersistsAReservation() {
        return describe("mint or persist an AircraftReservation",
                call -> mints(call) || savesThroughTheRepository(call) || persistsThroughJpa(call));
    }

    private static boolean mints(JavaCall<?> call) {
        boolean targetsTheAggregate =
                call.getTargetOwner().isEquivalentTo(AircraftReservation.class);
        boolean originIsTheAggregateCallingItsOwnConstructor =
                call.getOriginOwner().isEquivalentTo(AircraftReservation.class);
        if (!targetsTheAggregate || originIsTheAggregateCallingItsOwnConstructor) {
            return false;
        }
        String target = call.getTarget().getName();
        return "create".equals(target) || JavaConstructor.CONSTRUCTOR_NAME.equals(target);
    }

    private static boolean savesThroughTheRepository(JavaCall<?> call) {
        return call.getTargetOwner().isAssignableTo(AircraftReservationRepository.class)
                && call.getTarget().getName().startsWith("save");
    }

    private static boolean persistsThroughJpa(JavaCall<?> call) {
        return call.getTargetOwner().isAssignableTo(EntityManager.class)
                && ENTITY_MANAGER_WRITES.contains(call.getTarget().getName())
                && referencesAReservation(call.getOriginOwner());
    }

    private static boolean referencesAReservation(JavaClass origin) {
        return origin.getDirectDependenciesFromSelf().stream()
                .anyMatch(dependency ->
                        dependency.getTargetClass().isEquivalentTo(AircraftReservation.class));
    }
}
