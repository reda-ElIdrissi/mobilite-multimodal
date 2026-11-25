# ADR 0007 — RabbitMQ plutôt que Kafka, avec outbox et inbox

**Statut.** Accepté.

## Contexte
Le bus doit réveiller le worker de saga, diffuser le résultat d'un dossier à plusieurs consommateurs indépendants (Tarification, Notifications) et offrir acquittements, reprises bornées et file de messages morts, pour un faible volume et un démarrage simple dans Compose.

## Décision
**RabbitMQ 4** :
- exchange topic durable `mobility.events` ; files durables `saga.work`, `pricing.reservation-results`, `notification.reservation-results` ; exchange `mobility.events.dlx` et une DLQ par file ; file d'audit bornée `reservation.step-audit` (10 000 messages, 24 h). Topologie déclarative (`EventTopology`).
- **Outbox** PostgreSQL dans Réservation, avec confirmation de l'éditeur. **Inbox** par consommateur, qui déduplique par identifiant d'événement ; l'acquittement a lieu après commit de l'inbox et de la projection.
- Erreur de contenu : DLQ dès le premier échec. Erreur d'accès aux données : jusqu'à trois tentatives avec temporisation, puis DLQ.
- Le bus accélère le traitement mais n'est pas nécessaire à la correction : si RabbitMQ est indisponible, un scanner PostgreSQL reprend les sagas (ADR 0005).
- Notifications mémorise la dernière version reçue par dossier et ignore doublons et versions anciennes. Tarification applique la règle `everConfirmed` : un devis `CONSUMED` ne régresse pas.

## Alternatives écartées
**Kafka** : journal rejouable, fort débit, ordre par partition. Aucun n'est nécessaire ici (rejeu assuré par l'outbox, ordre par version d'événement, volume faible), et il faudrait coder reprises et DLQ, avec une pile plus lourde.

## Conséquences
- Les files de résultats déjà liées conservent les messages persistants tant qu'ils attendent un consommateur. Une nouvelle file reçoit les publications à partir de sa liaison à l'exchange. L'outbox conserve l'enregistrement des publications.
- Livraison « au moins une fois » : tous les consommateurs doivent être idempotents.
