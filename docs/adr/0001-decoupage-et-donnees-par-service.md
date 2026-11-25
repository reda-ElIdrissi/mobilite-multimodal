# ADR 0001 — Découpage en microservices et données par service

**Statut.** Accepté.

## Contexte
L'énoncé exige des microservices autonomes (contextes délimités, base par service, API REST versionnée) et interdit de modifier les deux systèmes legacy.

## Décision
- **Services métier** (Spring Boot) : `autopartage`, `tarification`, `reservation` (dossier et saga), `notification`.
- **Services d'exposition et d'intégration** : `api-gateway`, `search-bff`, `rail-bridge`, `air-bridge`.
- **Une base PostgreSQL et un compte par propriétaire** (`autopartage-db`, `tarification-db`, `reservation-db`, `notification-db`, `rail-bridge-db`, `air-bridge-db`, plus celle de Keycloak). Aucune jointure, clé étrangère ni SQL entre services : les références externes sont des identifiants.
- **API versionnée** : `/api/v1` côté client, `/internal/v1` entre services. Les contrats sont dans `docs/api` (OpenAPI, AsyncAPI).
- Des modules Maven partagés portent uniquement des contrats et du code transverse : `contracts`, `result-events`, `operator-journal`, `search-catalog`, `mobility-observability`.

## Alternatives écartées
- **Monolithe modulaire** : simplifierait l'ACID, mais contredit l'autonomie demandée.
- **Service métier « train » et « avion »** : dupliquerait le legacy, les bridges suffisent (ADR 0002).
- **Base partagée** : couplage par le schéma, déploiement non indépendant.

## Conséquences
- Plus d'unités à déployer, compensé par `docker compose up`.
- Il n'y a plus de transaction ACID entre services : la cohérence passe par la saga (ADR 0005).
- Les modules partagés forment un noyau commun qui couple les versions des services (voir ADR 0003).
