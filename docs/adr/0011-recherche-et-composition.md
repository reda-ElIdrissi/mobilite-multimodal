# ADR 0011 — Recherche directe et composition bornée d'itinéraires

**Statut.** Accepté.

## Contexte
La recherche interroge en parallèle trois opérateurs d'unités différentes (siège, jour). Les opérateurs ne fournissent aucun graphe global d'itinéraires.

## Décision
- **Recherche directe** (`POST /api/v1/search`) : le BFF appelle bridges et Autopartage en parallèle avec un jeton `offers:read`. Concurrence limitée à 9 appels, file de 24, 6 s au plus, 100 offres maximum (`truncated` signale le plafond). Un opérateur en panne donne `200` avec `partial=true` et l'erreur par opérateur ; la panne de tous donne `503`.
- **Composition** : activée par `maxSegments`, `via`, `driverCount` ou `carDurationMinutes`. Parcours en profondeur borné à 3 segments et 20 candidats, 96 appels par défaut (`mobility.composition-max-calls`), 6 en parallèle, hubs configurables (`RAB,PAR` par défaut). Correspondances minimales de 90 minutes vers un vol et 30 minutes sinon. Un segment voiture peut combiner jusqu'à trois véhicules.
- Tarification classe les candidats via un aperçu batch ; en cas de panne, les candidats restent avec `partial=true` et `pricingError=PRICING_UNAVAILABLE`. La recherche ne crée ni devis ni maintien.
- Le devis ferme recharge les offres chez les opérateurs ; le prix du client n'est jamais cru.

## Alternatives écartées
Recherche directe seule (trop pauvre pour un trajet multimodal) ; parcours exhaustif ou moteur de routage global (pas de données fiables, coût non borné).

## Conséquences
Le résultat est le meilleur parmi les candidats explorés, sans garantie d'optimum global. Une ville hors catalogue exige une extension explicite de `search-catalog`.
