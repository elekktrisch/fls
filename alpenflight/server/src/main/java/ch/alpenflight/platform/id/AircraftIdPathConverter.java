package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class AircraftIdPathConverter implements Converter<String, AircraftId> {

    @Override
    public AircraftId convert(String source) {
        return AircraftId.parse(source);
    }
}
