# ADR 0009 — Identité : Keycloak, OIDC et JWT

**Statut.** Accepté.

## Contexte
Le client doit s'authentifier et chaque ressource (dossier, véhicule, notification) doit appartenir à un utilisateur. Écrire un système de mots de passe maison n'apporte rien au sujet.

## Décision
- **Keycloak** (realm unique) comme fournisseur OIDC. Le navigateur utilise Authorization Code avec PKCE S256 ; les composants techniques (BFF, Réservation, Tarification) utilisent Client Credentials.
- La Gateway et chaque service valident indépendamment signature (JWKS), issuer, durée et audience `mobility-api`.
- L'identité métier est le claim `sub`, filtré côté service (dossiers, locations, notifications). La Gateway retire les en-têtes d'identité fournis par le client.
- Autorisations par scopes et rôles : `offers:read` (recherche interne, client `search-bff`), `operators:write` (commandes d'opérateur, client Réservation), `pricing:issue`, `pricing:claim`, `locations:read`, rôles `OWNER`, `PRICING_ADMIN`, `SUPPORT_ADMIN`.

## Alternatives écartées
Service utilisateurs avec JWT maison : sécurité à maintenir pour aucun gain.

## Conséquences
Un conteneur de plus et un realm importé au démarrage avec des identifiants de démonstration (remplaçables par `.env`). Les services revalident le jeton, d'où une configuration répétée.
