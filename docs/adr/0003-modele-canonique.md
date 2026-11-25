# ADR 0003 — Modèle canonique v1

**Statut.** Accepté.

## Contexte
Les trois sources décrivent une offre réservable de façons incompatibles :

| Source | Identifiant | Dates | Disponibilité |
|---|---|---|---|
| Rail (IDL `Trip`) | `tripId` | chaîne ISO-8601 sans fuseau | `long seatsAvailable` |
| Air (XSD `Flight`) | `flightId` | `xsd:string` sans fuseau | `int seatsAvailable` |
| Autopartage (REST) | véhicule + dates | `LocalDate` inclusives | capacité du véhicule |

Rail et air donnent un prix en centimes (`priceMinor`), l'autopartage un tarif journalier.

## Décision
Un modèle pivot unique, versionné dans le module `contracts` (`com.transport.mobility.contracts.v1`) et publié dans `docs/api/canonical-v1.openapi.json` :
- `Offer` (opérateur, mode, `offerId` opaque, origine et destination, `OffsetDateTime`, prix unitaire, type d'unité SEAT ou DAY, version de prix, capacité, date d'observation) ;
- `Money` : entier en centimes, MAD, jamais de flottant ;
- `Itinerary`, `Segment`, `Allocation` ;
- commandes et reçus de saga (`HoldCommand`, `HoldReceipt`, `ConfirmCommand`, `ConfirmationReceipt`, `CancelCommand`, `CancellationReceipt`) ;
- `OperationStatus` (PLANNED, IN_FLIGHT, SUCCEEDED, REJECTED, UNKNOWN, NOT_SENT) et `OperatorError`.

Chaque bridge traduit son partenaire vers ce modèle et seulement vers lui. Les heures locales reçoivent leur fuseau IANA grâce au catalogue de villes (`search-catalog`) ; une heure inexistante ou ambiguë est rejetée.

## Alternatives écartées
- **Exposer les modèles legacy** : le client devrait connaître trois formats.
- **DTO propres à chaque service** : risque de divergence sur les fuseaux et les centimes.
- **Prix en `double`** : erreurs d'arrondi. Le centime entier est déjà l'unité des partenaires.

## Conséquences
- La saga, le BFF et la tarification partagent un seul vocabulaire ; ajouter un mode revient à ajouter un bridge.
- Le module partagé est un noyau commun. Le versionnement `v1` (paquetage, OpenAPI, routes) permet d'introduire une v2 sans rupture.
