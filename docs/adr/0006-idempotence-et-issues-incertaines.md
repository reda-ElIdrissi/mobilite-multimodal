# ADR 0006 — Idempotence et issues incertaines

**Statut.** Accepté.

## Contexte
Les ponts vers CORBA et SOAP peuvent connaître des reprises, des délais dépassés ou des réponses perdues. Rejouer une annulation est le cas normal. Le SOAP du kit ne publie aucun fault métier typé : il renvoie un SOAP Fault serveur générique.

## Décision
- **Idempotence client** : l'en-tête `Idempotency-Key` permet de rejouer la création ou l'annulation d'un dossier.
- **Journal par opérateur** (module `operator-journal`, une base par bridge, plus Autopartage) : chaque commande est inscrite avec un identifiant stable et l'empreinte de son contenu, rattachée à la ligne de réservation ; rejouer un identifiant avec un autre contenu est refusé (`LINE_OPERATION_CONFLICT`). Un seul maintien non rejeté est admis par ligne, et une seule confirmation non rejetée par maintien. L'état passe à `IN_FLIGHT` avant l'appel, la réponse est journalisée dans une seconde transaction.
- **États** : `PLANNED`, `IN_FLIGHT`, `SUCCEEDED`, `REJECTED`, `UNKNOWN`, `NOT_SENT` (absence d'envoi prouvée, par exemple IOR indisponible). Un `IN_FLIGHT` de plus de 30 s est vu comme `UNKNOWN`.
- **Règle de rejeu** : un hold ou un confirm `UNKNOWN` ne repart jamais vers le partenaire ; le worker interroge d'abord le journal (`GET /internal/v1/operations/{id}`). Un cancel peut être rejoué avec la même référence, car il est idempotent.
- **Classification SOAP** : seuls les motifs métier exacts observés (capacité, expiration) sont des `REJECTED`. Tout autre fault et toute erreur de transport restent `UNKNOWN`.
- **Réconciliation** : une confirmation ambiguë n'est jamais déclarée libérée au bout du TTL. Les autres ressources certaines sont libérées et le dossier reste `NEEDS_RECONCILIATION`. Un maintien legacy anonyme ne peut être clos que sur preuve opérateur soumise par `SUPPORT_ADMIN` ; le délai réseau seul n'autorise jamais cette clôture.
- **Autopartage** : mutation et journal dans une même transaction, avec verrou pessimiste par véhicule ; maintien de 10 minutes.

## Alternatives écartées
- **Retry universel** : rejouer une écriture legacy ambiguë risque un doublon.
- **Simuler l'atomicité** : on préfère exposer un état incertain à un faux succès.

## Conséquences
- Les cas qu'aucune preuve ne tranche sont visibles (`NEEDS_RECONCILIATION`) et demandent une action humaine.
- Chaque appel legacy coûte une écriture de journal avant et après.
