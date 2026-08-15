package ch.alpenflight.persons.application;

import ch.alpenflight.persons.application.PersonDtos.LicenceFlags;
import ch.alpenflight.persons.application.PersonDtos.PersonClubResponse;
import ch.alpenflight.persons.application.PersonDtos.PersonListItem;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupMatch;
import ch.alpenflight.persons.application.PersonDtos.PersonResponse;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.platform.id.PersonId;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class PersonMapper {

    private PersonMapper() {}

    static PersonListItem toListItem(PersonRepository.ListRow row) {
        return new PersonListItem(
                PersonId.of(row.id()),
                row.firstname(),
                row.lastname(),
                row.emailPrivate(),
                row.mobilePhone(),
                row.city(),
                row.zip(),
                row.memberNumber(),
                row.memberStateId(),
                row.memberStateName(),
                row.active(),
                row.motorPilot(),
                row.towPilot(),
                row.gliderInstructor(),
                row.gliderPilot(),
                row.gliderTrainee(),
                row.winchOperator(),
                row.motorInstructor());
    }

    static PersonResponse toResponse(Person p, MemberStateNameLookup memberStateNames, int inOtherClubsCount) {
        return new PersonResponse(
                Objects.requireNonNull(p.getId(), "Cannot map an unpersisted Person"),
                p.getFirstname(),
                p.getLastname(),
                p.getMidname(),
                p.getCompanyName(),
                p.getAddressLine1(),
                p.getAddressLine2(),
                p.getZip(),
                p.getCity(),
                p.getRegion(),
                p.getCountryId(),
                p.getPrivatePhone(),
                p.getMobilePhone(),
                p.getBusinessPhone(),
                p.getFaxNumber(),
                p.getEmailPrivate(),
                p.getEmailBusiness(),
                p.isPreferMailToBusinessMail(),
                p.getBirthday(),
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
                p.getSpotLink(),
                p.isReceiveOwnedAircraftStatisticReports(),
                p.isEnableAddress(),
                p.getActivePersonClubs().stream().map(pc -> toClubResponse(pc, memberStateNames)).toList(),
                inOtherClubsCount);
    }

    static PersonClubResponse toClubResponse(PersonClub pc, MemberStateNameLookup names) {
        return new PersonClubResponse(
                Objects.requireNonNull(pc.getId(), "Cannot map an unpersisted PersonClub"),
                Objects.requireNonNull(pc.getClubId(), "PersonClub is missing clubId"),
                pc.getMemberNumber(),
                pc.getMemberStateId(),
                pc.getMemberStateId() == null ? null : names.nameOf(pc.getMemberStateId()),
                pc.isMotorPilot(),
                pc.isTowPilot(),
                pc.isGliderInstructor(),
                pc.isGliderPilot(),
                pc.isGliderTrainee(),
                pc.isPassenger(),
                pc.isWinchOperator(),
                pc.isMotorInstructor(),
                pc.isReceiveFlightReports(),
                pc.isReceiveAircraftReservationNotifications(),
                pc.isReceivePlanningDayRoleReminder(),
                pc.isActive());
    }

    static PersonLookupMatch toLookupMatch(Person p, boolean alreadyInThisClub) {
        return new PersonLookupMatch(
                Objects.requireNonNull(p.getId(), "Cannot map an unpersisted Person"),
                p.getFirstname(),
                p.getLastname(),
                p.getBirthday(),
                p.getEmailPrivate() != null ? p.getEmailPrivate() : p.getEmailBusiness(),
                alreadyInThisClub);
    }

    static PersonRoleFlags rolesFrom(PersonDtos.PersonClubRequest req) {
        return new PersonRoleFlags(
                req.isMotorPilot(),
                req.isTowPilot(),
                req.isGliderInstructor(),
                req.isGliderPilot(),
                req.isGliderTrainee(),
                req.isPassenger(),
                req.isWinchOperator(),
                req.isMotorInstructor());
    }

    static PersonNotificationPrefs prefsFrom(PersonDtos.PersonClubRequest req) {
        return new PersonNotificationPrefs(
                req.receiveFlightReports(),
                req.receiveAircraftReservationNotifications(),
                req.receivePlanningDayRoleReminder());
    }

    static void applyLicences(Person p, @Nullable LicenceFlags licences) {
        if (licences == null) {
            return;
        }
        p.updateLicences(
                licences.hasMotorPilotLicence(),
                licences.hasTowPilotLicence(),
                licences.hasGliderInstructorLicence(),
                licences.hasGliderPilotLicence(),
                licences.hasGliderTraineeLicence(),
                licences.hasGliderPaxLicence(),
                licences.hasTmgLicence(),
                licences.hasWinchOperatorLicence(),
                licences.hasMotorInstructorLicence(),
                licences.hasPartMLicence(),
                licences.licenceNumber(),
                licences.medicalClass1ExpireDate(),
                licences.medicalClass2ExpireDate(),
                licences.medicalLaplExpireDate(),
                licences.gliderInstructorLicenceExpireDate(),
                licences.motorInstructorLicenceExpireDate(),
                licences.partMLicenceExpireDate(),
                licences.hasGliderTowingStartPermission(),
                licences.hasGliderSelfStartPermission(),
                licences.hasGliderWinchStartPermission(),
                p.isReceiveOwnedAircraftStatisticReports());
    }

    interface MemberStateNameLookup {
        @Nullable String nameOf(java.util.UUID memberStateId);

        static MemberStateNameLookup empty() {
            return id -> null;
        }

        static MemberStateNameLookup fromList(List<? extends MemberStateNameRow> rows) {
            return id -> rows.stream()
                    .filter(r -> id.equals(r.id()))
                    .map(MemberStateNameRow::name)
                    .findFirst()
                    .orElse(null);
        }
    }

    interface MemberStateNameRow {
        java.util.UUID id();
        String name();
    }
}
