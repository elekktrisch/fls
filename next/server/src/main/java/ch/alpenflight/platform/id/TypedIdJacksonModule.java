package ch.alpenflight.platform.id;

import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Wires the typed-id family ({@link ClubId} and future {@code PersonId} /
 * {@code UserId} / …) into Spring Boot's auto-configured Jackson 3
 * {@code ObjectMapper}. Spring Boot registers every {@code JacksonModule}
 * bean with the default mapper, so this component drives both inbound
 * deserialisation and outbound serialisation without per-record annotations.
 *
 * <p>Each id type contributes one line to the constructor; the record itself
 * stays a pure data class with no Jackson dependency in source.
 */
@Component
public class TypedIdJacksonModule extends SimpleModule {

    public TypedIdJacksonModule() {
        super("AlpenFlightTypedIds");
        register(ClubId.class, ClubId::parse, ClubId::toExternal);
    }

    private <T> void register(Class<T> type, Function<String, T> parser, Function<T, String> renderer) {
        addSerializer(type, new ValueSerializer<T>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                gen.writeString(renderer.apply(value));
            }
        });
        addDeserializer(type, new ValueDeserializer<T>() {
            @Override
            public T deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
                return parser.apply(p.getValueAsString());
            }
        });
    }
}
