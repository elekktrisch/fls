
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
        '019e30c3-2c00-7100-8000-000000000012',
        '019e30c3-2c00-7001-8000-000000000001',
        'clubadmin4',
        'Club Admin Four',
        'clubadmin4@example.com',
        '019e2e15-2c00-77d0-8000-0000000007d0',
        'c1ab4d40-0000-4000-8000-000000000004'
    )
ON CONFLICT (id) DO NOTHING;
