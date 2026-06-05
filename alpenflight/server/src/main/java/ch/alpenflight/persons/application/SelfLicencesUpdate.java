package ch.alpenflight.persons.application;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Command for {@link PersonsService#updateOwnLicences} — the caller-scoped
 * Person licence/medical self-edit (J-4 T-08, the FADP-sensitive Pilot tab).
 * Carries ONLY the licence flags, the licence number, the medical / instructor
 * / part-M expiry dates and the glider start-permission flags of the Person
 * aggregate.
 *
 * <p>Deliberately absent: contact / address fields (their own
 * {@code PATCH /me/person} surface, S-051 T-06), the name fields (admin-only),
 * and membership / role fields (admin-only). Binding to this command rather
 * than the {@link ch.alpenflight.persons.domain.Person} aggregate makes
 * mass-assignment of those fields structurally impossible.
 */
public record SelfLicencesUpdate(
        boolean motorPilot,
        boolean towPilot,
        boolean gliderInstructor,
        boolean gliderPilot,
        boolean gliderTrainee,
        boolean gliderPax,
        boolean tmg,
        boolean winchOperator,
        boolean motorInstructor,
        boolean partM,
        @Nullable String licenceNumber,
        @Nullable LocalDate medicalClass1ExpireDate,
        @Nullable LocalDate medicalClass2ExpireDate,
        @Nullable LocalDate medicalLaplExpireDate,
        @Nullable LocalDate gliderInstructorLicenceExpireDate,
        @Nullable LocalDate motorInstructorLicenceExpireDate,
        @Nullable LocalDate partMLicenceExpireDate,
        boolean gliderTowingStartPermission,
        boolean gliderSelfStartPermission,
        boolean gliderWinchStartPermission,
        boolean receiveOwnedAircraftStatisticReports) {}
