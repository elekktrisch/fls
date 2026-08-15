
INSERT INTO t_user (
    id,
    club_id,
    username,
    friendly_name,
    notification_email,
    person_id,
    language_id,
    keycloak_sub
) VALUES (
    '019e30c3-2c00-7100-8000-000000000020',
    '019e30c3-2c00-7001-8000-000000000001',
    'pilot-empty1',
    'Pilot Empty One',
    'pilot-empty1@example.com',
    NULL,
    '019e2e15-2c00-77d0-8000-0000000007d0',
    '019e30c3-2c00-7200-8000-000000000020'
)
ON CONFLICT (id) DO NOTHING;
