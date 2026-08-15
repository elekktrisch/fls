
INSERT INTO t_aircraft_reservation_type (
    id,
    operating_club_id,
    reservation_type_name,
    is_instructor_required,
    is_maintenance,
    is_active
) VALUES (
    '019e30c3-2c00-7400-8000-000000000001',
    '019e30c3-2c00-7001-8000-000000000001',
    'Allgemein',
    false,
    false,
    true
)
ON CONFLICT (id) DO NOTHING;
