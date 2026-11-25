# ADR 0005 — Saga orchestrée dans Réservation

**Statut.** Accepté.

## Contexte
Une réservation multimodale engage trois opérateurs et doit finir en « tout confirmé » ou « tout compensé ». Contraintes : deux participants legacy synchrones, incapables d'émettre des événements ; des maintiens qui expirent (10 minutes) ; des réponses parfois ambiguës.

## Décision
Le service `reservation` est l'**orchestrateur unique** :
- `POST /api/v1/reservations` enregistre en une transaction PostgreSQL le dossier, la clé d'idempotence, la saga et l'outbox, puis répond `202` avec une URI de suivi.
- Pour chaque dossier : réclamation du devis, revérification des offres, puis holds, puis confirms, dans l'ordre rail, air, voiture (et dans l'ordre des lignes pour un même opérateur). La fenêtre de maintien est contrôlée avant chaque confirmation.
- En cas d'échec, compensation en ordre inverse : annulation des maintiens et confirmations connus. Issues possibles : `CONFIRMED`, `FAILED_COMPENSATED`, `CANCELLED`, ou `NEEDS_RECONCILIATION` quand aucune preuve ne permet de trancher (ADR 0006).
- Chaque étape a un identifiant stable et passe à `IN_FLIGHT` avant l'appel distant. Un lease persistant avec jeton de clôture garantit un seul worker actif par dossier ; aucune transaction locale n'est ouverte pendant un appel distant.
- La **chorégraphie** n'intervient qu'en aval, pour les réactions non critiques au résultat final (Tarification, Notifications : ADR 0007).

## Alternatives écartées
- **Chorégraphie complète** : il faudrait faire émettre des événements aux systèmes legacy, la compensation serait dispersée sur quatre services et l'état global n'existerait nulle part.
- **2PC/XA** : les partenaires n'offrent aucun coordinateur, et un verrou distribué de plusieurs minutes est inacceptable.
- **Orchestrateur dans un service séparé** : il faudrait synchroniser un second état durable avec le dossier, sans bénéfice.

## Conséquences
- L'état d'un dossier se lit en un point (`GET /api/v1/reservations/{id}`, plus la vue des opérations pour `SUPPORT_ADMIN`), et la trace Jaeger suit la saga de bout en bout.
- L'orchestrateur est un point central, atténué par la persistance, le lease et la reprise à partir des étapes journalisées.
- Chaque opérateur est ACID localement ; la fédération n'est cohérente qu'à terme, après compensation.
