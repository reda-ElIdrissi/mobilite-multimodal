# ADR 0008 — Tarification : règles typées et devis immuables

**Statut.** Accepté.

## Contexte
Le prix d'un trajet multimodal doit être calculé de façon déterministe, sans faire confiance au montant du client, et rester auditable.

## Décision
- Règles et promotions **typées et versionnées** en base (paramètres modifiables sans code, pas de code exécutable stocké). Désactiver une règle conserve les références historiques.
- Le **devis** est persisté à la sélection ; son détail financier et son panier canonique sont figés en JSONB. L'aperçu de recherche n'écrit rien.
- Calcul : frais avant remises, remises sur le net hors frais, taux en points de base avec arrondi `HALF_UP`, répartition des montants globaux par plus forts restes, détection des dépassements.
- Cycle de vie : expiration par défaut après 5 minutes (la lecture projette `EXPIRED`, un nouveau claim reçoit `410`) ; le claim verrouille le devis et le lie à une seule réservation, le rejeu restitue le devis acquis ; `CONSUMED` ou `VOID` sont alimentés par l'événement de résultat (ADR 0007).
- Scopes : `pricing:issue` pour le BFF, `pricing:claim` pour Réservation, rôle `PRICING_ADMIN` pour la gestion des règles.

## Alternatives écartées
Prix codés en Java (modification = redéploiement) ; moteur de règles complet type Drools (excessif).

## Conséquences
Un nouveau type de règle exige du code ; changer un seuil ou un montant n'en exige pas. Le devis ferme revérifie prix et disponibilité auprès des opérateurs.
