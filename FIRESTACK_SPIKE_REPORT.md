# Rapport du prototype jetable Firestack — Android 16

Date : 2026-09-26  
Cible : Samsung Galaxy A57 5G (`SM-A576B`), Android 16 / API 36, ARM64  
Périmètre : prototype jetable d'étude de faisabilité pour le point 41 ; ce n'est pas l'application de production

## Résultat en bref

Firestack fournit les points d'intégration essentiels pour un moniteur VPN local fonctionnant
dans l'espace utilisateur : le descripteur de fichier TUN d'Android, la sortie directe vers
Internet, la protection et le rattachement des sockets à un réseau, les identifiants d'origine
des flux (adresses et ports), l'injection précoce de l'UID, des rappels DNS, des compteurs à la
fermeture des flux et des statistiques globales de la pile réseau. L'AAR, dans la version exacte
figée, se compile en un APK ARM64 ciblant l'API 36, et ses fichiers ELF natifs ainsi que
l'empaquetage de l'APK satisfont les vérifications statiques d'alignement sur 16 Kio.

L'APK a ensuite été installé et utilisé sur l'appareil cible en Wi-Fi. L'essai validé a duré
de 13:14:35 à 13:17:13 UTC et a démontré l'acheminement direct TCP/UDP sur IPv4 et IPv6, un
trafic UDP/443 compatible avec QUIC, l'observation du DNS en clair, l'attribution par UID
Android, des compteurs dans les deux sens, des sockets protégées et rattachées au réseau, ainsi
qu'un démarrage et un arrêt propres. ICMP, la révocation du VPN, les données mobiles, les
passages Wi-Fi/5G et l'exécution sur un système à pages de 16 Kio restent à tester.

## Fichier et version testés

- Coordonnées Maven : `com.celzero:firestack:c4a33649be@aar`
- Commit Git complet : `c4a33649be94e6a4709dc3b098cd57453aacf34b`
- Date et message du commit : 2026-09-20, `rpn: rebuild auto exclusions on each fork to simplify`
- SHA-256 de l'AAR : `8fe63dac2028831b4fc57bd15c304a948c7e2eb1c6f11969f568d1deefeb0731`
- Licence déclarée par le code source et le POM Maven : MPL-2.0
- Compilation du prototype : AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, bytecode Java 17
- Configuration Android : `compileSdk 35`, `minSdk 36`, `targetSdk 36`
- APK : `spike/app/build/outputs/apk/debug/app-debug.apk`, environ 22 Mio
- Résultat de la compilation : `./gradlew assembleDebug` — **RÉUSSI**

L'écart d'API ci-dessus est volontaire, mais il ne convient pas pour la production. Seules
l'API 35 et une future plateforme nommée `android-37.0` étaient installées localement ; AGP 8.7
ne sait pas utiliser cette dernière. D8 a averti qu'il ne comprend les niveaux d'API que
jusqu'à 35. Le manifeste contient néanmoins `minSdkVersion=36` et `targetSdkVersion=36`. Une
preuve valable pour la production doit recompiler avec un AGP/D8 et une plateforme stable qui
prennent officiellement en charge l'API 36.

## Caractéristiques de l'appareil réellement obtenues

ADB a renvoyé :

| Propriété | Valeur |
|---|---|
| Modèle | `SM-A576B` |
| Version d'Android | `16` |
| API | `36` |
| ABI principale | `arm64-v8a` |
| Taille de page à l'exécution | `4096` octets |

Ce téléphone précis n'est donc pas un appareil de test pour l'exécution avec des pages de 16 Kio.

## Réalisation du prototype

Le code se trouve dans `spike/`. Il contient volontairement uniquement :

