package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link LocationId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments. Spring's
 * {@code WebConversionService} discovers {@code Converter} beans
 * automatically; no {@code WebMvcConfigurer.addFormatters} hook required.
 *
 * <p>Wrapping {@link LocationId#parse(String)}'s
 * {@code IllegalArgumentException} in Spring's own conversion error keeps
 * the failure path symmetric with Spring's other path-variable converters
 * (UUID, Long, …).
 */
@Component
public class LocationIdPathConverter implements Converter<String, LocationId> {

    @Override
    public LocationId convert(String source) {
        return LocationId.parse(source);
    }
}
