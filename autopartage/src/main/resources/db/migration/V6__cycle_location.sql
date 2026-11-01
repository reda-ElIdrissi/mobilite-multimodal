ALTER TABLE locations
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN hold_operation_id uuid UNIQUE,
    ADD COLUMN confirmed_at timestamptz,
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN quoted_daily_rate_minor bigint CHECK (quoted_daily_rate_minor > 0),
    ADD COLUMN driver_alias varchar(255),
    ADD COLUMN reservation_line_id uuid,
    ADD COLUMN handover_at timestamptz,
    ADD COLUMN handover_city_id bigint REFERENCES villes;
CREATE INDEX ix_locations_expiry ON locations(expires_at) WHERE status='PENDING';
CREATE INDEX ix_vehicle_assignments ON vehiculelocations(vehicule_id,location_id);
