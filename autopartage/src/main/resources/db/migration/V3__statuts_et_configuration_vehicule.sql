-- Hibernate may have created a legacy ordinal CHECK. Replace only checks
-- whose sole column is status before changing the column type.
DO $$
DECLARE
    check_name text;
BEGIN
    IF EXISTS (SELECT 1 FROM locations WHERE status NOT IN (0, 1, 2, 3)) THEN
        RAISE EXCEPTION 'Unknown location status ordinal';
    END IF;
    FOR check_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = 'status'
        WHERE c.conrelid = 'locations'::regclass AND c.contype = 'c'
          AND c.conkey = ARRAY[a.attnum]
    LOOP
        EXECUTE format('ALTER TABLE locations DROP CONSTRAINT %I', check_name);
    END LOOP;
END $$;

ALTER TABLE locations ALTER COLUMN status TYPE varchar(32) USING (
    CASE status WHEN 0 THEN 'PENDING' WHEN 1 THEN 'CONFIRMED'
                WHEN 2 THEN 'CANCELLED' WHEN 3 THEN 'FAILED' END
);
ALTER TABLE locations ADD CONSTRAINT ck_locations_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED', 'EXPIRED'));
ALTER TABLE locations ALTER COLUMN rating_owner DROP NOT NULL,
                      ALTER COLUMN rating_renter DROP NOT NULL;

ALTER TABLE vehicules
    ADD COLUMN daily_rate_minor bigint CHECK (daily_rate_minor > 0),
    ADD COLUMN passenger_capacity integer CHECK (passenger_capacity > 0),
    ADD COLUMN price_version bigint NOT NULL DEFAULT 0 CHECK (price_version >= 0);
UPDATE vehicules SET is_available = false WHERE is_available;
ALTER TABLE vehicules ADD CONSTRAINT ck_vehicules_configured
    CHECK (NOT is_available OR (daily_rate_minor IS NOT NULL AND passenger_capacity IS NOT NULL));
