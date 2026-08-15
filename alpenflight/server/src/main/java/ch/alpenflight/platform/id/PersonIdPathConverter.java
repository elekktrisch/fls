package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class PersonIdPathConverter implements Converter<String, PersonId> {

    @Override
    public PersonId convert(String source) {
        return PersonId.parse(source);
    }
}
