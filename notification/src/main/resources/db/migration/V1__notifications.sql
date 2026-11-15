CREATE TABLE inbox_event (
    event_id uuid PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reservation_notification_version (
    reservation_id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    aggregate_version bigint NOT NULL
);
CREATE TABLE notification (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    reservation_id uuid NOT NULL REFERENCES reservation_notification_version(reservation_id),
    event_id uuid NOT NULL UNIQUE REFERENCES inbox_event(event_id),
    aggregate_version bigint NOT NULL,
    type varchar(32) NOT NULL,
    message text NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz
);
CREATE INDEX ix_notification_user ON notification(user_id, created_at DESC, id DESC);
