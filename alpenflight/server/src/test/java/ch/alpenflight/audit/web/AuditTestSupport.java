package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.jdbc.core.JdbcTemplate;

final class AuditTestSupport {

    static final String SEED_COUNTRY_ID = "019e2e15-2c00-74be-8000-0000000004be";
    static final String SEED_CLUB_STATE_ID = "019e2e15-2c00-7bb8-8000-000000000bb8";

    static final ObjectMapper JSON = new ObjectMapper();

    private AuditTestSupport() {}

    static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    static String shortSuffix() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }

    static Map<String, Object> clubCreatePayload(String name, String slug, String clubKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("slug", slug);
        body.put("clubKey", clubKey);
        body.put("publicRegistrationEnabled", false);
        body.put("countryId", SEED_COUNTRY_ID);
        body.put("clubStateId", SEED_CLUB_STATE_ID);
        return body;
    }

    static <T extends RequestEntity.BodyBuilder> T withBearer(T builder, String token) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    static RequestEntity.HeadersBuilder<?> withBearer(RequestEntity.HeadersBuilder<?> builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    static void truncateForTenant(JdbcTemplate jdbc, UUID tenantClubId) {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                tenantClubId.toString());
    }

    static List<Map<String, Object>> findByTenant(JdbcTemplate jdbc, UUID tenantClubId) {
        return jdbc.queryForList(
                "SELECT * FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid "
                        + "ORDER BY occurred_at ASC",
                tenantClubId.toString());
    }

    static List<Map<String, Object>> findByTarget(JdbcTemplate jdbc,
                                                  UUID tenantClubId,
                                                  UUID targetEntityId) {
        return jdbc.queryForList(
                "SELECT * FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND target_entity_id = ?::uuid "
                        + "ORDER BY occurred_at ASC",
                tenantClubId.toString(),
                targetEntityId.toString());
    }

    static JsonNode parseSnapshot(Object jsonbColumnValue) {
        if (jsonbColumnValue == null) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(jsonbColumnValue.toString());
        } catch (Exception e) {
            throw new AssertionError("audit row snapshot is not valid JSON: " + jsonbColumnValue, e);
        }
    }

    static Map<String, Object> assertSingleEventForTarget(JdbcTemplate jdbc,
                                                          UUID tenantClubId,
                                                          UUID targetEntityId) {
        List<Map<String, Object>> rows = findByTarget(jdbc, tenantClubId, targetEntityId);
        assertThat(rows)
                .as("Expected exactly one audit row for tenant=%s target=%s but got: %s",
                        tenantClubId, targetEntityId, rows)
                .hasSize(1);
        return rows.get(0);
    }
}
