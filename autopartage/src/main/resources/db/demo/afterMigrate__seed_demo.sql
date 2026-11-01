INSERT INTO villes (nom) VALUES ('Paris'), ('Versailles') ON CONFLICT (nom) DO NOTHING;
INSERT INTO categories (nom) VALUES ('Citadine') ON CONFLICT (nom) DO NOTHING;

WITH vehicle AS (
    INSERT INTO vehicules (owner_subject, marque, modele, immatriculation, couleur, ville_id,
                           categorie_id, added_at, is_available, daily_rate_minor, passenger_capacity)
    SELECT '74fe3186-57c1-43a6-8f75-95cc328f77b3', 'Renault', 'Clio', 'DEMO-LOT1', 'Bleu', v.id, c.id, DATE '2026-11-15', true, 30000, 4
    FROM villes v CROSS JOIN categories c
    WHERE v.nom = 'Paris' AND c.nom = 'Citadine'
    ON CONFLICT (immatriculation) DO NOTHING
    RETURNING id
)
INSERT INTO ville_vehicule (ville_id, vehicule_id, debut, fin)
SELECT v.id, vehicle.id, DATE '2026-11-15', DATE '2026-11-16'
FROM villes v CROSS JOIN vehicle WHERE v.nom = 'Versailles';
