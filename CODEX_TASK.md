# Tâche : essai technique jetable de Firestack sous Android 16

Lisez `SPEC.md` et `REVUE_ANDROID_16.md`.

Cette tâche N'EST PAS la réalisation de l'application de production.

Créez un prototype technique jetable dont le seul but est de déterminer si Firestack convient comme moteur d'acheminement pour le Network Privacy Monitor.

Appareil cible :

- Samsung Galaxy A57 5G
- Android 16 / API 36
- ARM64

## Objectif principal

Déterminer si Firestack peut fournir une couche d'acheminement locale fiable basée sur `VpnService`, tout en fournissant assez d'informations pour répondre à nos exigences de surveillance.

Ne construisez pas la vraie interface, la base de données Room, le système de classification des traqueurs, le système d'historique ni l'architecture de production.

## Points à étudier

Déterminer si Firestack permet :

- de prendre possession du descripteur de fichier TUN d'Android ;
- l'acheminement local direct vers Internet, sans serveur VPN distant ;
- des connexions sortantes (sockets) protégées ;
- TCP ;
- UDP ;
- le trafic QUIC ;
- IPv4 ;
- IPv6 ;
- les besoins liés à ICMP/ICMPv6 ;
- le Wi-Fi ;
- les données mobiles ;
- le passage du Wi-Fi aux données mobiles et inversement ;
- l'observation du trafic DNS lorsque le DNS est visible ;
- des compteurs de trafic dans les deux sens ;
- l'observation des identifiants d'origine des connexions (adresses et ports) avant leur transformation par l'acheminement ou le relais ;
- l'intégration avec `ConnectivityManager.getConnectionOwnerUid()` ;
- un démarrage propre du VPN ;
- un arrêt propre du VPN ;
- la révocation du VPN ;
- ARM64 ;
- les pages mémoire de 16 Kio d'Android 16.

## Expérience d'attribution par UID

Pour chaque nouveau flux TCP/UDP observé, tentez l'attribution à une application le plus tôt possible, à partir des identifiants d'origine du flux.

Enregistrez :

- le protocole ;
- l'IP source ;
- le port source ;
- l'IP de destination ;
- le port de destination ;
- l'UID renvoyé ;
- l'état de l'attribution ;
- le ou les paquets d'application associés à l'UID.

L'expérience doit enregistrer explicitement les cas où Android renvoie `INVALID_UID`.

Ne supposez pas que l'attribution est toujours possible.

## Expérience sur les compteurs de trafic

Déterminez à quel niveau Firestack fournit des compteurs de trafic.

Comparez, lorsque c'est possible :

- la taille des paquets IP d'origine dans le TUN ;
- les compteurs du moteur d'acheminement ;
- les compteurs au niveau des sockets.

Indiquez quelle mesure serait utilisable en pratique pour la V1.

Ne remplacez pas en silence les octets IP par les octets de contenu utile.

## Expérience sur le DNS

Observez le trafic DNS ordinaire lorsque c'est possible.

Testez au moins :

1. le DNS normal du système ;
2. le DNS privé d'Android (Private DNS), s'il est activé ou disponible ;
3. une application utilisant du DNS chiffré, si c'est facile à tester.

Ne désactivez pas le DNS chiffré pour faire réussir l'expérience.

Notez quand les domaines sont observables et quand seules les adresses IP de destination sont disponibles.

N'ajoutez pas d'inspection du TLS ClientHello ou du SNI.

## Exigences IPv6

Vérifiez un véritable acheminement IPv6.

Le VPN ne doit pas laisser IPv6 le contourner en silence.

Testez :

- la connectivité IPv6 ;
- TCP sur IPv6 ;
- UDP sur IPv6 ;
- le comportement d'ICMPv6 lorsque c'est pertinent ;
- le comportement de découverte de la taille maximale de paquet (PMTU) lorsque c'est possible.

Si l'acheminement IPv6 complet ne peut pas être démontré, notez que le prototype ne satisfait pas l'exigence IPv6.

## Essai de changement de réseau

Pendant la surveillance :

1. démarrer en Wi-Fi ;
2. générer du trafic ;
3. désactiver le Wi-Fi ;
4. continuer en données mobiles ;
5. générer du trafic ;
6. rétablir le Wi-Fi.

Notez :

- si les flux existants survivent ;
- si les nouveaux flux fonctionnent ;
- si le VPN doit être recréé ;
- si l'attribution reste fonctionnelle.

## Observations de performance

Il ne s'agit pas d'une mesure de performance formelle.

Notez au moins :

- les problèmes évidents de processeur ;
- les problèmes évidents de batterie ou de chauffe ;
- la consommation de mémoire ;
- les plantages ;
- les blocages du réseau ;
- les baisses importantes de débit.

## Validation 16 Kio / bibliothèques natives

Examinez toutes les bibliothèques natives apportées par Firestack.

Vérifiez la compatibilité et l'alignement attendus pour Android 16 et les appareils à pages de 16 Kio.

Notez l'ABI et les fichiers natifs utilisés.

## Livrable

Produire :

`FIRESTACK_SPIKE_REPORT.md`

Ne vous contentez pas d'indiquer RÉUSSI ou ÉCHEC.

Incluez un tableau :

| Exigence | Résultat | Preuve | Limite |
|---|---|---|---|
| TCP IPv4 | RÉUSSI/ÉCHEC | ... | ... |
| UDP IPv4 | RÉUSSI/ÉCHEC | ... | ... |
| TCP IPv6 | RÉUSSI/ÉCHEC | ... | ... |
| UDP IPv6 | RÉUSSI/ÉCHEC | ... | ... |
| QUIC | RÉUSSI/ÉCHEC | ... | ... |
| Attribution par UID | ... | ... | ... |
| Observation du DNS | ... | ... | ... |
| Wi-Fi → 5G | ... | ... | ... |
| 5G → Wi-Fi | ... | ... | ... |
| Compteurs dans les deux sens | ... | ... | ... |
| Compatibilité 16 Kio | ... | ... | ... |

Documentez aussi :

- la version ou le commit exact de Firestack testé ;
- les dépendances exactes ;
- les autorisations nécessaires ;
- les composants natifs ;
- la complexité d'intégration ;
- les API et points d'accroche disponibles pour la surveillance ;
- les API et points d'accroche manquants ;
- toute modification qu'il faudrait apporter à Firestack.

## Évaluation finale

Terminez le rapport par l'une des mentions suivantes :

- `SUITABLE` (adapté)
- `SUITABLE WITH MODIFICATIONS` (adapté avec modifications)
- `UNSUITABLE` (inadapté)

Expliquez les raisons techniques.

NE commencez PAS à réaliser l'application de production après le rapport.

Attendez l'examen par une personne.
