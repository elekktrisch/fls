package ch.fls.legacyextract;

import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Reads FLS legacy schema metadata from SQL Server via JDBC and writes JSON
 * record arrays under {@link ExtractConfig#outDir()}. Read-only by
 * construction: only queries against {@code INFORMATION_SCHEMA.*}, {@code
 * sys.*}, and {@code sys.dm_db_*} reach the wire; application tables are
 * touched only when {@link ExtractConfig#allowAggregateCounts()} is true and
 * only via aggregate expressions (see {@link SqlGuard}).
 *
 * <p>Sacred-cow references — these are the tables S-013 / S-016 must preserve
 * shape on, and the JSON output is the contract:
 * <ul>
 *   <li><b>Flight</b> — single-entity discriminator across glider/tow/motor;
 *       has no {@code ClubId} column. Tenancy reaches Flights via
 *       {@code AircraftId → Aircrafts.OwnerClubId}; S-013 should denormalize
 *       {@code club_id} into the new {@code flight} table.</li>
 *   <li><b>FlightCrew</b> — composite UNIQUE on (Flight, Person, CrewType).</li>
 *   <li><b>AccountingRuleFilter</b> — rules-engine config, JSONB candidate.</li>
 *   <li><b>Delivery / DeliveryItem</b> — Prepared → Booked terminal flow.</li>
 *   <li><b>User / Person / PersonClub</b> — login principal vs. human vs.
 *       human-in-club; collapse breaks multi-club pilots.</li>
 *   <li><b>AuditLogs + AuditLogDetails</b> — audit fan-out; the
 *       {@code OriginalValue} / {@code NewValue} columns on
 *       {@code AuditLogDetails} are the system's largest PII container.</li>
 * </ul>
 */
@Component
public class MetadataExtractor {

    private final DataSource dataSource;

    public MetadataExtractor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ExtractResult extractTo(ExtractConfig config) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
