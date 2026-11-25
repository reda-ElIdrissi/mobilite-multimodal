# ADR 0002 — Bridges de protocole dédiés, couche anti-corruption

**Statut.** Accepté.

## Contexte
L'opérateur ferroviaire ne parle que CORBA/IIOP, le partenaire aérien que SOAP/XML. Le starter kit ne doit pas être modifié (intégrité vérifiable : `sha256sum -c docs/adr/starter-kit.sha256`, 24 fichiers). La saga et le BFF doivent les piloter comme un participant REST ordinaire.

## Décision
Un microservice par système legacy, `rail-bridge` et `air-bridge`, qui expose la même API interne :
`POST /internal/v1/offers/search`, `POST /internal/v1/holds`, `POST /internal/v1/holds/{ref}/confirm`, `POST /internal/v1/cancellations`, `GET /internal/v1/operations/{id}`.

- **rail-bridge** : stubs générés par le compilateur IDL de JacORB depuis `rail.idl`. L'IOR est relue à chaque appel pour suivre un redémarrage. Le serveur du kit reste en Java 8, le bridge en Java 17 (avec JBoss RMI API, car `javax.rmi.CORBA.Stub` n'existe plus depuis Java 11).
- **air-bridge** : classes JAXB générées depuis `air.xsd` (`jaxb2-maven-plugin`), appel par `WebServiceTemplate`. Le XSD du kit fait foi : `departure` et `arrival` y sont des `xsd:string`, et non des `xsd:dateTime` comme dans l'extrait de l'énoncé.
- Délais identiques : 1,5 s de connexion, 4 s de réponse.
- Traduction des erreurs : `NotAvailable` (IDL) et les motifs SOAP reconnus deviennent `REJECTED`; les erreurs de transport deviennent `UNKNOWN` (ADR 0006).

## Alternatives écartées
- **ESB (Apache Camel)** : concentrerait la connaissance des trois protocoles dans un composant unique, point de couplage et de panne. On garde la médiation de protocole, mais localisée par partenaire (« smart endpoints, dumb pipes »). Voir ADR 0004.
- **Appels CORBA/SOAP directs depuis Réservation ou le BFF** : les types legacy (`Trip`, `Flight`, exceptions IDL, SOAP Faults) contamineraient le domaine.
- **Un bridge unique** : mélangerait deux piles lourdes (ORB et JAXB) dans un même déploiement.

## Conséquences
- Aucun client ni service interne ne voit de l'IIOP ou du XML.
- Coût : deux services, deux bases, un saut réseau de plus.
- Le contexte de trace s'arrête à la frontière cliente des bridges, car les systèmes fournis ne propagent pas `traceparent`. Des spans `iiop` et `soap` mesurent ces appels (ADR 0010).
