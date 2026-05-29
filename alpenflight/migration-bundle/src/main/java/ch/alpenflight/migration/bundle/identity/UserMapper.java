package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-bound principal subject: legacy {@code Users.UserId} →
 * {@code t_user.id}.
 *
 * <p>FK shape:
 * <ul>
 *   <li>{@code club_id} → CLUB (home club; NOT a @TenantId discriminator
 *       per the V2 schema comment).</li>
 *   <li>{@code person_id} → PERSON (nullable; cross-tenant escape hatch
 *       via {@code EntityPolicy.tenantBypassFks}).</li>
 *   <li>{@code language_id} → LANGUAGE (INT → synthetic UUID via
 *       {@link Coercions#legacyIntIdToUuidString}). Drift outside the V2
 *       seed set fails closed at S-141 ingest with
 *       {@code BUNDLE_LANGUAGE_NOT_SEEDED}.</li>
 * </ul>
 *
 * <p><strong>Password + Keycloak-shadow deny-list.</strong> Keycloak owns
 * password storage, MFA, lockout, and account state per ADR 0007. The
 * legacy ASP.NET-Identity shadow columns MUST NEVER leave the legacy DB.
 * {@link #FORBIDDEN_LEGACY_COLUMNS} enumerates the producer-side
 * {@code SELECT} deny-list as defense-in-depth — the bundle NDJSON never
 * carries what the destination does not model. A test asserts these
 * names do not appear in {@link #columns()}.
 *
 * <p>{@code keycloak_sub} ships as NULL — S-028's
 * {@code BulkUserProvisioningService} is the only writer that flips
 * NULL → UUID. Adding {@code keycloak_sub} to the bundle column list
 * would create a double-write race; the column is structurally
 * always-null at bundle time and marked {@link ParityIgnore}.
 *
 * <p>The system-actor row {@link #LEGACY_SYSTEM_USER_ID} is filtered out
 * at producer time (legacy {@code WHERE UserId != ...}); audit references
 * to it land in S-186's orphan-actor synthesis path rather than crossing
 * the boundary as a Keycloak-less user (ADR 0007 violation).
 */
public final class UserMapper implements Mapper {

    /**
     * Legacy ASP.NET Identity system actor — the synthetic principal that
     * legacy uses for cron / workflow-triggered audit writes. It has no
     * Keycloak counterpart and MUST NOT cross the bundle boundary; the
     * producer-side {@code SELECT} filters {@code WHERE UserId != ...}.
     * S-186 orphan-actor synthesis reroutes audit references.
     */
    public static final UUID LEGACY_SYSTEM_USER_ID =
            UUID.fromString("13731ee2-c1d8-455c-8ad1-c39399893fff");

    static final String LEGACY_GUID = "legacy_guid";
    static final String CLUB_ID = "club_id";
    static final String USERNAME = "username";
    static final String FRIENDLY_NAME = "friendly_name";
    static final String PERSON_ID = "person_id";

    /**
     * @ParityIgnore reason: free-text PII field with no parity-relevant
     * invariant — S-187's sampled-value oracle treats it as opaque.
     */
    @ParityIgnore
    static final String NOTIFICATION_EMAIL = "notification_email";

    static final String PHONE_NUMBER = "phone_number";
    static final String REMARKS = "remarks";
    static final String LANGUAGE_ID = "language_id";

    @ParityIgnore
    static final String KEYCLOAK_SUB = "keycloak_sub";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    /**
     * Legacy column names that the producer-side {@code SELECT} must NOT
     * project — the bundle NDJSON must never carry password material or
     * Keycloak-shadow account state. See the deny-list rationale on the
     * class Javadoc.
     */
    public static final Set<String> FORBIDDEN_LEGACY_COLUMNS = Set.of(
            "Password",
            "PasswordHash",
            "LastPasswordChangeOn",
            "ForcePasswordChangeNextLogon",
            "FailedLoginCounts",
            "AccessFailedCount",
            "AccountState",
            "EmailConfirmed",
            "PhoneNumberConfirmed",
            "TwoFactorEnabled",
            "LockoutEnabled",
            "LockoutEndDateUtc",
            "SecurityStamp");

    private static final String[] COLUMNS = {
            LEGACY_GUID, CLUB_ID, USERNAME, FRIENDLY_NAME, PERSON_ID,
            NOTIFICATION_EMAIL, PHONE_NUMBER, REMARKS, LANGUAGE_ID, KEYCLOAK_SUB,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.USER;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB, EntityType.PERSON, EntityType.LANGUAGE);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("UserId"));
        target.writeStringField(CLUB_ID, source.getString("ClubId"));
        target.writeStringField(USERNAME, source.getString("UserName"));
        target.writeStringField(FRIENDLY_NAME, source.getString("FriendlyName"));
        Coercions.writeOptionalString(target, PERSON_ID, source.getString("PersonId"));
        target.writeStringField(NOTIFICATION_EMAIL, source.getString("NotificationEmail"));
        Coercions.writeOptionalString(target, PHONE_NUMBER, source.getString("PhoneNumber"));
        Coercions.writeOptionalString(target, REMARKS, source.getString("Remarks"));
        target.writeStringField(LANGUAGE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("LanguageId")));
        target.writeNullField(KEYCLOAK_SUB);
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeOptionalTimestamp(target, MODIFIED_ON, source.getTimestamp("ModifiedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(CLUB_ID).asText()));
        target.setString(position++, source.get(USERNAME).asText());
        target.setString(position++, source.get(FRIENDLY_NAME).asText());
        target.setObject(position++, Coercions.readUuidOrNull(source, PERSON_ID));
        target.setString(position++, source.get(NOTIFICATION_EMAIL).asText());
        target.setString(position++, Coercions.readStringOrNull(source, PHONE_NUMBER));
        target.setString(position++, Coercions.readStringOrNull(source, REMARKS));
        target.setObject(position++, UUID.fromString(source.get(LANGUAGE_ID).asText()));
        target.setObject(position++, Coercions.readUuidOrNull(source, KEYCLOAK_SUB));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
