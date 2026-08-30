CREATE TABLE club (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    name text NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz
);

ALTER TABLE club ENABLE ROW LEVEL SECURITY;
ALTER TABLE club FORCE ROW LEVEL SECURITY;

CREATE POLICY club_isolation ON club
    USING (
        CASE
            WHEN current_setting('app.current_club_id', true) IS NULL
                OR current_setting('app.current_club_id', true) = '' THEN false
            ELSE id = current_setting('app.current_club_id', true)::uuid
        END
    )
    WITH CHECK (true);

INSERT INTO club (name) VALUES ('AlpenFlight');
