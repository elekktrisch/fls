package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ClubIdPathConverter implements Converter<String, ClubId> {

    @Override
    public ClubId convert(String source) {
        return ClubId.parse(source);
    }
}
