package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link ClubId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments. Spring's
 * {@code WebConversionService} discovers {@code Converter} beans
 * automatically; no {@code WebMvcConfigurer.addFormatters} hook required.
 *
 * <p>Wrapping {@link ClubId#parse(String)}'s {@code IllegalArgumentException}
 * in Spring's own conversion error keeps the failure path symmetric with
 * Spring's other path-variable converters (UUID, Long, …).
 */
@Component
public class ClubIdPathConverter implements Converter<String, ClubId> {

    @Override
    public ClubId convert(String source) {
        return ClubId.parse(source);
    }
}
