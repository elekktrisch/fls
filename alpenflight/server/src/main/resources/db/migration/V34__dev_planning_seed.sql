
UPDATE t_club
   SET send_planning_day_info_mail_to = 'flugbetrieb@seed-club-1.example'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001'
   AND send_planning_day_info_mail_to IS NULL;

INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
        icao_code, is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
VALUES
    ('019e30c3-2c00-7001-8000-00000000c001',
     '019e30c3-2c00-7001-8000-000000000001',
     'Bern-Belp',
     '019e2e15-2c00-74be-8000-0000000004be',
     '019e2e15-2c00-72cb-8000-0000000032cb',
     'LSZB', false, false, false),
    ('019e30c3-2c00-7001-8000-00000000c002',
     '019e30c3-2c00-7001-8000-000000000001',
     'Thun',
     '019e2e15-2c00-74be-8000-0000000004be',
     '019e2e15-2c00-72cb-8000-0000000032cb',
     'LSPL', false, false, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_person (id, firstname, lastname, city,
        has_glider_instructor_licence, has_glider_pilot_licence, has_tow_pilot_licence)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000b1', 'Iris', 'Instructor', 'Bern',  true,  true,  false),
    ('019e30c3-2c00-7001-8000-0000000000b2', 'Tom',  'Towpilot',   'Thun',  false, true,  true),
    ('019e30c3-2c00-7001-8000-0000000000b3', 'Fred', 'Flightop',   'Bern',  false, true,  false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_planning_day_assignment_type
    (id, operating_club_id, assignment_type_name, required_nr_of_assignments)
VALUES
    ('019e30c3-2c00-7001-8000-0000000000d1', '019e30c3-2c00-7001-8000-000000000001', 'Segelflugleiter', 1),
    ('019e30c3-2c00-7001-8000-0000000000d2', '019e30c3-2c00-7001-8000-000000000001', 'Schlepppilot',    1),
    ('019e30c3-2c00-7001-8000-0000000000d3', '019e30c3-2c00-7001-8000-000000000001', 'Fluglehrer',      1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_planning_day (id, operating_club_id, planning_date, location_id, info)
VALUES
    ('019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-000000000001',
     CURRENT_DATE + 3,
     '019e30c3-2c00-7001-8000-00000000c001',
     'Seed planning day — full crew'),
    ('019e30c3-2c00-7001-8000-000000000e02',
     '019e30c3-2c00-7001-8000-000000000001',
     CURRENT_DATE + ((6 - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7
                     + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE)::int = 6 THEN 7 ELSE 0 END),
     '019e30c3-2c00-7001-8000-00000000c002',
     'Seed planning day — weekend, no crew')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_planning_day_assignment
    (id, operating_club_id, planning_day_id, assigned_person_id, assignment_type_id)
VALUES
    ('019e30c3-2c00-7001-8000-000000000f01',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b1',
     '019e30c3-2c00-7001-8000-0000000000d3'),
    ('019e30c3-2c00-7001-8000-000000000f02',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b2',
     '019e30c3-2c00-7001-8000-0000000000d2'),
    ('019e30c3-2c00-7001-8000-000000000f03',
     '019e30c3-2c00-7001-8000-000000000001',
     '019e30c3-2c00-7001-8000-000000000e01',
     '019e30c3-2c00-7001-8000-0000000000b3',
     '019e30c3-2c00-7001-8000-0000000000d1')
ON CONFLICT (id) DO NOTHING;
