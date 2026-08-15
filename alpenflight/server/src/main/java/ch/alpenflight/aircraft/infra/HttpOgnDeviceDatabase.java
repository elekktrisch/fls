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

@Component
public class HttpOgnDeviceDatabase implements OgnDeviceDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOgnDeviceDatabase.class);

    private static final String DEFAULT_URL = "http://ddb.glidernet.org/download?j=1";

    private final RestClient restClient;
    private final String ddbUrl;

    public HttpOgnDeviceDatabase(@Value("${alpenflight.ogn.ddb-url:" + DEFAULT_URL + "}")
                                 String ddbUrl) {
        this.restClient = newRestClientWithoutTheTestClasspathOnlyAutoConfiguredBuilder();
        this.ddbUrl = ddbUrl;
    }

    private static RestClient newRestClientWithoutTheTestClasspathOnlyAutoConfiguredBuilder() {
        return RestClient.create();
    }

    @Override
    public List<OgnDevice> fetchDevicesOrEmptyWhenRegistryUnreachable() {
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
