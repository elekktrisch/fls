package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class FlightCostBalanceTypeIdPathConverter
        implements Converter<String, FlightCostBalanceTypeId> {

    @Override
    public FlightCostBalanceTypeId convert(String source) {
        return FlightCostBalanceTypeId.parse(source);
    }
}
