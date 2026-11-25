# ADR 0004 — API Gateway et BFF plutôt qu'un ESB

**Statut.** Accepté.

## Contexte
Le client attend une API unique, authentifiée et cohérente devant une dizaine de services. Un ESB placerait routage, transformation et orchestration au centre. Une API Gateway reste un aiguilleur.

## Décision
Deux responsabilités séparées :
1. **`api-gateway`** (Spring Cloud Gateway), point d'entrée de l'API métier sur `:8080`. Il valide le JWT, applique CORS, refuse les chemins hors de la liste autorisée et transmet les requêtes aux services. Un filtre global retire `X-User-Id`, `X-Owner-Id`, `X-Renter-Id` et `X-Actor-User-Id` sur toutes les routes. Les routes protégées transmettent le JWT. La seule route métier publique, `POST /api/v1/search`, retire aussi `Authorization` avant d'appeler le BFF. Le filtre d'identité laisse passer les en-têtes de traçabilité.
2. **`search-bff`** : agrégation des lectures (bridges et Autopartage en parallèle), composition d'itinéraires, aperçu tarifaire, réponses partielles (`partial=true`) si un opérateur tombe (ADR 0011).

La médiation de protocole reste dans les bridges (ADR 0002) et l'orchestration transactionnelle dans Réservation (ADR 0005).

## Alternatives écartées
- **ESB central** : monolithe d'intégration, goulot de déploiement et point de panne unique. C'est le mouvement « de l'ESB vers les extrémités » de l'énoncé. La comparaison pratique avec une médiation Camel (bonus) n'a pas été réalisée.
- **Agrégation dans la Gateway** : elle devrait connaître le modèle d'itinéraire et deviendrait un service métier.
- **Pas de Gateway** : authentification dispersée, topologie interne exposée.

## Conséquences
- Le client ne voit que l'API canonique v1.
- Chaque service revalide aussi le JWT (défense en profondeur), au prix d'une configuration Keycloak répétée.
- La Gateway reste simple et remplaçable.
