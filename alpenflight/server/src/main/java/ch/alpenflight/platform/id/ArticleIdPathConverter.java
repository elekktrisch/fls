package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to an {@link ArticleId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments. Mirrors
 * {@link FlightTypeIdPathConverter}.
 */
@Component
public class ArticleIdPathConverter implements Converter<String, ArticleId> {

    @Override
    public ArticleId convert(String source) {
        return ArticleId.parse(source);
    }
}