- une activité minimale pour l'autorisation VPN, le démarrage, l'arrêt et la suppression du journal ;
- un `VpnService` de premier plan avec des routes par défaut complètes IPv4 et IPv6 ;
- un `Bridge` Firestack unique qui envoie les flux Internet vers `Backend.Exit`, le port 53 du
  faux DNS vers `Backend.Base`, et bloque le DoT (DNS sur TLS), non pris en charge, vers le port
  virtuel 853 ;
- `VpnService.protect(fd)` et `Network.bindSocket(fd)` sur les réseaux sous-jacents autres que le VPN ;
- une recherche précoce avec `ConnectivityManager.getConnectionOwnerUid()` à partir des
  identifiants fournis par `preflow` ;
- de nouvelles tentatives explicites pour UDP, d'abord avec le port de destination zéro, puis
  sans destination précisée ;
- un journal JSONL pour les identifiants d'origine, l'état d'attribution, les noms de paquets,
  le DNS, les compteurs de flux, les changements de réseau sous-jacent, la protection et le
  rattachement des sockets, le cycle de vie et les statistiques globales de la pile ;
- une règle de choix du réseau qui privilégie un Wi-Fi validé, se replie sur le réseau mobile
  et met à jour la taille maximale de paquet (MTU) du tunnel sans supprimer en silence la route IPv6 ;
- l'injection dans Firestack des serveurs DNS du réseau physique au démarrage et à chaque
  changement de réseau sous-jacent ;
- aucun PCAP, aucune inspection TLS/SNI, pas de Room, pas de classification des traqueurs, pas
  d'interface d'historique ni de couches de production.

Fichier d'événements sur l'appareil :
`files/firestack-spike/events.jsonl`. Une attribution inconnue est enregistrée avec l'UID `-1`
et un état explicite (`UNKNOWN`, `UNSUPPORTED_PROTOCOL`, `INVALID_SOURCE` ou
`INVALID_DESTINATION`). Les UID partagés conservent tous les noms de paquets visibles.

## Tableau des exigences

« RÉUSSI (CODE SOURCE) » (`SOURCE PASS`) signifie que l'API et le code source figés, ainsi que
l'intégration compilée, prennent en charge ce cas, mais qu'il n'a pas été exercé sur l'appareil.
« NON EXÉCUTÉ » (`NOT RUN`) n'équivaut pas à RÉUSSI. Les chiffres d'exécution ci-dessous ne
concernent que l'essai final corrigé.

