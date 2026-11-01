CREATE TABLE IF NOT EXISTS legacy_identity_mapping (
    legacy_id bigint PRIMARY KEY CHECK (legacy_id > 0),
    subject varchar(255) NOT NULL UNIQUE CHECK (btrim(subject) <> '')
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (
            SELECT owner_id AS id FROM vehicules
            UNION SELECT owner_id FROM locations
            UNION SELECT renter_id FROM locations
        ) ids
        LEFT JOIN legacy_identity_mapping m ON m.legacy_id = ids.id
        WHERE m.legacy_id IS NULL OR m.subject IS NULL OR btrim(m.subject) = ''
    ) THEN
        RAISE EXCEPTION 'Missing verified OIDC identity mapping. See ADR 17.';
    END IF;
END $$;

ALTER TABLE vehicules ADD COLUMN owner_subject varchar(255);
UPDATE vehicules v SET owner_subject = m.subject
FROM legacy_identity_mapping m WHERE m.legacy_id = v.owner_id;

ALTER TABLE locations ADD COLUMN owner_subject varchar(255), ADD COLUMN renter_subject varchar(255);
UPDATE locations l SET owner_subject = o.subject, renter_subject = r.subject
FROM legacy_identity_mapping o, legacy_identity_mapping r
WHERE o.legacy_id = l.owner_id AND r.legacy_id = l.renter_id;

ALTER TABLE vehicules ALTER COLUMN owner_subject SET NOT NULL, ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE locations ALTER COLUMN owner_subject SET NOT NULL, ALTER COLUMN renter_subject SET NOT NULL,
    ALTER COLUMN owner_id DROP NOT NULL, ALTER COLUMN renter_id DROP NOT NULL;

CREATE INDEX ix_vehicules_owner_subject ON vehicules (owner_subject, id);
CREATE INDEX ix_locations_owner_subject ON locations (owner_subject, id);
CREATE INDEX ix_locations_renter_subject ON locations (renter_subject, id);
