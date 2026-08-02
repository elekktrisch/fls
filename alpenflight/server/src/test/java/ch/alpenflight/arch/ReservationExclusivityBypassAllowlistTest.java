package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
 * <p>Allow-list is keyed by fully-qualified class name, so a new class in either
 * package does not inherit the privilege. Adding an entry means asserting that
 * the new caller's booking semantics are genuinely non-exclusive — if a
 * member-facing path lands here, double-booking has silently become legal.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReservationExclusivityBypassAllowlistTest {

    private static final Set<String> ALLOWED_CALLERS = Set.of(
            "ch.alpenflight.reservations.application.AircraftReservationsService",
            "ch.alpenflight.publicregistration.application.DiscoveryReservationBooker");

    @ArchTest
    static final ArchRule only_allowlisted_callers_create_or_save_a_reservation =
            noClasses()
                    .that(describe("are not on the reservation-bypass allow-list",
                            (JavaClass c) -> !ALLOWED_CALLERS.contains(c.getFullName())))
                    .should().callMethod(AircraftReservation.class, "create",
                            UUID.class, UUID.class, UUID.class, UUID.class, UUID.class, UUID.class,
                            Instant.class, Instant.class, boolean.class, UUID.class, String.class)
                    .orShould().callMethod(AircraftReservationRepository.class, "save",
                            AircraftReservation.class)
                    .as("Creating or persisting an AircraftReservation outside "
                            + "AircraftReservationsService skips the exclusivity probe — only "
                            + "allow-listed FQNs may, see the class javadoc.");
}
