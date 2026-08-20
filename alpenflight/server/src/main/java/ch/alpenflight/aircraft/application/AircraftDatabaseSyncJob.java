package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.OgnDeviceDatabase;
import ch.alpenflight.aircraft.domain.OgnDeviceDatabase.OgnDevice;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@MeasuredJob(name = AircraftDatabaseSyncJob.JOB_NAME,
        cronShownInConsole = AircraftDatabaseSyncJob.CRON,
        description = "Aircraft sync against the OGN device database")
public class AircraftDatabaseSyncJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(AircraftDatabaseSyncJob.class);

    public static final String JOB_NAME = "aircraft-database-sync";

    static final String CRON = "0 0 4 * * SUN";

    private final AircraftRepository aircraft;
    private final OgnDeviceDatabase ddb;
    private final ObjectProvider<AircraftDatabaseSyncJob> self;

    public AircraftDatabaseSyncJob(AircraftRepository aircraft,
                                   OgnDeviceDatabase ddb,
                                   ObjectProvider<AircraftDatabaseSyncJob> self) {
        this.aircraft = aircraft;
        this.ddb = ddb;
        this.self = self;
    }

    @Scheduled(cron = CRON)
    @UnscopedScheduledJob
    public void runScheduled() {
        self.getObject().runOnce();
    }

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
            if (craft.applyNonBlankDeviceDatabaseValues(
                    device.deviceId(), device.aircraftModel(), device.competitionSign())) {
                aircraft.save(craft);
                updated++;
            }
        }
        return new RunSummary(updated, unmatched);
    }

    private Map<String, OgnDevice> indexByRegistration() {
        Map<String, OgnDevice> byRegistration = new HashMap<>();
        for (OgnDevice device : ddb.fetchDevicesOrEmptyWhenRegistryUnreachable()) {
            String registration = normalise(device.registration());
            if (!registration.isEmpty()) {
                byRegistration.putIfAbsent(registration, device);
            }
        }
        return byRegistration;
    }

    private static String normalise(@Nullable String immatriculation) {
        if (immatriculation == null) {
            return "";
        }
        return immatriculation.replace("-", "").toUpperCase(Locale.ROOT).strip();
    }

    public record RunSummary(int updatedCount, int unmatchedCount) {

        @Override
        public String toString() {
            return updatedCount + " aircraft updated, " + unmatchedCount + " not in the registry";
        }
    }
}
