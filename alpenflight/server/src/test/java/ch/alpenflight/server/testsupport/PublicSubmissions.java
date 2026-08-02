package ch.alpenflight.server.testsupport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The submissions the ITs post to the anonymous registration surface, as the
 * JSON a browser sends rather than as the server's own request records — a
 * rename or a restructured payload has to break these tests, not silently
 * follow them.
 *
 * <p>Both bodies carry the same registrant, which is the contract under test:
 * the scenic form is the discovery form minus the day selection.
 *
 * <p>Shared test support rather than a helper inside the publicregistration
 * package: the surface is also driven from outside it (the audit projection
 * needs an anonymous write), and a second copy of the payload would let the
 * two drift.
 */
public final class PublicSubmissions {

    public static final String FIRSTNAME = "Rosa";
    public static final String LASTNAME = "Renggli";

    private PublicSubmissions() {}

    public static Map<String, Object> discoveryBody(LocalDate selectedDay) {
        Map<String, Object> body = scenicBody();
        body.put("selectedDay", selectedDay.toString());
        return body;
    }

    public static Map<String, Object> scenicBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registrant", registrant());
        return body;
    }

    public static Map<String, Object> registrant() {
        Map<String, Object> registrant = new LinkedHashMap<>();
        registrant.put("firstname", FIRSTNAME);
        registrant.put("lastname", LASTNAME);
        registrant.put("addressLine1", "Flugplatzstrasse 7");
        registrant.put("zip", "6060");
        registrant.put("city", "Sarnen");
        registrant.put("mobilePhone", "079 555 66 77");
        registrant.put("privateEmail", "rosa.renggli@example.ch");
        registrant.put("invoiceAddressIsSame", true);
        registrant.put("sendCouponToInvoiceAddress", false);
        return registrant;
    }
}
