package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class FlightTypeIdPathConverter implements Converter<String, FlightTypeId> {

    @Override
    public FlightTypeId convert(String source) {
        return FlightTypeId.parse(source);
    }
}
