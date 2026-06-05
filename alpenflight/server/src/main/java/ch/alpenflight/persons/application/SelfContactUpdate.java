package ch.alpenflight.persons.application;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Command for {@link PersonsService#updateOwnContact} — the caller-scoped
 * Person-contact self-edit (J-4 T-06). Carries ONLY the contact / address
 * fields of the Person aggregate.
 *
 * <p>Deliberately absent: the name fields (firstname / lastname / midname /
 * companyName) — rename stays admin-only and {@code updateOwnContact}
 * preserves them unchanged. Also absent: {@code spotLink} and
 * {@code enableAddress} (carried by {@link ch.alpenflight.persons.domain.Person#updateContact}
 * but not part of the contact/address self-edit surface) — the service reads
 * their existing values from the aggregate and passes them back through
 * unchanged, the same way the Account self-edit preserves {@code remarks}.
 */
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