| Exigence | Résultat | Preuve | Limite |
|---|---|---|---|
| Possession du TUN Android | RÉUSSI | `Intra.connect(fd, ...)` a démarré sur l'API 36 ; la chaîne de version de Firestack indiquait Go 1.27.1/ARM64 ; 504 événements enregistrés | L'analyse des fuites s'est limitée à un seul arrêt propre |
| Acheminement local direct | RÉUSSI | Aucun VPN distant configuré ; des destinations extérieures IPv4/IPv6 ont échangé du trafic via `Backend.Exit` | Essai fonctionnel court, pas un essai d'endurance |
| Sockets sortantes protégées | RÉUSSI | Tous les rattachements de sockets enregistrés lors de l'essai final ont réussi ; protégées et rattachées au Wi-Fi | Rattachement au réseau mobile non testé |
| TCP IPv4 | RÉUSSI | Cinq flux TCP IPv4 extérieurs ; l'un s'est fermé normalement avec `rx=5634`, `tx=723` ; les compteurs TCP globaux ont progressé | Un serveur a refusé une connexion ; ce n'est pas une erreur d'acheminement de Firestack |
| UDP IPv4 | RÉUSSI | Neuf flux UDP/443 IPv4 extérieurs, plus du trafic DNS physique réussi | Pas de mesure UDP de longue durée |
| TCP IPv6 | RÉUSSI | Huit flux TCP IPv6 extérieurs ; un flux TLS IPv6 fermé a transféré `rx=2338`, `tx=2237` avant une réinitialisation par le serveur | La réinitialisation par le serveur a eu lieu après le transfert des données |
| UDP IPv6 | RÉUSSI | Flux UDP extérieur vers `[2a03:2880:...]:443` via `Backend.Exit` | Un seul flux extérieur observé |
| QUIC / HTTP/3 | RÉUSSI AVEC RÉSERVE | Dix flux UDP/443, dont l'UID 10271 de Chrome vers `www.google.com` ; UDP/443 acheminé en IPv4 et IPv6 | L'ALPN HTTP/3 et le journal qlog n'ont pas été enregistrés : l'identification repose sur le transport et le domaine |
| ICMP / ICMPv6 | PARTIEL (CODE SOURCE) | Firestack déclare un type de flux ICMP et fournit des statistiques ICMPv4/v6 | La recherche du propriétaire par Android n'est tentée que pour TCP/UDP ; ping (echo) et PMTU non testés |
| Observation du DNS | RÉUSSI AVEC RÉSERVE | 59 requêtes et 59 réponses réussies ; noms A/AAAA et réponses observés via le DNS physique `[fd0f:ee:b0::1]:53` | Les tentatives DoT/853 virtuelles ont été bloquées ; le DoH des applications reste volontairement invisible en clair |
| Identifiants d'origine | RÉUSSI | 88 flux ont enregistré le protocole et les adresses/ports d'origine source/destination avant l'aiguillage | Échantillon court |
| Attribution par UID | RÉUSSI AVEC RÉSERVE | 84 flux rattachés à des paquets/UID visibles ; quatre flux d'UID système sans paquet visible ; aucun `INVALID_UID` rencontré | La gestion d'INVALID_UID est en place mais n'a pas été exercée lors de cet essai |
| Compteurs dans les deux sens | PARTIEL | Les flux fermés ont fourni des Rx/Tx non nuls ; total final de 198,07 Kio reçus (Rx) et 1,90 Mio envoyés (Tx), 926/1881 paquets | Pas de comparaison contrôlée avec la taille des paquets d'origine ; pas de point d'accroche des octets TUN par flux |
| Wi-Fi | RÉUSSI | Wi-Fi validé choisi ; DNS IPv4/IPv6 et trafic Internet acheminés | Un seul réseau Wi-Fi testé |
| Données mobiles | NON EXÉCUTÉ | Détection et rattachement au réseau mobile sous-jacent en place | Aucun paquet acheminé |
| Wi-Fi → 5G | NON EXÉCUTÉ | Repli en cas de perte du réseau sous-jacent et mise à jour de la MTU en place | Comportement des flux existants inconnu |
| 5G → Wi-Fi | NON EXÉCUTÉ | Un Wi-Fi validé obtient une meilleure note de choix que le réseau mobile | Comportement des flux existants inconnu |
| Démarrage propre | RÉUSSI | Autorisation acceptée, VPN de premier plan créé, rappels Firestack et notification actifs ; aucun plantage | Redémarrage et VPN permanent non testés |
| Arrêt propre | RÉUSSI | L'arrêt par l'utilisateur a produit les bilans de déconnexion et supprimé le service ; aucun événement d'échec au démarrage ou à la déconnexion | Vérifié sur un seul essai, sans recherche de fuites sur des cycles répétés |
| Révocation du VPN | RÉUSSI (CODE SOURCE) / NON EXÉCUTÉ À L'EXÉCUTION | `onRevoke()` écrit dans le journal puis suit le même chemin d'arrêt | Révocation par le système non déclenchée |
| ARM64 | RÉUSSI | L'APK ne contient que `lib/arm64-v8a/libgojni.so` ; le code natif Go/JNI s'est chargé et a acheminé le trafic sur `SM-A576B` | Aucune pour l'ABI cible |
| Compatibilité 16 Kio | RÉUSSI (STATIQUE) / NON EXÉCUTÉ À L'EXÉCUTION | L'alignement des segments LOAD de l'ELF ARM64 est `0x4000` ; `zipalign -c -P 16 -v 4` réussit | Le téléphone cible utilise des pages de 4 Kio : pas d'essai de lancement avec des pages de 16 Kio |
| Performance | PARTIEL | Aucun plantage, blocage de l'application (ANR) ou arrêt du réseau observé ; `TOTAL PSS` du processus mesuré à 68 752 Kio et `TOTAL RSS` à 188 060 Kio | Processeur, batterie, chauffe et débit non mesurés formellement ; la RSS est élevée |

