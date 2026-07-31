package ch.alpenflight.aircraft.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for the Open Glider Network device database — the registry the
 * aircraft sync matches our fleet against. Implemented by
 * {@code ch.alpenflight.aircraft.infra.HttpOgnDeviceDatabase}.
 *
 * <p>The port promises a list, never a throw: an unreachable third-party registry
 * must not fail the nightly job, so the adapter logs and yields empty.
 */
public interface OgnDeviceDatabase {

    /** Every device the registry lists; empty when it cannot be read. */
    List<OgnDevice> fetchDevices();

    /**
     * One registry entry. The registry quotes its booleans
     * ({@code "tracked": "Y"}), so those stay strings — the sync does not act on
     * them beyond logging.
     */
    record OgnDevice(@Nullable String deviceType,
                     @Nullable String deviceId,
                     @Nullable String aircraftModel,
                     @Nullable String registration,
                     @Nullable String competitionSign,
                     @Nullable String tracked,
                     @Nullable String identified) {}
}
