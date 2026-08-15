package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserMapperTest extends AbstractMapperContractTest<UserMapper> {

    private final UserMapper mapper = new UserMapper();

    @Override
    protected UserMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("UserId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("UserName", faker.internet().username());
        row.put("FriendlyName", faker.name().fullName());
        row.put("PersonId", randomUuidString(faker));
        row.put("NotificationEmail", faker.internet().emailAddress());
        row.put("PhoneNumber", faker.phoneNumber().phoneNumber());
        row.put("Remarks", faker.lorem().sentence());
        row.put("LanguageId", faker.random().nextInt(1, 1_000_000));
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesUserEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.USER);
    }

    @Test
    void declaresClubPersonLanguageAsStructuralForeignKeys() {
        assertThat(mapper.foreignKeyTargets())
                .containsExactly(EntityType.CLUB, EntityType.PERSON, EntityType.LANGUAGE);
    }

    @Test
    void columnsExcludeEveryPasswordAndKeycloakShadowColumn() {
        assertThat(mapper.wireColumns())
                .as("Password material + Keycloak shadow account state never crosses "
                        + "the bundle boundary — Keycloak owns these per ADR 0007.")
                .doesNotContain(
                        "password",
                        "password_hash",
                        "last_password_change_on",
                        "force_password_change_next_logon",
                        "failed_login_counts",
                        "access_failed_count",
                        "account_state",
                        "email_confirmed",
                        "phone_number_confirmed",
                        "two_factor_enabled",
                        "lockout_enabled",
                        "lockout_end_date_utc",
                        "security_stamp");
    }

    @Test
    void forbiddenLegacyColumnSetCoversAspNetIdentityShadow() {
        assertThat(UserMapper.LEGACY_COLUMNS_FORBIDDEN_IN_PRODUCER_SELECT)
                .as("The producer-side SELECT must not project these legacy "
                        + "ASP.NET-Identity columns — defense-in-depth against "
                        + "accidental NDJSON leak.")
                .contains("Password", "PasswordHash", "LastPasswordChangeOn",
                        "ForcePasswordChangeNextLogon", "FailedLoginCounts",
                        "AccessFailedCount", "AccountState",
                        "EmailConfirmed", "PhoneNumberConfirmed",
                        "TwoFactorEnabled", "LockoutEnabled", "LockoutEndDateUtc",
                        "SecurityStamp");
    }

    @Test
    void readEntityWithNullPersonIdBindsSqlNull() throws Exception {
        Map<Integer, Object> binds = captureBinds(legacyRowWithNullPersonIdAndKcSub());
        assertThat(binds.get(positionOf(mapper, UserMapper.PERSON_ID)))
                .as("person_id must bind SQL NULL — preserves the pre-S-052 "
                        + "service-account case without synthesising a stub Person")
                .isNull();
    }

    @Test
    void readEntityBindsKeycloakSubAsNullForS028Mint() throws Exception {
        Map<Integer, Object> binds = captureBinds(legacyRowWithNullPersonIdAndKcSub());
        assertThat(binds.get(positionOf(mapper, UserMapper.KEYCLOAK_SUB)))
                .as("keycloak_sub must bind SQL NULL at bundle ingest — S-028's "
                        + "BulkUserProvisioningService is the only NULL→UUID writer for "
                        + "this column; binding a value here would create a double-write "
                        + "race per ADR 0007.")
                .isNull();
    }

    private static ObjectNode legacyRowWithNullPersonIdAndKcSub() {
        ObjectNode emitted = new ObjectMapper().createObjectNode();
        emitted.put(UserMapper.LEGACY_GUID, UUID.randomUUID().toString());
        emitted.put(UserMapper.CLUB_ID, UUID.randomUUID().toString());
        emitted.put(UserMapper.USERNAME, "service-account");
        emitted.put(UserMapper.FRIENDLY_NAME, "Service Account");
        emitted.putNull(UserMapper.PERSON_ID);
        emitted.put(UserMapper.NOTIFICATION_EMAIL, "ops@example.test");
        emitted.putNull(UserMapper.PHONE_NUMBER);
        emitted.putNull(UserMapper.REMARKS);
        emitted.put(UserMapper.LANGUAGE_ID, new UUID(0L, 1L).toString());
        emitted.putNull(UserMapper.KEYCLOAK_SUB);
        emitted.put(UserMapper.CREATED_ON, "2024-01-01T12:00:00Z");
        emitted.put(UserMapper.CREATED_BY_USER_ID, UUID.randomUUID().toString());
        emitted.putNull(UserMapper.MODIFIED_ON);
        emitted.putNull(UserMapper.MODIFIED_BY_USER_ID);
        emitted.putNull(UserMapper.DELETED_ON);
        emitted.putNull(UserMapper.DELETED_BY_USER_ID);
        return emitted;
    }

    private Map<Integer, Object> captureBinds(ObjectNode emitted) throws Exception {
        Map<Integer, Object> binds = new TreeMap<>();
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        org.mockito.stubbing.Answer<Void> capture = invocation -> {
            binds.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        };
        doAnswer(capture).when(ps).setObject(anyInt(), any());
        doAnswer(capture).when(ps).setString(anyInt(), any());
        doAnswer(capture).when(ps).setTimestamp(anyInt(), any());
        mapper.readEntity(emitted, ps);
        return binds;
    }

}
