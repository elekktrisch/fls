package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import ch.alpenflight.audit.domain.AuditRedact;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the {@link PiiRedactor} contract:
 *
 * <ul>
 *   <li>fields on the entity-type allow-list are emitted verbatim;</li>
 *   <li>any field NOT on the allow-list is replaced by the {@code [redacted]}
 *       sentinel — default-deny;</li>
 *   <li>fields carrying {@link AuditRedact} are unconditionally redacted
 *       even when the policy would otherwise allow them;</li>
 *   <li>entity-types named in {@code deny-all} have every field redacted
 *       regardless of any per-entity allow-list entry.</li>
 * </ul>
 */
class PiiRedactorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class ClubSnapshot {
        String name = "Alps Soaring";
        String slug = "alps";
        String clubKey = "ALP01";
        @AuditRedact String operatorMobile = "+41 79 555 12 34";
        String hiddenField = "should-be-redacted-default-deny";
    }

    @Test
    void allow_listed_fields_emit_verbatim() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", new EntityPolicy(List.of("name", "slug", "clubKey"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("name").asText()).isEqualTo("Alps Soaring");
        assertThat(out.get("slug").asText()).isEqualTo("alps");
        assertThat(out.get("clubKey").asText()).isEqualTo("ALP01");
    }

    @Test
    void non_allow_listed_field_appears_as_redacted_sentinel() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", new EntityPolicy(List.of("name"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("hiddenField").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("clubKey").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void audit_redact_annotation_overrides_any_allow_entry() {
        PiiRedactor redactor = redactorFor(
                Map.of("Club", new EntityPolicy(List.of("name", "operatorMobile"))),
                List.of());

        JsonNode out = parse(redactor.serialize("Club", new ClubSnapshot()));

        assertThat(out.get("operatorMobile").asText())
                .as("@AuditRedact wins over the allow-list — drift-resistance")
                .isEqualTo(PiiRedactor.REDACTED_SENTINEL);
    }

    @Test
    void deny_all_redacts_every_field_for_listed_entity_type() {
        PiiRedactor redactor = redactorFor(
                Map.of("Person", new EntityPolicy(List.of("firstName", "lastName", "email"))),
                List.of("Person"));

        @SuppressWarnings("UnusedVariable") // walked reflectively by PiiRedactor
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
    void unknown_entity_type_redacts_every_field() {
        PiiRedactor redactor = redactorFor(Map.of(), List.of());

        JsonNode out = parse(redactor.serialize("MysteryEntity", new ClubSnapshot()));

        assertThat(out.get("name").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
        assertThat(out.get("slug").asText()).isEqualTo(PiiRedactor.REDACTED_SENTINEL);
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