## Points d'accroche disponibles pour la surveillance

### Cycle de vie des flux

- `preflow(protocol, uid, src, dst)` est documenté comme appelé avant l'établissement d'une
  nouvelle connexion. C'est le bon endroit pour demander à Android l'attribution du
  propriétaire à partir des identifiants non modifiés.
- `flow(...)` fournit le protocole, l'UID, la source, la destination, les IP de destination
  d'origine, les domaines associés ou probables, les listes de blocage et un indicateur ALG.
  Renvoyer `Backend.Exit` choisit la sortie directe.
- `flowing(mark)` confirme la marque finalement appliquée.
- `postflow(FlowSummary)` renvoie, après la fermeture, l'identifiant de connexion, le
  protocole, l'UID, la source, la cible, le relais choisi, Rx, Tx, la durée, le temps
  d'aller-retour (RTT) et un message.

Le code source figé documente explicitement les protocoles 6/TCP, 17/UDP, 1/ICMP et l'UID `-1`
lorsque le propriétaire est inconnu.

### DNS

`onQuery` et `onResponse` ont fourni le DNS ordinaire entrant dans le chemin de faux DNS de
Firestack. Lors de l'essai validé, les 59 réponses observées avaient toutes `rcode=0`, y compris
des réponses A et AAAA pour `example.com` et des serveurs Google/Facebook. Les serveurs DNS du
Wi-Fi physique ont été injectés avec `Intra.setSystemDNS()` ; sans cet appel, Firestack se
repliait sur `localhost:53`, inutilisable sous Android.

Cela ne peut pas rendre visible en clair le DNS chiffré :

- le DNS système en clair était observable ;
- le DNS privé opportuniste d'Android a tenté TCP/853 vers l'IP DNS virtuelle du VPN.
  Le prototype a bloqué ces quatre tentatives de DoT virtuel, car il ne termine pas le TLS ;
  Android a ensuite utilisé le chemin fonctionnel par le port 53. Le DNS privé strict nécessite
  encore un essai dédié ;
- le DoH/DoQ des applications ne devrait laisser voir que des métadonnées d'IP et de transport ;
- aucune inspection du TLS ClientHello ou du SNI n'a été ajoutée.

Ce sont des limites de visibilité voulues, pas des échecs d'attribution.

### Réseau et sockets

Le côté `Controller` fournit `protect`, `bind4` et `bind6`. Le prototype protège chaque
descripteur (FD) sortant et le rattache au `Network` physique choisi. C'est nécessaire à la
fois pour éviter une boucle d'acheminement dans le VPN et pour diriger les nouvelles sockets
lors des passages Wi-Fi/mobile.

## Constat sur les compteurs de trafic

Trois niveaux sont fournis, mais seuls deux sont directement utilisables sans modifier Firestack :

1. **Taille d'origine des paquets IP du TUN** — aucun rappel par paquet ou par flux n'est
   fourni. Firestack peut écrire un fichier PCAP, mais le prototype le laisse volontairement
   désactivé, car il enregistrerait le contenu des paquets. Les totaux Rx/Tx et le nombre de
   paquets de l'interface réseau (NIC) sont la vue la plus proche de la pile IP.
2. **Moteur d'acheminement / totaux par protocole** — `Tunnel.stat()` fournit des statistiques
   NIC, IP, TCP, UDP et ICMP. Elles servent à vérifier le bon état et à rapprocher les totaux,
   pas à compter par application.
