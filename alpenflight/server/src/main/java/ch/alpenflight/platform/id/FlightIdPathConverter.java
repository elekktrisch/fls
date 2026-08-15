package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class FlightIdPathConverter implements Converter<String, FlightId> {

    @Override
    public FlightId convert(String source) {
        return FlightId.parse(source);
    }
}
