package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link PersonId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments.
 */
@Component
public class PersonIdPathConverter implements Converter<String, PersonId> {

    @Override
    public PersonId convert(String source) {
        return PersonId.parse(source);
    }
}
