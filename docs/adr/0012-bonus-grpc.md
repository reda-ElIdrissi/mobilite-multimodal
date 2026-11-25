# ADR 0012 — Aperçu tarifaire gRPC (bonus)

**Statut.** Accepté, optionnel (profil `grpc-preview`).

## Contexte
L'énoncé propose gRPC entre microservices internes en bonus.

## Décision
Le profil `grpc-preview` remplace l'appel REST d'aperçu BFF → Tarification par l'appel unaire `mobility.pricing.v1.PreviewPricing/Preview` (module `pricing-grpc`). Les deux transports appellent `PricingService.preview`. Le BFF place un JWT technique dans la métadonnée `authorization`. Le serveur utilise le décodeur JWT du resource server et exige le scope `pricing:issue`. Jeton manquant ou invalide donne `UNAUTHENTICATED`, scope manquant donne `PERMISSION_DENIED`. Le client réutilise un canal avec un délai de 5 s et sans rejeu automatique. Les devis fermes et l'API publique restent en REST.

## Alternatives écartées
Passer tout le trafic interne en gRPC : périmètre trop large pour un gain non démontré.

## Conséquences
Le serveur limite les messages entrants à 512 Kio. L'override active le profil aux deux extrémités et utilise HTTP/2 sans chiffrement sur le réseau Compose, sans port gRPC publié sur l'hôte. Une panne gRPC produit une indisponibilité de l'aperçu, sans bascule vers REST. Compose utilise REST par défaut. Les mesures locales de latence et de taille dépendent des données et de l'environnement.

## Bonus non réalisés
Médiation ESB Apache Camel, service mesh, Kubernetes.
