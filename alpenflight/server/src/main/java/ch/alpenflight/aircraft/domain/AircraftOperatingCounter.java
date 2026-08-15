package ch.alpenflight.aircraft.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_aircraft_operating_counter")
public class AircraftOperatingCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "aircraft_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
    private @Nullable Aircraft aircraft;

    @Column(name = "at_date_time", nullable = false)
    private @Nullable Instant atDateTime;

    @Column(name = "total_towed_glider_starts")
    private @Nullable Integer totalTowedGliderStarts;

    @Column(name = "total_winch_launch_starts")
    private @Nullable Integer totalWinchLaunchStarts;

    @Column(name = "total_self_starts")
    private @Nullable Integer totalSelfStarts;

    @Column(name = "flight_operating_counter_in_seconds")
    private @Nullable Long flightOperatingCounterInSeconds;

    @Column(name = "engine_operating_counter_in_seconds")
    private @Nullable Long engineOperatingCounterInSeconds;

    @Column(name = "next_maintenance_at_flight_operating_counter_in_seconds")
    private @Nullable Long nextMaintenanceAtFlightOperatingCounterInSeconds;

    @Column(name = "next_maintenance_at_engine_operating_counter_in_seconds")
    private @Nullable Long nextMaintenanceAtEngineOperatingCounterInSeconds;

    @Column(name = "deleted_on")
    @SuppressWarnings("UnusedVariable")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected AircraftOperatingCounter() {
    }

    static AircraftOperatingCounter record(Instant atDateTime,
                                           @Nullable Integer totalTowedGliderStarts,
                                           @Nullable Integer totalWinchLaunchStarts,
                                           @Nullable Integer totalSelfStarts,
                                           @Nullable Long flightOperatingCounterInSeconds,
                                           @Nullable Long engineOperatingCounterInSeconds,
                                           @Nullable Long nextMaintenanceAtFlightOperatingCounterInSeconds,
                                           @Nullable Long nextMaintenanceAtEngineOperatingCounterInSeconds) {
        if (atDateTime == null) {
            throw new IllegalArgumentException("atDateTime must not be null");
        }
        requireNonNegative("totalTowedGliderStarts", totalTowedGliderStarts);
        requireNonNegative("totalWinchLaunchStarts", totalWinchLaunchStarts);
        requireNonNegative("totalSelfStarts", totalSelfStarts);
        requireNonNegativeLong("flightOperatingCounterInSeconds", flightOperatingCounterInSeconds);
        requireNonNegativeLong("engineOperatingCounterInSeconds", engineOperatingCounterInSeconds);
        requireNonNegativeLong("nextMaintenanceAtFlightOperatingCounterInSeconds",
                nextMaintenanceAtFlightOperatingCounterInSeconds);
        requireNonNegativeLong("nextMaintenanceAtEngineOperatingCounterInSeconds",
                nextMaintenanceAtEngineOperatingCounterInSeconds);

        AircraftOperatingCounter c = new AircraftOperatingCounter();
        c.atDateTime = atDateTime;
        c.totalTowedGliderStarts = totalTowedGliderStarts;
        c.totalWinchLaunchStarts = totalWinchLaunchStarts;
        c.totalSelfStarts = totalSelfStarts;
        c.flightOperatingCounterInSeconds = flightOperatingCounterInSeconds;
        c.engineOperatingCounterInSeconds = engineOperatingCounterInSeconds;
        c.nextMaintenanceAtFlightOperatingCounterInSeconds = nextMaintenanceAtFlightOperatingCounterInSeconds;
        c.nextMaintenanceAtEngineOperatingCounterInSeconds = nextMaintenanceAtEngineOperatingCounterInSeconds;
        return c;
    }

    void attachTo(Aircraft parent) {
        this.aircraft = parent;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable Instant getAtDateTime() {
        return atDateTime;
    }

    public @Nullable Integer getTotalTowedGliderStarts() {
        return totalTowedGliderStarts;
    }

    public @Nullable Integer getTotalWinchLaunchStarts() {
        return totalWinchLaunchStarts;
    }

    public @Nullable Integer getTotalSelfStarts() {
        return totalSelfStarts;
    }

    public @Nullable Long getFlightOperatingCounterInSeconds() {
        return flightOperatingCounterInSeconds;
    }

    public @Nullable Long getEngineOperatingCounterInSeconds() {
        return engineOperatingCounterInSeconds;
    }

    public @Nullable Long getNextMaintenanceAtFlightOperatingCounterInSeconds() {
        return nextMaintenanceAtFlightOperatingCounterInSeconds;
    }

    public @Nullable Long getNextMaintenanceAtEngineOperatingCounterInSeconds() {
        return nextMaintenanceAtEngineOperatingCounterInSeconds;
    }

    private static void requireNonNegative(String name, @Nullable Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }

    private static void requireNonNegativeLong(String name, @Nullable Long value) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}
