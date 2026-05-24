package ch.alpenflight.flights.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

/**
 * JPA converter for the sparse SMALLINT discriminator {@link
 * FlightAircraftType}. Explicit mapping {GLIDER→1, TOW→2, MOTOR→4} —
 * standard {@code EnumType.ORDINAL} cannot express the gap at 3.
 *
 * <p>{@code autoApply = false}: the {@link Flight} entity opts in via
 * {@code @Convert} on the field, so other potential Short fields are not
 * silently rewritten.
 */
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
