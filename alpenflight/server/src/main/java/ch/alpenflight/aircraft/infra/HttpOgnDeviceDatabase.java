package ch.alpenflight.aircraft.infra;

import ch.alpenflight.aircraft.domain.OgnDeviceDatabase;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the OGN device database over HTTP ({@code AircraftDatabaseSyncJob.cs:62}).
 *
 * <p>The endpoint is configuration ({@code alpenflight.ogn.ddb-url}) so a test or
 * the e2e gate can point it at a recorded fixture instead of the live network. A
 * transport or parse failure is logged and yields an empty list — the job survives
 * an unreachable registry, as legacy does ({@code :111-114}).
 */
@Component
public class HttpOgnDeviceDatabase implements OgnDeviceDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOgnDeviceDatabase.class);

    private static final String DEFAULT_URL = "http://ddb.glidernet.org/download?j=1";

    private final RestClient restClient;
    private final String ddbUrl;

    /**
     * Builds its own client rather than taking the auto-configured
     * {@code RestClient.Builder}: that bean is contributed by web auto-config that
     * is present on the test classpath but not in the packaged application, so
     * injecting it starts fine under {@code @SpringBootTest} and fails the boot jar.
     */
    public HttpOgnDeviceDatabase(@Value("${alpenflight.ogn.ddb-url:" + DEFAULT_URL + "}")
                                 String ddbUrl) {
        this.restClient = RestClient.create();
        this.ddbUrl = ddbUrl;
    }

    @Override
    public List<OgnDevice> fetchDevices() {
        try {
            DdbResponse response = restClient.get()
                    .uri(ddbUrl)
                    .retrieve()
                    .body(DdbResponse.class);
            if (response == null || response.devices() == null) {
                LOG.info("OGN device database returned no devices — check the DDB or the connection");
                return List.of();
            }
            List<OgnDevice> devices = new ArrayList<>();
            for (DdbDevice device : response.devices()) {
                devices.add(new OgnDevice(device.deviceType(), device.deviceId(),
                        device.aircraftModel(), device.registration(), device.competitionSign(),
                        device.tracked(), device.identified()));
            }
            return devices;
        } catch (RuntimeException e) {
            LOG.error("could not read the OGN device database at {}", ddbUrl, e);
            return List.of();
        }
    }

    /** Wire shape of the DDB response; field names are the registry's JSON keys. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DdbResponse(@JsonProperty("devices") @Nullable List<DdbDevice> devices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DdbDevice(@JsonProperty("device_type") @Nullable String deviceType,
                             @JsonProperty("device_id") @Nullable String deviceId,
                             @JsonProperty("aircraft_model") @Nullable String aircraftModel,
                             @JsonProperty("registration") @Nullable String registration,
                             @JsonProperty("cn") @Nullable String competitionSign,
                             @JsonProperty("tracked") @Nullable String tracked,
                             @JsonProperty("identified") @Nullable String identified) {}
}
