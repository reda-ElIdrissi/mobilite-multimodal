# Décisions d'architecture (ADR)

Chaque fichier consigne une décision : contexte, décision, alternatives écartées, conséquences. Tous sont acceptés. Les diagrammes (vue d'ensemble et séquence de la saga) figurent dans le `README.md` à la racine du dépôt.

## Correspondance avec l'énoncé

| Attendu par l'énoncé | ADR |
|---|---|
| Choix des ponts CORBA↔REST et SOAP↔REST, couche anti-corruption | 0002 |
| Modèle canonique | 0003 |
| Gestion transactionnelle : saga, compensation | 0005 |
| Idempotence et reprises | 0006 |
| Orchestration ou chorégraphie | 0005 |
| ESB ou API Gateway | 0004 (et 0002) |
| Synchrone ou événementiel, choix du bus | 0007 |
| Microservices : contextes délimités, base par service, API versionnée | 0001 |
| Authentification | 0009 |
| Traçabilité distribuée et résilience | 0010 |
| Bonus gRPC | 0012 |

## Liste

| N° | Titre |
|---|---|
| 0001 | Découpage en microservices et données par service |
| 0002 | Bridges de protocole dédiés, couche anti-corruption |
| 0003 | Modèle canonique v1 |
| 0004 | API Gateway et BFF plutôt qu'un ESB |
| 0005 | Saga orchestrée dans Réservation |
| 0006 | Idempotence et issues incertaines |
| 0007 | RabbitMQ plutôt que Kafka, avec outbox et inbox |
| 0008 | Tarification : règles typées et devis immuables |
| 0009 | Identité : Keycloak, OIDC et JWT |
| 0010 | Résilience et observabilité |
| 0011 | Recherche directe et composition bornée d'itinéraires |
| 0012 | Aperçu tarifaire gRPC (bonus) |

## Intégrité du kit fourni

Depuis la racine du dépôt : `sha256sum -c docs/adr/starter-kit.sha256`. Les 24 fichiers de `starter-kit-mobilite/` doivent afficher `OK`.
