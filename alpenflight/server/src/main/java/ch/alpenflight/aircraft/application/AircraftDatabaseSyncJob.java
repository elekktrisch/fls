package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.OgnDeviceDatabase;
import ch.alpenflight.aircraft.domain.OgnDeviceDatabase.OgnDevice;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aircraft ↔ OGN device-database sync (S-088) — mirrors legacy
 * {@code AircraftDatabaseSyncJob.cs}: download the registry, match each of our
 * aircraft to a device entry by immatriculation, and copy the FLARM device id,
 * model, and competition sign onto the matched aircraft.
 *
 * <p><strong>Update-only.</strong> The scan runs over <em>our</em> fleet, so a
 * registry entry we have no aircraft for is simply never looked at — nothing is
 * auto-created. That matters for tenancy as much as for data hygiene: a
 * device-database row carries no club, so there would be no owner to give it to.
 *
 * <p><strong>Matching.</strong> Immatriculation compared with dashes stripped and
 * upper-cased on both sides ({@code :82-85}), so {@code HB-1234} matches
 * {@code HB1234}.
 *
 * <p><strong>Scope.</strong> Aircraft is cross-tenant (ADR 0008 amendment), so the
 * job scans the fleet once rather than iterating clubs.
 */
@Component
@MeasuredJob(name = AircraftDatabaseSyncJob.JOB_NAME,
        cron = AircraftDatabaseSyncJob.CRON,
        description = "Aircraft sync against the OGN device database")
public class AircraftDatabaseSyncJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(AircraftDatabaseSyncJob.class);

    /** Stable registry key — see {@link MeasuredJob#name()}. */
    public static final String JOB_NAME = "aircraft-database-sync";

    static final String CRON = "0 0 4 * * SUN";

    private final AircraftRepository aircraft;
    private final OgnDeviceDatabase ddb;

    public AircraftDatabaseSyncJob(AircraftRepository aircraft, OgnDeviceDatabase ddb) {
        this.aircraft = aircraft;
        this.ddb = ddb;
    }

    /** Scheduled tick — weekly; the registry changes slowly. */
    @Scheduled(cron = CRON)
    public void runScheduled() {
        runOnce();
    }

    /** Cross-tenant "Run now" for the {@code /system/jobs} console. */
    @Override
    @Transactional
    public RunSummary runOnce() {
        Map<String, OgnDevice> byRegistration = indexByRegistration();
        if (byRegistration.isEmpty()) {
            return new RunSummary(0, 0);
        }
        int updated = 0;
        int unmatched = 0;
        for (Aircraft craft : aircraft.findAllActive()) {
            OgnDevice device = byRegistration.get(normalise(craft.getImmatriculation()));
            if (device == null) {
                LOG.debug("aircraft {} not found in the OGN device database",
                        craft.getImmatriculation());
                unmatched++;
                continue;
            }
            if (craft.syncFromDeviceDatabase(
                    device.deviceId(), device.aircraftModel(), device.competitionSign())) {
                aircraft.save(craft);
                updated++;
            }
        }
        return new RunSummary(updated, unmatched);
    }

    private Map<String, OgnDevice> indexByRegistration() {
        Map<String, OgnDevice> byRegistration = new HashMap<>();
        for (OgnDevice device : ddb.fetchDevices()) {
            String registration = normalise(device.registration());
            if (!registration.isEmpty()) {
                byRegistration.putIfAbsent(registration, device);
            }
        }
        return byRegistration;
    }

    /** Dash-stripped, upper-cased immatriculation — the match key on both sides. */
    private static String normalise(@Nullable String immatriculation) {
        if (immatriculation == null) {
            return "";
        }
        return immatriculation.replace("-", "").toUpperCase(Locale.ROOT).strip();
    }

    /**
     * Non-PII run summary: how many aircraft the registry updated, and how many of
     * ours it does not know.
     */
    public record RunSummary(int updatedCount, int unmatchedCount) {

        @Override
        public String toString() {
            return updatedCount + " aircraft updated, " + unmatchedCount + " not in the registry";
        }
    }
}
