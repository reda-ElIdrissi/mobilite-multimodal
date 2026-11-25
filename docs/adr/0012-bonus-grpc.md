# ADR 0012 — Aperçu tarifaire gRPC (bonus)

**Statut.** Accepté, optionnel (profil `grpc-preview`).

## Contexte
L'énoncé propose gRPC entre microservices internes en bonus.

## Décision
Le profil `grpc-preview` remplace l'appel REST d'aperçu BFF → Tarification par l'appel unaire `mobility.pricing.v1.PreviewPricing/Preview` (module `pricing-grpc`). Les deux transports appellent la même méthode `PricingService.preview`. Le service gRPC applique le même décodeur JWT et exige `pricing:issue`. Le client réutilise un canal, fixe un délai de 5 s et désactive le rejeu automatique. Les devis fermes, la Gateway et le contrat public restent en REST ; la configuration Compose de base reste en REST.

## Alternatives écartées
Passer tout le trafic interne en gRPC : périmètre trop large pour un gain non démontré.

## Conséquences
Ajout d'une génération de code, d'un canal interne et d'une configuration à tester. Le canal de démonstration est en HTTP/2 non chiffré sur le réseau Compose ; hors de ce réseau, il faudrait TLS. Les mesures locales de latence et de taille servent de comparaison, sans promesse de gain.

## Bonus non réalisés
Médiation ESB Apache Camel, service mesh, Kubernetes.
