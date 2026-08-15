package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ArticleIdPathConverter implements Converter<String, ArticleId> {

    @Override
    public ArticleId convert(String source) {
        return ArticleId.parse(source);
    }
}