3. **Compteurs de sockets à la fermeture des flux** — `FlowSummary.rx/tx` sont utilisables en
   pratique pour les totaux par application et par destination de la V1, mais doivent être
   présentés comme des octets d'acheminement/de sockets Firestack, tant qu'un transfert contrôlé
   n'a pas montré si les en-têtes sont inclus.

Choix pratique pour la V1 : utiliser `FlowSummary.rx/tx` pour le regroupement par flux, indiquer
que les valeurs sont des estimations issues de la couche d'acheminement, et les rapprocher
régulièrement des totaux NIC. Si le produit a besoin des octets IP d'origine exacts par
application, Firestack doit être modifié pour fournir des compteurs d'octets IP entrants et
sortants par flux, avant le traitement par la couche de transport. Les compteurs de contenu utile
ne doivent pas être présentés comme des totaux au niveau IP.

## Examen des fichiers natifs

L'AAR Maven contient une bibliothèque partagée Go/JNI par ABI :

| ABI | Fichier | Taille non compressée |
|---|---|---:|
| `arm64-v8a` | `libgojni.so` | 19 784 296 octets |
| `armeabi-v7a` | `libgojni.so` | 19 192 532 octets |
| `x86_64` | `libgojni.so` | 21 123 080 octets |
| `x86` | `libgojni.so` | 19 402 404 octets |

Le prototype n'empaquette que `arm64-v8a`. `readelf -lW` indique `Align 0x4000` pour tous les
segments LOAD ARM64 ; l'APK place la bibliothèque non compressée sur une limite de 16 Kio et
passe la vérification `zipalign -P 16` des build-tools Android 36. L'empaquetage statique est
donc compatible avec les exigences 16 Kio, mais la preuve à l'exécution nécessite encore un
appareil ou un émulateur à pages de 16 Kio.

## Autorisations et composants Android

