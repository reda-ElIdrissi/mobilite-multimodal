ALTER TABLE reservation ADD COLUMN traceparent varchar(55);

CREATE TABLE inbox_event (
    event_id uuid PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);

-- Replay previous outcomes once after the two durable consumer queues are declared.
UPDATE outbox_event SET published_at = NULL WHERE event_type LIKE 'reservation.%.v1';
