# ADR 0006 — Idempotence et issues incertaines

**Statut.** Accepté.

## Contexte
Les ponts vers CORBA et SOAP peuvent connaître des reprises, des délais dépassés ou des réponses perdues. Rejouer une annulation est le cas normal. Le SOAP du kit ne publie aucun fault métier typé : il renvoie un SOAP Fault serveur générique.

## Décision
- **Idempotence client** : la création et l'annulation acceptent `Idempotency-Key`. Réservation enregistre la clé et l'empreinte de la demande et refuse une réutilisation incompatible. Tarification rattache le devis à un seul dossier et permet au même dossier de rejouer le claim.
- **Journal par participant** : `operator_operation` rattache l'identifiant stable, le demandeur et l'empreinte de la commande à la ligne. `OPERATION_ID_CONFLICT` signale un identifiant réutilisé avec un autre contenu et `LINE_OPERATION_CONFLICT` une commande concurrente incompatible. Les index partiels limitent la ligne à un HOLD non rejeté et le maintien à un CONFIRM non rejeté. Pour un bridge, une transaction enregistre `IN_FLIGHT` avant l'envoi, une seconde journalise la réponse.
- **États** : le journal persiste `IN_FLIGHT`, `SUCCEEDED`, `REJECTED`, `UNKNOWN` et `NOT_SENT`. Un appel `IN_FLIGHT` âgé de plus de 30 s et relu par le journal passe à `UNKNOWN`. Une réponse tardive peut encore mettre à jour `IN_FLIGHT` ou `UNKNOWN` si l'identifiant de tentative correspond.
- **Rejeux** : un refus métier certain donne `REJECTED`. Un HOLD ou CONFIRM `UNKNOWN` est relu par Réservation via `GET /internal/v1/operations/{id}` sans nouvel envoi au partenaire. Un `NOT_SENT` explicite, par exemple un échec CORBA avant invocation, permet un nouvel envoi de la même commande pendant la phase active. Une absence de ligne (`JOURNAL_ABSENT`) ne prouve pas la libération pendant la compensation. Pour un CANCEL, les rejeux restent possibles avec la même référence dans la limite fixée par la saga.
- **Classification SOAP** : seuls les motifs métier exacts observés (capacité, expiration) sont des `REJECTED`. Tout autre fault et toute erreur de transport restent `UNKNOWN`.
- **Réconciliation** : une confirmation ambiguë reste à résoudre après le TTL. La saga tente de libérer les autres engagements connus et maintient le dossier en `NEEDS_RECONCILIATION` tant que l'issue manque. Un maintien legacy sans référence peut être marqué `RELEASED` sur preuve opérateur contrôlée, soumise par `SUPPORT_ADMIN`. Une réussite tardive détectée après cette clôture rouvre la réconciliation et déclenche la compensation de la référence retrouvée.
- **Autopartage** : mutation et journal dans une même transaction, avec verrou pessimiste par véhicule ; maintien de 10 minutes.

## Alternatives écartées
- **Retry universel** : rejouer une écriture legacy ambiguë risque un doublon.
- **Simuler l'atomicité** : on préfère exposer un état incertain à un faux succès.

## Conséquences
- Les cas qu'aucune preuve ne tranche sont visibles (`NEEDS_RECONCILIATION`) et demandent une action humaine.
- Chaque appel legacy coûte une écriture de journal avant et après.
