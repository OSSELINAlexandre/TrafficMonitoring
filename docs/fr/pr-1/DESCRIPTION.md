> Traduction française de la description de la PR #1 (branche `cursor/android-monitoring-core-df04`), « Add the first production network monitoring core ».

## Résumé

Premier noyau de production du moniteur de confidentialité réseau Android (`:app` à la racine du dépôt). Le prototype jetable Firestack dans `spike/` n'est pas modifié.

Il s'agit d'un VPN local de surveillance : pas de serveur distant, pas d'interception TLS, pas d'enregistrement du contenu des données, pas de SDK de mesure d'audience, et aucun envoi hors de l'appareil des métadonnées surveillées.

## Ce qui fonctionne

- Kotlin, Jetpack Compose et Room, organisés en `ui/`, `monitoring/`, `forwarding/`, `classification/`, `data/` et `privacy/`.
- Interface en français avec trois écrans : Surveillance (démarrage/arrêt), Résultats (dernière session terminée), Réglages.
- `VpnService` de premier plan avec autorisation VPN, notification permanente, et démarrage/arrêt depuis l'écran ou la notification.
- `ForwardingEngine` reposant sur la même version figée de Firestack que le prototype : `com.celzero:firestack:c4a33649be@aar` (commit `c4a33649be94e6a4709dc3b098cd57453aacf34b`, MPL-2.0). Le reste de l'application n'appelle pas Firestack. `Intra.connect` est utilisé avec une MTU de lien positive, pour que cette version n'emprunte pas le chemin `connect2`/`connect3` qui crée un tunnel supplémentaire.
- Possession du TUN, `protect` puis rattachement au réseau actif autre que le VPN (données mobiles ou Wi-Fi), attribution par UID dans `preflow` via `ConnectivityManager.getConnectionOwnerUid`, réponses DNS lorsque le DNS en clair est visible, et compteurs rx/tx de Firestack à la fermeture des flux.
- `FlowTracker` en mémoire, puis une `MonitoringSession` et des lignes regroupées application → destination dans Room à l'arrêt de la session (et environ toutes les 20 secondes pendant son déroulement). Une session restée `RUNNING` après l'arrêt brutal du processus est marquée `FAILED` au lancement suivant.
- Les résultats listent les applications et les destinations de la dernière session terminée, avec une catégorie lorsque la petite liste locale provisoire trouve une correspondance. Sinon, l'étiquette reste **Inconnu**.
- `compileSdk` / `minSdk` / `targetSdk` = 36 avec AGP 8.13.2 (le prototype avait dû rester en compileSdk 35, car AGP 8.7 ne savait pas lire l'API 36). L'APK de mise au point ne contient que `arm64-v8a`. `./gradlew :app:assembleDebug` et `:app:testDebugUnitTest` réussissent.

## Ce qui est provisoire

- Filtre par application : la commande est à l'écran mais désactivée. La surveillance porte sur toutes les applications du profil actuel.
- Historique complet sur plusieurs semaines et conservation automatique de 30 jours / 500 Mo. Les réglages permettent d'effacer toutes les sessions enregistrées ; les limites sont affichées mais pas appliquées.
- Tracker Radar et Disconnect. `DestinationClassifier` est réel ; `StubDestinationClassifier` ne reconnaît qu'une petite liste intégrée de suffixes (`LISTE_LOCALE_MINIMALE`). Aucune classification par le réseau.
- `PolicyEngine` autorise toujours. Les ports autres que 53 sur les adresses du DNS virtuel ne sont pas acheminés, car cette version ne termine pas le DNS sur TLS (même règle d'intégration Firestack que dans le prototype, pas un pare-feu applicatif). Le vrai trafic distant passe toujours par la sortie directe.
- Pas de blocage (V2) ni d'analyse du contenu (V3). Le TUN n'est pas analysé une seconde fois ; Firestack est le seul lecteur.

Les compteurs d'octets sont les rx/tx de la couche d'acheminement de Firestack, pas les longueurs IP brutes du TUN.

## Comment lancer

1. Installer la plateforme 36 du SDK Android et les build-tools 36.
2. Ajouter `sdk.dir` dans `local.properties` à la racine du dépôt.
3. Ouvrir la racine du dépôt dans Android Studio (pas `spike/`).
4. `./gradlew :app:assembleDebug` puis `adb install -r app/build/outputs/apk/debug/app-debug.apk` sur le Galaxy A57 (ARM64, Android 16).
5. Autoriser les notifications et le VPN, démarrer la surveillance, utiliser d'autres applications, arrêter, puis ouvrir Résultats.

Base de données : `/data/data/com.trafficmonitor.privacy/databases/traffic_monitor.db` (également affichée dans Réglages). Journaux : `adb logcat -s TrafficMonitor`. Pas de PCAP.

Cet environnement a compilé l'APK de mise au point et lancé les tests unitaires. Il ne l'a pas installé sur un appareil : le chemin d'acheminement sur le téléphone est donc celui que le prototype a déjà testé avec cette version figée de Firestack.
