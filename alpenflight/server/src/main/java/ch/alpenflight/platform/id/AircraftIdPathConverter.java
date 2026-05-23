package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to an {@link AircraftId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments. Mirrors
 * {@link LocationIdPathConverter}.
 */
@Component
public class AircraftIdPathConverter implements Converter<String, AircraftId> {

    @Override
    public AircraftId convert(String source) {
        return AircraftId.parse(source);
    }
}
