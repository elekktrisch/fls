package ch.alpenflight.flights.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

@Converter(autoApply = false)
public class FlightAircraftTypeConverter
        implements AttributeConverter<FlightAircraftType, Short> {

    @Override
    public @Nullable Short convertToDatabaseColumn(@Nullable FlightAircraftType attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.legacyId();
    }

    @Override
    public @Nullable FlightAircraftType convertToEntityAttribute(@Nullable Short dbData) {
        if (dbData == null) {
            return null;
        }
        return FlightAircraftType.fromLegacyId(dbData);
    }
}
