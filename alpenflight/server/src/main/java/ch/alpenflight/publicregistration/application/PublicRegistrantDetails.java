package ch.alpenflight.publicregistration.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The registrant an anonymous submission describes, shared by both public
 * flows — the scenic form is the discovery form minus the day selection, so
 * the registrant contract is one type.
 *
 * <h2>The field contract is enforced here, not only in the browser</h2>
 *
 * <p>Legacy required only club key, firstname and lastname server-side while
 * its HTML required far more ({@code tryflight.html:25,35,47,59,69,101,111}),
 * so a direct API caller could post a registration with no address and no way
 * to reach the registrant back. Per ADR 0022 directive 2 those rules belong on
 * the aggregate, so they are invariants of this command: address, zip and city
 * are required, and at least one of mobile phone / private email must be
 * present — a registration nobody can be contacted about is worthless to the
 * club that receives it.
 *
 * <p>Length caps and email shape are deliberately NOT re-checked here; the
 * {@code Person} aggregate already rejects both, and duplicating them would
 * fork the limit.
 *
 * <p>{@code remarks} is the one field that is never persisted: legacy carries
 * it straight into the two notification mails and stores nothing
 * ({@code RegistrationService.cs:109-131}), so it lives on the command rather
 * than on {@code Person}.
 *
 * <p>{@code sendCouponToInvoiceAddress} is likewise mail-only: it picks which
 * of the two people the club posts the voucher to, which only exists as a
 * choice while the invoice address differs.
 */
public record PublicRegistrantDetails(
        String firstname,
        String lastname,
        String addressLine1,
        String zip,
        String city,
        @Nullable UUID countryId,
        @Nullable String privatePhone,
        @Nullable String businessPhone,
        @Nullable String mobilePhone,
        @Nullable String privateEmail,
        @Nullable String remarks,
        boolean invoiceAddressIsSame,
        boolean sendCouponToInvoiceAddress,
        @Nullable InvoiceRecipient invoiceRecipient) {

    public PublicRegistrantDetails {
        firstname = required(firstname, "firstname");
        lastname = required(lastname, "lastname");
        addressLine1 = required(addressLine1, "addressLine1");
        zip = required(zip, "zip");
        city = required(city, "city");
        privatePhone = trimToNull(privatePhone);
        businessPhone = trimToNull(businessPhone);
        mobilePhone = trimToNull(mobilePhone);
        privateEmail = trimToNull(privateEmail);
        remarks = trimToNull(remarks);
        if (mobilePhone == null && privateEmail == null) {
            throw new PublicRegistrationInvalidException(
                    "at least one of mobilePhone / privateEmail is required");
        }
        // The flag is the switch, matching legacy: a browser that hid the
        // invoice block may still post whatever was typed into it before the
        // registrant ticked "same address". Dropping it here makes "no second
        // Person when the addresses match" structural rather than a branch the
        // writer has to remember.
        if (invoiceAddressIsSame) {
            invoiceRecipient = null;
            // There is no second recipient to post the voucher to, so the flag
            // cannot mean anything — legacy re-tests InvoiceAddressIsSame at
            // every read site (RegistrationEmailBuildService.cs:71,135,199,259)
            // instead, which is the same rule spelled four times.
            sendCouponToInvoiceAddress = false;
        } else if (invoiceRecipient == null) {
            throw new PublicRegistrationInvalidException(
                    "invoiceRecipient is required when the invoice address differs");
        }
    }

    /**
     * Where the invoice goes when it is not the registrant's own address. The
     * notification email doubles as this person's private email, as legacy has
     * it ({@code RegistrationService.cs:143}), and is the address the
     * confirmation mail is sent to.
     */
    public record InvoiceRecipient(
            String firstname,
            String lastname,
            String addressLine1,
            String zip,
            String city,
            @Nullable UUID countryId,
            String notificationEmail) {

        public InvoiceRecipient {
            firstname = required(firstname, "invoice firstname");
            lastname = required(lastname, "invoice lastname");
            addressLine1 = required(addressLine1, "invoice addressLine1");
            zip = required(zip, "invoice zip");
            city = required(city, "invoice city");
            notificationEmail = required(notificationEmail, "invoice notificationEmail");
        }
    }

    private static String required(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new PublicRegistrationInvalidException(field + " is required");
        }
        return trimmed;
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
