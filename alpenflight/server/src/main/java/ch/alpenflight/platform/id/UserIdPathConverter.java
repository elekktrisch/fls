package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link UserId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments.
 */
@Component
public class UserIdPathConverter implements Converter<String, UserId> {

    @Override
    public UserId convert(String source) {
        return UserId.parse(source);
    }
}
