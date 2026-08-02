package ch.alpenflight.publicregistration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.publicregistration.application.PublicRegistrantDetails.InvoiceRecipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The field contract legacy enforced only in the browser. Legacy's DTO marked
 * just club key / firstname / lastname {@code [Required]}, so an API caller
 * could post a registration with no address and no contact channel; per
 * ADR 0022 directive 2 these are invariants of the command, and each one is
 * proved to actually reject rather than being documented prose.
 */
class PublicRegistrantDetailsTest {

    @ParameterizedTest
    @ValueSource(strings = {"firstname", "lastname", "addressLine1", "zip", "city"})
    void a_blank_required_field_is_rejected(String field) {
        assertThatThrownBy(() -> registrantWithout(field))
                .isInstanceOf(PublicRegistrationInvalidException.class)
                .hasMessageContaining(field);
    }

    @Test
    void a_registrant_reachable_by_neither_mobile_nor_email_is_rejected() {
        assertThatThrownBy(() -> registrant(null, null, true, null))
                .isInstanceOf(PublicRegistrationInvalidException.class)
                .hasMessageContaining("mobilePhone");
    }

    @Test
    void either_contact_channel_alone_is_enough() {
        assertThatCode(() -> registrant("079 555 66 77", null, true, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> registrant(null, "rosa@example.ch", true, null))
                .doesNotThrowAnyException();
    }

    @Test
    void a_differing_invoice_address_requires_the_invoice_block() {
        assertThatThrownBy(() -> registrant("079 555 66 77", null, false, null))
                .isInstanceOf(PublicRegistrationInvalidException.class)
                .hasMessageContaining("invoiceRecipient");
        assertThatThrownBy(() -> new InvoiceRecipient(
                "Beat", "Bezahler", "Buchhaltungsweg 3", "6003", "Luzern", null, "  "))
                .isInstanceOf(PublicRegistrationInvalidException.class)
                .hasMessageContaining("notificationEmail");
    }

    /**
     * A form that hid the invoice block may still post whatever was typed into
     * it; the flag is the switch, so the block is dropped rather than turned
     * into a second Person nobody asked for.
     */
    @Test
    void an_invoice_block_posted_alongside_sameAddress_is_dropped() {
        PublicRegistrantDetails details = registrant("079 555 66 77", null, true,
                new InvoiceRecipient("Beat", "Bezahler", "Buchhaltungsweg 3", "6003",
                        "Luzern", null, "beat@example.ch"));

        assertThat(details.invoiceRecipient()).isNull();
    }

    @Test
    void values_are_trimmed() {
        PublicRegistrantDetails details = new PublicRegistrantDetails(
                "  Rosa  ", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                "   ", null, "079 555 66 77", null, null, true, null);

        assertThat(details.firstname()).isEqualTo("Rosa");
        assertThat(details.privatePhone()).isNull();
    }

    private static PublicRegistrantDetails registrant(String mobilePhone, String privateEmail,
            boolean invoiceAddressIsSame, InvoiceRecipient invoiceRecipient) {
        return new PublicRegistrantDetails(
                "Rosa", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                null, null, mobilePhone, privateEmail, null,
                invoiceAddressIsSame, invoiceRecipient);
    }

    private static PublicRegistrantDetails registrantWithout(String blankedField) {
        return new PublicRegistrantDetails(
                blankUnless("firstname", blankedField, "Rosa"),
                blankUnless("lastname", blankedField, "Renggli"),
                blankUnless("addressLine1", blankedField, "Flugplatzstrasse 7"),
                blankUnless("zip", blankedField, "6060"),
                blankUnless("city", blankedField, "Sarnen"),
                null, null, null, "079 555 66 77", null, null, true, null);
    }

    private static String blankUnless(String field, String blankedField, String value) {
        return field.equals(blankedField) ? "  " : value;
    }
}
