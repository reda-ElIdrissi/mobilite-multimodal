# ADR 0010 — Résilience et observabilité

**Statut.** Accepté.

## Contexte
Sans traçabilité, une panne dans un parcours à trois protocoles est introuvable. Les systèmes legacy peuvent être lents ou indisponibles.

## Décision
- **Traçabilité** : agent OpenTelemetry 2.17.1 embarqué dans les images (HTTP, AMQP, JDBC automatiques), spans et histogramme ajoutés aux frontières CORBA (`iiop`) et SOAP (`soap`). Collector 0.129.1 vers Jaeger 2.9 (traces en mémoire, démonstration) et métriques Prometheus. Les ports de visualisation sont liés à la boucle locale.
- **Corrélation** : Réservation conserve le contexte de la requête de création ; saga, opérations et publications le reprennent. Les étapes consignent les identifiants de saga et d'opération. Aucune donnée métier ni jeton dans les attributs ajoutés.
- **Résilience** : le BFF protège les lectures par un circuit breaker et un bulkhead Resilience4j par opérateur. Les 4xx métier n'ouvrent pas le circuit. Les bridges fixent 1,5 s de connexion et 4 s de réponse legacy. Le BFF attend 5 s pour les appels HTTP aux partenaires et 2 s pour l'identité. Réservation attend normalement 6 s pour ses appels HTTP et utilise un lease de 30 s. Elle choisit les rejeux en fonction des résultats journalisés (ADR 0006). Les sondes des services persistants incluent leur base.
- **Profils de démonstration** : `demo-contention` attend qu'un autre acteur consomme les dernières places avant le second maintien. `demo-expiry` fixe le maintien voiture à 30 s et retarde sa confirmation de 35 s. L'override donne à Réservation 45 s pour cette réponse et un lease de 60 s pour observer le rejet métier du participant.

## Alternatives écartées
Retry universel (voir ADR 0006) ; journaux seuls sans trace distribuée.

## Conséquences
La trace s'arrête à la frontière cliente des bridges, car les systèmes fournis ne propagent pas `traceparent`. Jaeger en mémoire perd les traces au redémarrage.
