INSERT INTO regle (id, settings) VALUES
('00000000-0000-4000-8000-000000000001', '{"type":"PERCENT_DISCOUNT","condition":{"minDistinctModes":3},"rateBps":500,"priority":10,"active":true}'),
('00000000-0000-4000-8000-000000000002', '{"type":"FIXED_FEE_PER_ALLOCATION","mode":"VOITURE","amountMinor":1000,"priority":0,"active":true}')
ON CONFLICT DO NOTHING;
WITH seeded AS (
    INSERT INTO promotion (code, settings) VALUES ('DEMO', '{"rateBps":1000,"active":true}')
    ON CONFLICT DO NOTHING RETURNING code
)
INSERT INTO promotion_mode (code, mode) SELECT code, 'VOITURE' FROM seeded;
