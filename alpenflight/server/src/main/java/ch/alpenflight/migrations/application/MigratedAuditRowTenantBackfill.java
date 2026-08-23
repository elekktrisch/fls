package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class MigratedAuditRowTenantBackfill {

    static final String AUDIT_TABLE = "t_mutation_audit_event";
    static final String MIGRATED_ACTOR_KIND = "LEGACY_MIGRATED";

    private static final String A_CLUB_ROW_IS_ITS_OWN_TENANT = "id";

    private static final char ENUM_WORD_SEPARATOR = '_';

    private static final List<String> TENANT_COLUMN_CANDIDATES_IN_PRIORITY_ORDER =
            List.of("operating_club_id", "club_id");

    private MigratedAuditRowTenantBackfill() { }

    record Result(int rowsGivenTheTenantOfTheEntityTheyDescribe,
                  int rowsWhoseDescribedEntityYieldsNoClub) { }

    static Result giveEachMigratedRowTheTenantOfTheEntityItDescribes(Connection connection)
            throws SQLException {
        int given = 0;
        for (EntityType entity : EntityType.values()) {
            given += adoptTheClubOfEveryRowDescribing(connection, entity);
        }
        return new Result(given, countMigratedRowsStillWithoutATenant(connection));
    }

    static boolean aRowOfThisEntityCanNameItsOwnClub(EntityType entity) {
        return entity != EntityType.AUDIT_LOG
                && !entity.fansOut()
                && MapperLegacyBindings.isRegistered(entity);
    }

    private static @Nullable String tenantColumnOfOrNull(Connection connection, EntityType entity)
            throws SQLException {
        if (!aRowOfThisEntityCanNameItsOwnClub(entity)) {
            return null;
        }
        if (entity == EntityType.CLUB) {
            return A_CLUB_ROW_IS_ITS_OWN_TENANT;
        }
        return firstTenantColumnPresentOn(
                connection, MapperLegacyBindings.newSchemaTable(entity));
    }

    private static @Nullable String firstTenantColumnPresentOn(Connection connection, String table)
            throws SQLException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?")) {
            query.setString(1, table);
            try (ResultSet found = query.executeQuery()) {
                while (found.next()) {
                    columns.add(found.getString(1));
                }
            }
        }
        for (String candidate : TENANT_COLUMN_CANDIDATES_IN_PRIORITY_ORDER) {
            if (columns.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int adoptTheClubOfEveryRowDescribing(Connection connection, EntityType entity)
            throws SQLException {
        String tenantColumn = tenantColumnOfOrNull(connection, entity);
        if (tenantColumn == null) {
            return 0;
        }
        String update = "UPDATE " + AUDIT_TABLE + " a"
                + " SET tenant_club_id = described." + tenantColumn
                + " FROM " + LegacyIdMapTables.temporaryTableName(entity) + " m"
                + " JOIN " + MapperLegacyBindings.newSchemaTable(entity) + " described"
                + " ON described.id = m.new_uuid"
                + " WHERE a.tenant_club_id IS NULL"
                + " AND a.actor_kind = ?"
                + " AND a.target_entity_type = ?"
                + " AND a.target_entity_id = m.legacy_guid";
        try (PreparedStatement ps = connection.prepareStatement(update)) {
            ps.setString(1, MIGRATED_ACTOR_KIND);
            ps.setString(2, legacyEntityNameOf(entity));
            return ps.executeUpdate();
        }
    }

    private static int countMigratedRowsStillWithoutATenant(Connection connection)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM " + AUDIT_TABLE
                        + " WHERE actor_kind = ? AND tenant_club_id IS NULL")) {
            query.setString(1, MIGRATED_ACTOR_KIND);
            try (ResultSet count = query.executeQuery()) {
                return count.next() ? count.getInt(1) : 0;
            }
        }
    }

    static String legacyEntityNameOf(EntityType entity) {
        String screamingSnakeCase = entity.name();
        StringBuilder pascalCase = new StringBuilder(screamingSnakeCase.length());
        boolean atWordStart = true;
        for (int position = 0; position < screamingSnakeCase.length(); position++) {
            char letter = screamingSnakeCase.charAt(position);
            if (letter == ENUM_WORD_SEPARATOR) {
                atWordStart = true;
                continue;
            }
            pascalCase.append(atWordStart ? letter : Character.toLowerCase(letter));
            atWordStart = false;
        }
        return pascalCase.toString();
    }
}
