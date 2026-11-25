# ADR 0004 — API Gateway et BFF plutôt qu'un ESB

**Statut.** Accepté.

## Contexte
Le client attend une API unique, authentifiée et cohérente devant une dizaine de services. Un ESB placerait routage, transformation et orchestration au centre. Une API Gateway reste un aiguilleur.

## Décision
Deux responsabilités séparées :
1. **`api-gateway`** (Spring Cloud Gateway), seul point d'entrée (`:8080`), sans logique métier : routage de `/api/v1/*`, validation du JWT (signature, issuer, audience `mobility-api`), suppression des en-têtes d'identité fournis par le client (`X-User-Id`, etc.), CORS, refus de tout chemin non déclaré. Seule `POST /api/v1/search` est publique.
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
