> Traduction française du `README.md` de la branche `cursor/wifi-underlay-bonus-5fd4` (PR #2).

# Traffic Monitoring

Moniteur personnel de confidentialité réseau pour Android. La V1 enregistre quelles applications installées ont communiqué avec quels serveurs distants pendant une session de surveillance. Elle ne déchiffre pas le TLS, n'inspecte pas le contenu des données, ne bloque pas d'applications et n'envoie aucune métadonnée à l'extérieur.

Le prototype jetable d'étude de faisabilité de Firestack reste dans `spike/` et n'est pas modifié. Le code de production est le module `:app` à la racine du dépôt. Le choix du moteur d'acheminement et ses limites sont décrits dans `FIRESTACK_SPIKE_REPORT.md` : Firestack est **adapté avec modifications**, figé sur la même version que celle utilisée par le prototype sur un Galaxy A57.

## Ce que fait cette version

- Navigation Jetpack Compose : Surveillance, Résultats, Réglages.
- `VpnService` de premier plan (`systemExempted`), autorisation VPN, notification permanente, démarrage/arrêt.
- `ForwardingEngine` réalisé avec Firestack (`com.celzero:firestack:c4a33649be@aar`, commit `c4a33649be94e6a4709dc3b098cd57453aacf34b`, MPL-2.0). Le reste de l'application n'appelle pas Firestack directement.
- Le moteur possède le TUN. Les sockets passent par `protect` puis sont rattachées à un réseau sous-jacent autre que le VPN. Un Wi-Fi validé est préféré lorsqu'il est disponible ; sinon, la session utilise les données mobiles. `preflow` appelle `ConnectivityManager.getConnectionOwnerUid`. Les compteurs rx/tx des flux fermés et les réponses DNS en clair alimentent un `FlowTracker` en mémoire. Le même traitement d'attribution, de destinations et de classification fonctionne quel que soit le réseau sous-jacent.
- À l'arrêt (et environ toutes les 20 secondes), une `MonitoringSession` et des lignes regroupées par application et par destination sont écrites dans Room. La session enregistre le type de réseau sous-jacent (`WIFI` ou `CELLULAR`) relevé au démarrage, et met cette valeur à jour si le réseau préféré change. Les octets sont les compteurs d'acheminement de Firestack, pas les longueurs IP brutes du TUN.
- Les résultats affichent la dernière session terminée : applications, destinations et une catégorie lorsque la petite liste locale provisoire trouve une correspondance. Sinon, la catégorie reste **Inconnu**. La ligne de session indique « Session en Wi‑Fi » ou « Session en 5G / mobile ».

## Wi-Fi

Le Wi-Fi est pris en charge en bonus. La cible principale reste les données mobiles / la 5G. Lorsqu'un réseau Wi-Fi validé est disponible, les sockets sortantes y sont rattachées, `VpnService.setUnderlyingNetworks` le désigne, et Firestack reçoit les serveurs DNS de ce réseau. Sinon, la session utilise le réseau mobile. Cette version ne prétend pas que le passage du Wi-Fi au réseau mobile en plein trajet est fiable, et elle ne recrée pas le VPN lorsque le réseau radio change.

## Ce qui est provisoire

- Filtre VPN par application (la commande est visible mais désactivée ; la surveillance porte sur toutes les applications du profil actuel).
- Historique complet, suppression session par session, et conservation automatique de 30 jours / 500 Mo. Les réglages permettent d'effacer toutes les sessions enregistrées. Les valeurs par défaut sont affichées mais pas appliquées. Une session restée `RUNNING` après l'arrêt brutal du processus est marquée `FAILED` au lancement suivant.
- Jeux de données Tracker Radar et Disconnect. `DestinationClassifier` existe ; `StubDestinationClassifier` reconnaît quelques suffixes bien connus à partir d'une liste intégrée (`LISTE_LOCALE_MINIMALE`). Aucune recherche n'est faite sur le réseau.
- `PolicyEngine` autorise toujours. Les ports du DNS virtuel autres que 53 ne sont pas acheminés (`Backend.Block`), car cette version ne termine pas le DNS sur TLS. Il s'agit de la règle d'intégration DNS de Firestack issue du prototype, pas d'un pare-feu applicatif. Le vrai trafic distant, y compris vers le vrai port 853, passe toujours par `Backend.Exit`.
- Pas de capture de paquets, pas d'enregistrement du contenu des données, pas de métadonnées TLS ni d'analyse du contenu.

## Outils de compilation

| | |
|---|---|
| AGP | 8.13.2 (connaît l'API 36 ; l'AGP 8.7 du prototype ne la connaissait pas) |
| Gradle | 8.13 |
| Kotlin | 2.1.21 |
| compileSdk / minSdk / targetSdk | 36 |
| JDK | 17 ou plus récent |
| ABI | `arm64-v8a` uniquement |
| Firestack | `com.celzero:firestack:c4a33649be@aar` |

`Intra.connect` est appelé avec une MTU de lien positive. Dans cette version figée, `connect2` / `connect3` peuvent créer un tunnel supplémentaire.

## Ouvrir, compiler, installer

1. Installer la plateforme 36 du SDK Android et les build-tools 36 (gestionnaire de SDK d'Android Studio, ou `sdkmanager "platforms;android-36" "build-tools;36.0.0"`).
2. Créer `local.properties` à la racine du dépôt :

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```

3. Ouvrir la racine du dépôt dans Android Studio (pas `spike/`).
4. Compiler et installer un APK de mise au point (debug) sur le Galaxy A57 (ARM64, Android 16) :

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Ouvrir **Moniteur réseau**, autoriser les notifications, appuyer sur **Démarrer la surveillance** et accepter la demande d'autorisation VPN. Il n'y a pas de serveur VPN distant.
6. Utiliser d'autres applications, puis appuyer sur **Arrêter** (à l'écran ou dans la notification).
7. Ouvrir **Résultats**. La dernière session liste les applications et les destinations. Les noms apparaissent lorsque le DNS en clair était visible ; sinon, la ligne affiche l'IP. Les catégories restent surtout **Inconnu** tant qu'une vraie liste locale n'a pas été ajoutée.

Tests unitaires (sans appareil) :

```bash
./gradlew :app:testDebugUnitTest
```

## Journaux et base de données

Rien n'est envoyé à l'extérieur. Le fichier Room et son journal WAL se trouvent dans le stockage privé de l'application :

```text
/data/data/com.trafficmonitor.privacy/databases/traffic_monitor.db
```

Le même chemin est affiché dans Réglages. Récupérez-le ou examinez-le avec `adb shell run-as com.trafficmonitor.privacy`.

Les lignes de cycle de vie et de flux fermés sont écrites dans logcat avec l'étiquette `TrafficMonitor` (les lignes de flux sont au niveau debug) :

```bash
adb logcat -s TrafficMonitor
```

Il n'y a ni PCAP ni journal du contenu des données. Le fichier `files/firestack-spike/events.jsonl` du prototype appartient à une autre application (`com.trafficmonitor.firestackspike`).

## Organisation

Paquet `com.trafficmonitor.privacy`, conforme au §33 de SPEC :

```text
ui/            Compose screens
monitoring/    VpnService, FlowTracker, attribution, DNS evidence
forwarding/    ForwardingEngine + Firestack adapter
classification/ DestinationClassifier + local stub
data/          Room and repository
privacy/       Retention hook (manual erase; automatic limits later)
```

Le TUN n'est pas analysé une seconde fois. `PacketParser` interprète seulement les adresses et ports déjà signalés par le moteur d'acheminement.
