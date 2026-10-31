CREATE TABLE operator_operation (
    operation_id uuid PRIMARY KEY,
    caller text NOT NULL,
    reservation_line_id uuid NOT NULL,
    action varchar(12) NOT NULL CHECK (action IN ('HOLD','CONFIRM','CANCEL')),
    request_hash char(64) NOT NULL,
    target_ref text,
    state varchar(12) NOT NULL CHECK (state IN ('IN_FLIGHT','SUCCEEDED','REJECTED','UNKNOWN','NOT_SENT')),
    attempt_id uuid NOT NULL,
    remote_ref text,
    response_json jsonb,
    error_code text,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_operator_reference ON operator_operation(caller,reservation_line_id,action,remote_ref)
    WHERE state = 'SUCCEEDED';

CREATE UNIQUE INDEX ux_operator_hold_per_line ON operator_operation(caller,reservation_line_id)
    WHERE action='HOLD' AND state IN ('IN_FLIGHT','SUCCEEDED','UNKNOWN','NOT_SENT');
CREATE UNIQUE INDEX ux_operator_confirm_per_hold ON operator_operation(caller,reservation_line_id,target_ref)
    WHERE action='CONFIRM' AND state IN ('IN_FLIGHT','SUCCEEDED','UNKNOWN','NOT_SENT');
