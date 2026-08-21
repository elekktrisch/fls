package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.persons.domain.Person;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PiiRedactorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class ClubSnapshot {
        String name = "Alps Soaring";
        String slug = "alps";
        String clubKey = "ALP01";
        @AuditRedact String operatorMobile = "+41 79 555 12 34";
        String hiddenField = "should-be-redacted-default-deny";
    }

    static class ChildSnap {
        String safe = "ok-to-log";
        String sensitive = "secret-data";
    }

    static class OuterSnap {
        String topField = "outer-value";
        ChildSnap child = new ChildSnap();
    }

    @Test
    void allow_listed_fields_emit_verbatim() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", EntityPolicy.allowing(List.of("name", "slug", "clubKey"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("name").asText()).isEqualTo("Alps Soaring");
        assertThat(out.get("slug").asText()).isEqualTo("alps");
        assertThat(out.get("clubKey").asText()).isEqualTo("ALP01");
    }

    @Test
    void non_allow_listed_field_appears_as_redacted_sentinel() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", EntityPolicy.allowing(List.of("name"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("hiddenField").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("clubKey").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void audit_redact_annotation_overrides_any_allow_entry() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", EntityPolicy.allowing(List.of("name", "operatorMobile"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("operatorMobile").asText())
                .as("@AuditRedact wins over the allow-list — drift-resistance")
                .isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void deny_all_redacts_every_field_for_listed_entity_type() {
        PiiRedactor redactor = redactorFor(
                Map.of("Person", EntityPolicy.allowing(List.of("firstName", "lastName", "email"))),
                List.of("Person"));

        @SuppressWarnings("UnusedVariable")
        class PersonSnapshot {
            String firstName = "Anna";
            String lastName = "Müller";
            String email = "anna@example.ch";
        }

        JsonNode out = parse(redactor.serialize("Person", new PersonSnapshot()));

        assertThat(out.get("firstName").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("lastName").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("email").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void nested_field_recurses_under_runtime_type_simple_name() {
        PiiRedactor redactor = redactorFor(
                Map.of(
                        "Outer", EntityPolicy.allowing(List.of("topField", "child")),
                        "ChildSnap", EntityPolicy.allowing(List.of("safe"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Outer", new OuterSnap()));

        JsonNode child = out.get("child");
        assertThat(child.get("safe").asText()).isEqualTo("ok-to-log");
        assertThat(child.get("sensitive").asText())
                .as("the outer policy allows \"child\" wholesale, yet the nested field is still "
                        + "redacted — without the recursion it would land in jsonb verbatim")
                .isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void unknown_nested_type_defaults_to_full_deny() {
        PiiRedactor redactor = redactorFor(
                Map.of("Outer", EntityPolicy.allowing(List.of("topField", "child"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Outer", new OuterSnap()));

        JsonNode child = out.get("child");
        assertThat(child.get("safe").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(child.get("sensitive").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void unknown_entity_type_redacts_every_field() {
        PiiRedactor redactor = redactorFor(Map.of(), List.of());

        JsonNode out = parse(redactor.serialize("MysteryEntity", new ClubSnapshot()));

        assertThat(out.get("name").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("slug").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void the_shipped_policy_denies_person_pii_and_still_emits_an_allow_listed_location_name()
            throws IOException {
        PiiRedactor shipped = new PiiRedactor(ShippedRedactionPolicy.properties(), JSON);

        Person person = Person.register("Anna", "Muller-Tschudi", null);
        person.updateContact("Bahnhofstrasse 1", null, "3013", "Bern", "BE",
                UUID.randomUUID(), "+41 31 555 00 11", "+41 79 555 00 11", null, null,
                "anna.mueller@example.ch", null, false, LocalDate.of(1988, 4, 12), null, true);

        String personSnapshot = shipped.serialize("Person", person);

        assertThat(personSnapshot)
                .as("Person is deny-all in the shipped policy on privacy grounds, so /system/logs "
                        + "records that a Person changed without storing the person's data")
                .contains("\"firstname\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .contains("\"lastname\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .contains("\"emailPrivate\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .contains("\"birthday\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .contains("\"mobilePhone\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .doesNotContain("Anna", "Muller-Tschudi", "anna.mueller@example.ch",
                        "1988-04-12", "Bahnhofstrasse 1", "+41 79 555 00 11");

        Location location = Location.create("Bern-Belp", "BELP", UUID.randomUUID(), UUID.randomUUID(),
                "LSZB", null, null, null, null, null, null, null, null, null, null,
                false, false, false);

        assertThat(shipped.serialize("Location", location))
                .as("the control: the same shipped policy emits an allow-listed field verbatim, so "
                        + "the Person assertion cannot pass because the redactor redacts everything")
                .contains("\"locationName\":\"Bern-Belp\"");
    }

    @Test
    void null_snapshot_returns_null_payload() {
        PiiRedactor redactor = redactorFor(Map.of(), List.of());
        assertThat(redactor.serialize("Club", null)).isNull();
    }

    private static PiiRedactor redactorFor(Map<String, EntityPolicy> entities, List<String> denyAll) {
        return new PiiRedactor(new AuditRedactionProperties(entities, denyAll), JSON);
    }

    private static JsonNode parse(String json) {
        try {
            assertThat(json).isNotNull();
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("Redactor returned non-JSON: " + json, e);
        }
    }
}
