
INSERT INTO t_member_state (id, club_id, name)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000a1', '019e30c3-2c00-7001-8000-000000000001', 'Aktivmitglied'),
    ('019e30c3-2c00-7001-8000-0000000000a2', '019e30c3-2c00-7001-8000-000000000001', 'Passivmitglied')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_person_club
    (id, person_id, club_id, member_number, member_state_id,
     is_glider_instructor, is_glider_pilot, is_tow_pilot, is_active)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000c1', '019e30c3-2c00-7001-8000-0000000000b1',
     '019e30c3-2c00-7001-8000-000000000001', '1001',
     '019e30c3-2c00-7001-8000-0000000000a1', true,  true,  false, true),
    ('019e30c3-2c00-7001-8000-0000000000c2', '019e30c3-2c00-7001-8000-0000000000b2',
     '019e30c3-2c00-7001-8000-000000000001', '1002',
     '019e30c3-2c00-7001-8000-0000000000a1', false, true,  true,  true),
    ('019e30c3-2c00-7001-8000-0000000000c3', '019e30c3-2c00-7001-8000-0000000000b3',
     '019e30c3-2c00-7001-8000-000000000001', '1003',
     '019e30c3-2c00-7001-8000-0000000000a1', false, true,  false, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_aircraft
    (id, managing_club_id, owner_club_id, aircraft_type_id,
     manufacturer_name, aircraft_model, immatriculation, competition_sign, nr_of_seats,
     is_towing_or_winch_required, is_towing_start_allowed, is_winch_start_allowed,
     is_towing_aircraft, is_fast_entry_record)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a10',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e2e15-2c00-7af9-8000-000000002af9',
     'Schleicher', 'ASK-21', 'HB-SEED', 'SD', 2,
     true, false, false, false, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_aircraft_aircraft_state
    (id, aircraft_id, aircraft_state_id, valid_from, valid_to)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a11',
     '019e30c3-2c00-7001-8000-000000000a10',
     '019e2e15-2c00-7ee0-8000-000000002ee0',
     now(), NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_flight_type
    (id, operating_club_id, flight_type_name, flight_code, is_for_glider_flights)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f10',
     '019e30c3-2c00-7001-8000-000000000001',
     'Schulung', 'SCH', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_article
    (id, operating_club_id, article_number, article_name, article_info, is_active)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a20',
     '019e30c3-2c00-7001-8000-000000000001',
     'A-1001', 'Startgebühr Segelflug', 'Seed article', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_flight
    (id, operating_club_id, aircraft_id, flight_date,
     start_date_time, ldg_date_time, start_location_id, ldg_location_id,
     flight_type_id, start_type_id, is_solo_flight,
     no_start_time_information, no_ldg_time_information,
     process_state_id, flight_aircraft_type_id, comment)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f20',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000a10',
     CURRENT_DATE - 7,
     (CURRENT_DATE - 7 + TIME '10:00')::timestamptz,
     (CURRENT_DATE - 7 + TIME '11:00')::timestamptz,
     '019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7001-8000-000000000f10',
     '019e2e15-2c00-7fa0-8000-000000000fa0',
     false, false, false,
     '019e2e15-2c00-7a9a-8000-000000003a9a',
     1,
     'Seed flight — glider')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_flight_crew
    (id, flight_id, person_id, flight_crew_type_id,
     begin_flight_datetime, end_flight_datetime, nr_of_ldgs, nr_of_starts)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f21',
     '019e30c3-2c00-7001-8000-000000000f20',
     '019e30c3-2c00-7001-8000-0000000000b1',
     '019e2e15-2c00-76b0-8000-0000000036b0',
     (CURRENT_DATE - 7 + TIME '10:00')::timestamptz,
     (CURRENT_DATE - 7 + TIME '11:00')::timestamptz,
     1, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_aircraft_reservation
    (id, operating_club_id, aircraft_id, reservation_start, reservation_end,
     is_all_day, pilot_person_id, location_id, reservation_type_id, info)
VALUES
    ('019e30c3-2c00-7001-8000-000000000a30',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000a10',
     (CURRENT_DATE + 2 + TIME '09:00')::timestamptz,
     (CURRENT_DATE + 2 + TIME '12:00')::timestamptz,
     false,
     '019e30c3-2c00-7001-8000-0000000000b1',
     '019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7400-8000-000000000001',
     'Seed reservation')
ON CONFLICT (id) DO NOTHING;
