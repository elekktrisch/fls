package ch.alpenflight.tenancy.provisioning.application;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-Club default reference rows for a freshly-provisioned Deployment.
 * Inserts the TENANT_SCOPED defaults a self-service tenant expects to
 * see immediately:
 *
 * <ul>
 *   <li>{@code t_member_state} — {@code active}, {@code passive},
 *       {@code junior}.</li>
 *   <li>{@code t_flight_type} — {@code training}, {@code glider-tow},
 *       {@code private}, {@code ferry}, with the glider / tow / motor /
 *       observer flags set to legacy defaults.</li>
 * </ul>
 *
 * <p>{@code t_flight_cost_balance_type} is intentionally NOT seeded
 * here: the V3 baseline populates it as a system-global catalogue (no
 * {@code club_id} column), shared across tenants per the reference-data
 * pattern.
 *
 * <p>Bundle-wins-on-conflict is enforced by {@code ON CONFLICT (...) DO
 * NOTHING} against the same partial UNIQUE indexes the application
 * relies on for identity:
 * {@code ux_member_state_club_name} (V15) and
 * {@code ux_flight_type_club_name} (V11). The predicates in this file
 * MUST stay in sync with the index WHERE clauses; either side drifting
 * is silent.
 *
 * <p>This component runs JDBC-directly rather than through JPA so the
 * INSERT path doesn't depend on the {@code @TenantId} resolver — the
 * Deployment + Clubs were just inserted under a null-tenant write
 * context (Deployment is above tenancy), and the seeder fires per-Club
 * with the {@code club_id} passed in explicitly. {@code MANDATORY}
 * propagation joins the caller's transaction so a rollback rewinds the
 * seeded rows cleanly.
 */
@Component
public class ReferenceDataSeeder {

    private final JdbcTemplate jdbc;

    public ReferenceDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts every default for the given Club. Idempotent — re-running
     * against an already-seeded Club is a no-op because each INSERT
     * carries {@code ON CONFLICT … DO NOTHING}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seedDefaults(UUID clubId) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null");
        }
        seedMemberStates(clubId);
        seedFlightTypes(clubId);
    }

    private void seedMemberStates(UUID clubId) {
        for (String name : List.of("active", "passive", "junior")) {
            // Conflict target matches ux_member_state_club_name (V15).
            jdbc.update("""
                    INSERT INTO t_member_state (id, club_id, name)
                    VALUES (?::uuid, ?::uuid, ?)
                    ON CONFLICT (club_id, name) WHERE deleted_on IS NULL DO NOTHING
                    """,
                    nextId(), clubId.toString(), name);
        }
    }

    private void seedFlightTypes(UUID clubId) {
        record DefaultFlightType(String name, boolean forGlider, boolean forTow,
                                 boolean forMotor, boolean instructorRequired) {}
        List<DefaultFlightType> defaults = List.of(
                new DefaultFlightType("training",   true,  false, true,  true),
                new DefaultFlightType("glider-tow", false, true,  false, false),
                new DefaultFlightType("private",    true,  false, true,  false),
                new DefaultFlightType("ferry",      true,  false, true,  false)
        );
        for (DefaultFlightType d : defaults) {
            // Conflict target matches ux_flight_type_club_name (V11).
            jdbc.update("""
                    INSERT INTO t_flight_type (id, operating_club_id, flight_type_name,
                            is_for_glider_flights, is_for_tow_flights,
                            is_for_motor_flights, instructor_required)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                    ON CONFLICT (operating_club_id, flight_type_name)
                        WHERE deleted_on IS NULL DO NOTHING
                    """,
                    nextId(), clubId.toString(), d.name(),
                    d.forGlider(), d.forTow(), d.forMotor(), d.instructorRequired());
        }
    }

    /**
     * Time-ordered v7 UUID, matching the project convention enforced by
     * {@code @UuidV7} on JPA entities. Reference-data rows the seeder
     * inserts use the same identity shape so b-tree clustering stays
     * monotonic.
     */
    private static String nextId() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }
}
