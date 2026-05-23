package ch.alpenflight.aircraft.application;

/**
 * Server-side type-discriminator slice for the {@code GET /api/v1/aircraft}
 * list endpoint. Membership preserves the legacy {@code AircraftService}
 * semantics (legacy bit-field codes preserved as {@code aircraft_type.legacy_int_id}):
 *
 * <ul>
 *   <li>{@link #GLIDER} — {@code aircraft_type.code IN ('GLIDER', 'GLIDER_WITH_MOTOR')}.
 *       Legacy: {@code Type ∈ {Glider, GliderWithMotor}}
 *       (AircraftService.cs:303-304). A glider-with-motor still flies as a
 *       glider — keep it in the glider slice for pilot-pool filtering.</li>
 *   <li>{@link #MOTOR} — {@code aircraft_type.legacy_int_id >= 4}
 *       ({@code MOTOR_GLIDER}, {@code MOTOR_AIRCRAFT}, {@code MULTI_ENGINE},
 *       {@code JET}, {@code HELICOPTER}). Legacy:
 *       {@code AircraftTypeId >= MotorGlider} (AircraftService.cs:96).
 *       {@code GLIDER_WITH_MOTOR} is intentionally excluded — it's a glider
 *       primarily, motor only as fallback.</li>
 *   <li>{@link #TOWING} — {@code aircraft.is_towing_aircraft = true}, regardless
 *       of type. Towing capability rides on the aircraft, not the type.</li>
 * </ul>
 *
 * <p>Slicing client-side via {@code has_engine} would silently drop
 * {@code GLIDER_WITH_MOTOR} from the glider list and include it in motors —
 * the new {@code aircraft_type} schema has {@code has_engine=true} for both
 * glider-with-motor and pure motor types.
 */
public enum AircraftTypeSlice {
    GLIDER,
    MOTOR,
    TOWING,
}
