# Autopartage

Les routes de gestion `/api/v1/vehicules` exigent un JWT de l’émetteur configuré, de l’audience `mobility-api` et du rôle OWNER. Les mutations vérifient le propriétaire par le claim `sub`. L’historique `GET /api/v1/locations?role=owner|renter` correspond au sujet authentifié. La recherche est interne et requiert le scope `offers:read`.

Flyway initialise un schéma vierge et Hibernate le valide. Pour démarrer le profil de démonstration et exécuter les vérifications, voir [le README racine](../README.md).


## Migration d’une base existante

Sauvegarder la base et inspecter une copie avant d’appliquer V4. Dans le schéma applicatif de la copie, préparer la table suivante et la charger avec les correspondances vérifiées entre identifiants numériques et sujets OIDC.

```sql
CREATE TABLE legacy_identity_mapping (
    legacy_id bigint PRIMARY KEY CHECK (legacy_id > 0),
    subject varchar(255) NOT NULL UNIQUE CHECK (btrim(subject) <> '')
);
```

Comparer propriétaires et locataires avant et après migration. Une correspondance manquante interrompt V4.
