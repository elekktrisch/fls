package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.Person;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Read projection of a Person's editable licence/medical shape — returned by
 * the caller-scoped {@code GET /api/v1/me/person/licences} (so the Pilot tab,
 * J-4 T-09, hydrates) and ALSO used as the lean, Keycloak-free before/after
 * snapshot handed to the audit trail on the licence self-edit (J-4 T-08, AC4).
 *
 * <p>Projects exactly the fields {@link Person#updateLicences} can mutate. The
 * audit listener keys redaction off the {@code entityType} string the service
 * passes ({@code "PersonLicences"}); that entity type carries an explicit
 * allow-list in {@code audit.redaction.entities} so the before/after diff is
 * READABLE by a sysadmin (AC4) rather than {@code [redacted]} — distinct from
 * the {@code Person} entity type, which stays in {@code audit.redaction.deny-all}.
 * Medical-field PII-redaction (which dates emitted vs hashed) is deferred per
 * S-182's open questions; for now the dates emit verbatim in the diff.
 */
public record SelfLicencesView(
        boolean hasMotorPilotLicence,
        boolean hasTowPilotLicence,
        boolean hasGliderInstructorLicence,
        boolean hasGliderPilotLicence,
        boolean hasGliderTraineeLicence,
        boolean hasGliderPaxLicence,
        boolean hasTmgLicence,
        boolean hasWinchOperatorLicence,
        boolean hasMotorInstructorLicence,
        boolean hasPartMLicence,
        @Nullable String licenceNumber,
        @Nullable LocalDate medicalClass1ExpireDate,
        @Nullable LocalDate medicalClass2ExpireDate,
        @Nullable LocalDate medicalLaplExpireDate,
        @Nullable LocalDate gliderInstructorLicenceExpireDate,
        @Nullable LocalDate motorInstructorLicenceExpireDate,
        @Nullable LocalDate partMLicenceExpireDate,
        boolean hasGliderTowingStartPermission,
        boolean hasGliderSelfStartPermission,
        boolean hasGliderWinchStartPermission,
        boolean receiveOwnedAircraftStatisticReports) {

    public static SelfLicencesView of(Person p) {
        return new SelfLicencesView(
                p.hasMotorPilotLicence(),
                p.hasTowPilotLicence(),
                p.hasGliderInstructorLicence(),
                p.hasGliderPilotLicence(),
                p.hasGliderTraineeLicence(),
                p.hasGliderPaxLicence(),
                p.hasTmgLicence(),
                p.hasWinchOperatorLicence(),
                p.hasMotorInstructorLicence(),
                p.hasPartMLicence(),
                p.getLicenceNumber(),
                p.getMedicalClass1ExpireDate(),
                p.getMedicalClass2ExpireDate(),
                p.getMedicalLaplExpireDate(),
                p.getGliderInstructorLicenceExpireDate(),
                p.getMotorInstructorLicenceExpireDate(),
                p.getPartMLicenceExpireDate(),
                p.hasGliderTowingStartPermission(),
                p.hasGliderSelfStartPermission(),
                p.hasGliderWinchStartPermission(),
                p.isReceiveOwnedAircraftStatisticReports());
    }
}