Autorisations déclarées :

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `QUERY_ALL_PACKAGES` (visibilité des paquets pour l'attribution, uniquement dans le prototype)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`
- `POST_NOTIFICATIONS`

Le service exige `android.permission.BIND_VPN_SERVICE`, n'est pas exporté, déclare le type de
service de premier plan `systemExempted` et désactive explicitement le VPN permanent pour
l'expérience. L'utilisation de `QUERY_ALL_PACKAGES` en production nécessite un examen au regard
des règles de Google Play ou une stratégie de visibilité des paquets plus restreinte.

## Risques d'intégration et modifications nécessaires

1. **Défaut de l'API figée :** au commit testé, `Connect()` appelle `NewTunnel()` lorsque
   `linkmtu <= 0` mais ne renvoie pas son résultat ; il appelle ensuite aussi `NewTunnel2()`.
   Comme `Connect2/Connect3` passent `-1`, ils risquent de créer un tunnel supplémentaire, voire
   de le laisser fuir. Le prototype évite ce problème en appelant `Intra.connect` avec une MTU
   physique positive. La production devrait intégrer un petit correctif en amont
   (`return NewTunnel(...)`) ou utiliser une révision où il est corrigé, avec un test de
   non-régression.
2. **Compteurs manquants :** pas de rappel par flux, respectueux de la vie privée, donnant les
   octets IP d'origine. Ajouter des compteurs dans Firestack ou accepter explicitement des
   estimations au niveau des sockets/de l'acheminement, en les signalant comme telles.
3. **Propriétaire des flux ICMP :** l'API Android de l'UID propriétaire n'est pas utilisée pour
   ICMP/ICMPv6. Ces événements doivent rester non attribués ou utiliser un mécanisme justifié à
   part ; ils ne doivent pas être attribués à une application au hasard.
4. **Changements de réseau :** les nouvelles sockets peuvent être rattachées à nouveau, mais les
   sockets existantes sur un réseau perdu peuvent échouer. Seul l'essai réel prévu peut dire s'il
   faut recréer le tunnel ou fermer certains flux.
5. **Interface instable :** Firestack présente son API comme évolutive et livre un gros binaire
   Go/JNI. Figer le commit, l'isoler derrière `ForwardingEngine`, garder un adaptateur
   remplaçable et tester chaque mise à jour contre les régressions.
6. **Chaîne de compilation :** recompiler avec un Android Gradle Plugin/D8 qui connaît l'API 36
   avant la production.
7. **Licence :** la MPL-2.0 et les obligations liées à la distribution nécessitent l'examen
   juridique habituel du projet, surtout si le code source de Firestack est modifié.
8. **Règles d'intégration du DNS :** les adresses du faux DNS doivent inclure le port 53, les
   flux du faux DNS doivent renvoyer `Backend.Base`, le port virtuel 853 ne doit pas être envoyé
   vers `Exit`, la liste des DNS physiques doit être fournie avec `Intra.setSystemDNS()`, et
   `onQuery` doit choisir `Backend.System`. Ces règles ne ressortent pas clairement de l'API Java
   générée et nécessitent des tests de l'adaptateur.

## Essais restant à faire sur l'appareil

Le chemin Wi-Fi de base est terminé. Les essais restants, sans modifier les réglages DNS dans le
seul but de faire réussir les résultats, sont :

1. Tester le ping IPv4 et le ping IPv6, y compris le comportement PMTU.
2. Confirmer HTTP/3 avec l'export réseau (net-export) ou qlog du navigateur, plutôt que par simple
   déduction à partir d'UDP/443.
3. Tester le DNS privé strict d'Android tel qu'il est configuré ou disponible, puis une
   application facilement disponible utilisant du DNS chiffré. Mettre en correspondance les
   événements de flux et de DNS.
4. Avec ADB par USB, désactiver le Wi-Fi pendant des flux actifs et inactifs ; tester le nouveau
   trafic en 5G ; rétablir le Wi-Fi et
   recommencer. Noter la survie des anciens flux, l'accessibilité des nouveaux flux, l'état de
   l'UID et la MTU/PMTU.
5. Révoquer le VPN depuis les réglages Android, le redémarrer, puis l'arrêter depuis la
   notification. Rechercher un état VPN résiduel, des descripteurs ouverts et une activité en
   double des tâches de fond sur des cycles répétés.
6. Relever `dumpsys meminfo`, le processeur, l'état thermique, toute décharge évidente de la
   batterie et un débit représentatif sur un essai plus long. Les chiffres PSS/RSS de l'essai
   court ne sont qu'une base de départ.
7. Récupérer `events.jsonl` et calculer le taux de réussite de l'attribution et le taux
   d'`INVALID_UID` par protocole ; comparer des transferts d'octets contrôlés avec les compteurs
   des flux fermés et les totaux NIC.
8. Recommencer l'installation et le démarrage sur une image système ou un appareil utilisant
   réellement des pages de 16 Kio.

## Évaluation finale

**SUITABLE WITH MODIFICATIONS** (adapté avec modifications)

L'architecture de Firestack et l'interface de son API compilée conviennent mieux au moniteur
qu'un VPN distant ou qu'un simple relais de paquets rudimentaire : sortie locale directe,
identifiants d'origine des flux, injection précoce de l'UID, événements DNS et compteurs utiles
sont tous présents. Il n'est pas prêt à être adopté tel quel à cause du défaut
`Connect2/Connect3` de la version figée, de l'absence de compteurs d'octets IP d'origine par flux,
de règles d'intégration du DNS peu évidentes, de l'instabilité de l'API et de la chaîne de
compilation, d'une RSS initiale élevée, et des essais d'exécution restants (changements de
réseau, ICMP, 16 Kio). Le véritable acheminement IPv6 est désormais démontré sur l'appareil
cible ; la fiabilité lors des changements de réseau mobile reste une condition préalable à la
publication.

SUITABLE WITH MODIFICATIONS
