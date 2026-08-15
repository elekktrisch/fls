package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Person-contact self-edit payload — caller's own contact / address fields only.")
record MePersonUpdateRequest(
        @Schema(description = "Street address, line 1.")
        @Nullable @Size(max = 200) String addressLine1,
        @Schema(description = "Street address, line 2.")
        @Nullable @Size(max = 200) String addressLine2,
        @Schema(description = "Postal / ZIP code.")
        @Nullable @Size(max = 10) String zip,
        @Schema(description = "City / town.")
        @Nullable @Size(max = 100) String city,
        @Schema(description = "Region / canton / state.")
        @Nullable @Size(max = 100) String region,
        @Schema(description = "Country reference id.")
        @Nullable UUID countryId,
        @Schema(description = "Private phone number.")
        @Nullable @Size(max = 30) String privatePhone,
        @Schema(description = "Mobile phone number.")
        @Nullable @Size(max = 30) String mobilePhone,
        @Schema(description = "Business phone number.")
        @Nullable @Size(max = 30) String businessPhone,
        @Schema(description = "Fax number.")
        @Nullable @Size(max = 30) String faxNumber,
        @Schema(description = "Private email address.")
        @Nullable @Email @Size(max = 256) String emailPrivate,
        @Schema(description = "Business email address.")
        @Nullable @Email @Size(max = 256) String emailBusiness,
        @Schema(description = "Prefer the business email for club correspondence (absent = false).")
        @Nullable Boolean preferMailToBusinessMail,
        @Schema(description = "Date of birth.")
        @Nullable LocalDate birthday) {}
