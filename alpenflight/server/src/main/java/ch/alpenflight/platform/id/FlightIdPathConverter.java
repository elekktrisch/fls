package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link FlightId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments.
 */
@Component
public class FlightIdPathConverter implements Converter<String, FlightId> {

    @Override
    public FlightId convert(String source) {
        return FlightId.parse(source);
    }
}
