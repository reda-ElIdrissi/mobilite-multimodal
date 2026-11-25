# ADR 0010 — Résilience et observabilité

**Statut.** Accepté.

## Contexte
Sans traçabilité, une panne dans un parcours à trois protocoles est introuvable. Les systèmes legacy peuvent être lents ou indisponibles.

## Décision
- **Traçabilité** : agent OpenTelemetry 2.17.1 embarqué dans les images (HTTP, AMQP, JDBC automatiques), spans et histogramme ajoutés aux frontières CORBA (`iiop`) et SOAP (`soap`). Collector 0.129.1 vers Jaeger 2.9 (traces en mémoire, démonstration) et métriques Prometheus. Les ports de visualisation sont liés à la boucle locale.
- **Corrélation** : Réservation conserve le contexte de la requête de création ; saga, opérations et publications le reprennent. Les étapes consignent les identifiants de saga et d'opération. Aucune donnée métier ni jeton dans les attributs ajoutés.
- **Résilience** : dans le BFF, un circuit breaker et un bulkhead Resilience4j par opérateur sur les lectures ; les 4xx métier n'ouvrent pas le circuit. Délais : 1,5 s de connexion et 4 s de réponse des bridges vers les systèmes legacy, 5 s de réponse du BFF vers les services. Les écritures de la saga ne sont pas rejouées aveuglément (ADR 0006). Sondes de disponibilité sur l'application et sa base.
- **Profils de démonstration** : contention (un autre acteur consomme les dernières places avant le second maintien) et expiration (TTL voiture de 30 s, lease de 60 s), pour provoquer des échecs réels et non simulés.

## Alternatives écartées
Retry universel (voir ADR 0006) ; journaux seuls sans trace distribuée.

## Conséquences
La trace s'arrête à la frontière cliente des bridges, car les systèmes fournis ne propagent pas `traceparent`. Jaeger en mémoire perd les traces au redémarrage.
