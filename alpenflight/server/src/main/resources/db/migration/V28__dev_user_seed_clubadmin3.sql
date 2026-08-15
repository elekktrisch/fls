
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
        '019e30c3-2c00-7100-8000-000000000011',
        '019e30c3-2c00-7001-8000-000000000001',
        'clubadmin3',
        'Club Admin Three',
        'clubadmin3@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',
        '9d08ed9c-699a-4c26-9036-9f0bd378003b'
    )
ON CONFLICT (id) DO NOTHING;
