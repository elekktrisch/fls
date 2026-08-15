package ch.alpenflight.persons.application;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

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
