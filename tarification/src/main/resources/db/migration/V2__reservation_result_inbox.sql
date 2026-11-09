CREATE TABLE inbox_event (
    event_id uuid PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE devis ADD COLUMN reservation_event_version bigint NOT NULL DEFAULT 0,
                  ADD COLUMN next_reconcile_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_quote_reconciliation ON devis(next_reconcile_at) WHERE status='CLAIMED';
