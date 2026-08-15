package ch.alpenflight.tenancy.provisioning.application;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReferenceDataSeeder {

    private final JdbcTemplate jdbc;

    public ReferenceDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    private static String nextId() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }
}
