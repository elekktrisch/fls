package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link FlightTypeId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments. Mirrors
 * {@link AircraftIdPathConverter}.
 */
@Component
public class FlightTypeIdPathConverter implements Converter<String, FlightTypeId> {

    @Override
    public FlightTypeId convert(String source) {
        return FlightTypeId.parse(source);
    }
}
