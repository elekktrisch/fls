package ch.alpenflight.persons.application;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SelfContactUpdate(
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String zip,
        @Nullable String city,
        @Nullable String region,
        @Nullable UUID countryId,
        @Nullable String privatePhone,
        @Nullable String mobilePhone,
        @Nullable String businessPhone,
        @Nullable String faxNumber,
        @Nullable String emailPrivate,
        @Nullable String emailBusiness,
        boolean preferMailToBusinessMail,
        @Nullable LocalDate birthday) {}
