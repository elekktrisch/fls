package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class UserIdPathConverter implements Converter<String, UserId> {

    @Override
    public UserId convert(String source) {
        return UserId.parse(source);
    }
}
