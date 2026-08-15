
CREATE FUNCTION gen_join_code() RETURNS TEXT
    LANGUAGE sql VOLATILE AS $$
    SELECT string_agg(
        substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789',
               1 + floor(random() * 31)::int, 1),
        '')
    FROM generate_series(1, 8);
$$;

ALTER TABLE t_club ADD COLUMN join_code TEXT NOT NULL DEFAULT gen_join_code();

UPDATE t_club
   SET join_code = 'SEEDCLUB'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001';

CREATE UNIQUE INDEX ux_club_join_code ON t_club (join_code);
