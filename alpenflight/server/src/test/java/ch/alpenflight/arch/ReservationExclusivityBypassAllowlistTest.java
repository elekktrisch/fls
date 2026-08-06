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

/**
 * Only two callers may mint or persist an {@link AircraftReservation} directly,
 * because doing so skips the exclusivity probe
 * ({@code AircraftReservationsService.rejectIfConflicting}) that answers 409 for
 * an overlapping booking.
 *
 * <ul>
 *   <li>{@code reservations.application.AircraftReservationsService} — the
 *       member-facing booking service, which RUNS the probe. It is on the list
 *       because it owns the probe, not because it is exempt from it.</li>
 *   <li>{@code publicregistration.application.DiscoveryReservationBooker} — the
 *       organiser block-booking path (J-17). Legacy books one all-day
 *       reservation per discovery candidate on the same club glider, so a
 *       five-candidate day is five deliberately overlapping reservations; the
 *       exclusivity probe would reject candidate #2 onward and fail a
 *       registration the club wants. See that class's javadoc.</li>
 * </ul>
 *
 * <h2>What counts as minting or persisting</h2>
 *
 * <p>The route, not one signature. A rule matching exact declared signatures is
 * decoration: it reds on today's call and stays green on every equivalent way of
 * reaching the same row. So the check is:
 *
 * <ul>
 *   <li>any {@code AircraftReservation.create} overload, and the aggregate's own
 *       constructors — a second factory or a direct {@code new} mints the same
 *       unprobed row;</li>
 *   <li>any {@code save*} method on anything ASSIGNABLE TO the
 *       {@link AircraftReservationRepository} port — which is what catches an
 *       injected {@code reservations.infra.JpaAircraftReservationRepository}
 *       (a public interface), and its inherited {@code saveAll} /
 *       {@code saveAndFlush} alongside {@code save};</li>
 *   <li>{@code EntityManager.persist} / {@code merge} from a class that
 *       references {@link AircraftReservation} at all — the JPA back door around
 *       the repository. The reference is what scopes the clause: classes that
 *       persist other entities are untouched.</li>
 * </ul>
 *
 * <p>Allow-list is keyed by fully-qualified class name, so a new class in either
 * package does not inherit the privilege — including a new class inside
 * {@code reservations} itself, which is the case Spring Modulith cannot help
 * with (it only blocks cross-module reach) and the one this guard exists for.
 * Adding an entry means asserting that the new caller's booking semantics are
 * genuinely non-exclusive — if a member-facing path lands here, double-booking
 * has silently become legal.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReservationExclusivityBypassAllowlistTest {

    private static final Set<String> ALLOWED_CALLERS = Set.of(
            "ch.alpenflight.reservations.application.AircraftReservationsService",
            "ch.alpenflight.publicregistration.application.DiscoveryReservationBooker");

    private static final Set<String> ENTITY_MANAGER_WRITES = Set.of("persist", "merge");

    @ArchTest
    static final ArchRule only_allowlisted_callers_create_or_save_a_reservation =
            noClasses()
                    .that(describe("are not on the reservation-bypass allow-list",
                            (JavaClass c) -> !ALLOWED_CALLERS.contains(c.getFullName())))
                    .should().callCodeUnitWhere(mintsOrPersistsAReservation())
                    .as("Creating or persisting an AircraftReservation outside "
                            + "AircraftReservationsService skips the exclusivity probe — only "
                            + "allow-listed FQNs may, see the class javadoc.");

    private static DescribedPredicate<JavaCall<?>> mintsOrPersistsAReservation() {
        return describe("mint or persist an AircraftReservation",
                call -> mints(call) || savesThroughTheRepository(call) || persistsThroughJpa(call));
    }

    /** The aggregate's own factory necessarily calls its own constructor. */
    private static boolean mints(JavaCall<?> call) {
        if (!call.getTargetOwner().isEquivalentTo(AircraftReservation.class)
                || call.getOriginOwner().isEquivalentTo(AircraftReservation.class)) {
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
