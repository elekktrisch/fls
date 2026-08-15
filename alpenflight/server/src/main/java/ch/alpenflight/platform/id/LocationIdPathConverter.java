package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class LocationIdPathConverter implements Converter<String, LocationId> {

    @Override
    public LocationId convert(String source) {
        return LocationId.parse(source);
    }
}
