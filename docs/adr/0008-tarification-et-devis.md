# ADR 0008 — Tarification : règles typées et devis immuables

**Statut.** Accepté.

## Contexte
Le prix d'un trajet multimodal doit être calculé de façon déterministe, sans faire confiance au montant du client, et rester auditable.

## Décision
- `regle` et `promotion` stockent les paramètres typés dans `settings` (JSONB) et un compteur de version. `promotion_mode` associe une promotion à ses modes. Les devis conservent les versions d'ajustement appliquées dans leurs snapshots.
- `devis` stocke le cycle de vie, le total, l'empreinte d'itinéraire et le snapshot JSONB du panier tarifé. `ligne_devis` rattache un snapshot par allocation et `devis_ajustement` rattache les snapshots des ajustements à leur ligne. L'aperçu calcule le prix en lecture seule.
- Calcul : frais avant remises, remises sur le net hors frais, taux en points de base avec arrondi `HALF_UP`, répartition des montants globaux par plus forts restes, détection des dépassements.
- Cycle de vie : expiration par défaut après 5 minutes. La lecture projette `EXPIRED` et le claim initial d'un devis expiré reçoit `410`. Le claim verrouille le devis et le lie à une seule réservation. Le même dossier peut rejouer son acquisition. Tarification applique le résultat via RabbitMQ et réconcilie les devis réclamés via l'API interne de Réservation.
- Scopes : `pricing:issue` pour le BFF, `pricing:claim` pour Réservation, rôle `PRICING_ADMIN` pour la gestion des règles.

## Alternatives écartées
Prix codés en Java (modification = redéploiement) ; moteur de règles complet type Drools (excessif).

## Conséquences
Un nouveau type de règle exige du code ; changer un seuil ou un montant n'en exige pas. Le devis ferme revérifie prix et disponibilité auprès des opérateurs.
