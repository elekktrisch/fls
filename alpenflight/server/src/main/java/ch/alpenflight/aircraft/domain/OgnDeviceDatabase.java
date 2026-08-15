package ch.alpenflight.aircraft.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface OgnDeviceDatabase {

    List<OgnDevice> fetchDevices();

    record OgnDevice(@Nullable String deviceType,
                     @Nullable String deviceId,
                     @Nullable String aircraftModel,
                     @Nullable String registration,
                     @Nullable String competitionSign,
                     @Nullable String tracked,
                     @Nullable String identified) {}
}
