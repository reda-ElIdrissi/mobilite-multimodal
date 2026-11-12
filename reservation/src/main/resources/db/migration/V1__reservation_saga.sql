CREATE TABLE reservation (
    id uuid PRIMARY KEY,
    quote_id uuid NOT NULL UNIQUE,
    subject varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    snapshot jsonb,
    created_at timestamptz NOT NULL,
    quote_expires_at timestamptz NOT NULL,
    first_departure_at timestamptz NOT NULL,
    deadline timestamptz,
    cancel_requested_at timestamptz,
    ever_confirmed boolean NOT NULL DEFAULT false,
    failure_code text,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_reservation_subject ON reservation(subject, created_at DESC, id);
CREATE TABLE request_idempotency (
    subject varchar(255) NOT NULL,
    action varchar(16) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_hash text NOT NULL,
    reservation_id uuid NOT NULL REFERENCES reservation(id),
    PRIMARY KEY(subject, action, request_key)
);
CREATE TABLE saga_instance (
    reservation_id uuid PRIMARY KEY REFERENCES reservation(id),
    next_run_at timestamptz,
    lease_until timestamptz,
    lease_token uuid
);
CREATE INDEX ix_saga_due ON saga_instance(next_run_at) WHERE next_run_at IS NOT NULL;
CREATE TABLE ligne_reservation (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation(id),
    position integer NOT NULL,
    segment_index integer NOT NULL,
    allocation jsonb NOT NULL,
    UNIQUE(reservation_id, position)
);
CREATE TABLE saga_step (
    operation_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation(id),
    line_id uuid REFERENCES ligne_reservation(id),
    action varchar(16) NOT NULL,
    state varchar(24) NOT NULL,
    target_ref text NOT NULL DEFAULT '',
    result jsonb,
    attempts integer NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    UNIQUE NULLS NOT DISTINCT(reservation_id,line_id,action,target_ref)
);
CREATE TABLE hold_expiry_evidence (
    operation_id uuid PRIMARY KEY REFERENCES saga_step(operation_id),
    execution_upper_bound_at timestamptz NOT NULL,
    safe_expiry_at timestamptz NOT NULL,
    evidence text NOT NULL,
    approved_by text NOT NULL,
    resolved_at timestamptz
);
CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation(id),
    event_type text NOT NULL,
    event_version bigint NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    UNIQUE(reservation_id,event_type,event_version)
);
