
INSERT INTO t_user (
    id,
    club_id,
    username,
    friendly_name,
    notification_email,
    language_id,
    keycloak_sub
) VALUES
    (
        '019e30c3-2c00-7100-8000-000000000001',
        '019e30c3-2c00-7001-8000-000000000001',
        'clubadmin1',
        'Club Admin One',
        'clubadmin1@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',
        '9d08ed9c-699a-4c26-9036-9f0bd378009d'
    ),
    (
        '019e30c3-2c00-7100-8000-000000000002',
        '019e30c3-2c00-7001-8000-000000000001',
        'pilot1',
        'Pilot One',
        'pilot1@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',
        '376317c0-fc0a-439d-a5f7-9af17e5f4178'
    )
ON CONFLICT (id) DO NOTHING;
