package ch.alpenflight.platform.id;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the prefixed external form to a {@link FlightCostBalanceTypeId} for
 * {@code @PathVariable} / {@code @RequestParam} arguments.
 */
@Component
public class FlightCostBalanceTypeIdPathConverter
        implements Converter<String, FlightCostBalanceTypeId> {

    @Override
    public FlightCostBalanceTypeId convert(String source) {
        return FlightCostBalanceTypeId.parse(source);
    }
}
