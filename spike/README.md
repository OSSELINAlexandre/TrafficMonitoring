# Prototype jetable Firestack pour Android 16

Application d'essai jetable pour `CODEX_TASK.md`. Ce n'est pas l'architecture de production.

## Compilation

Renseignez `sdk.dir` dans `local.properties`, puis lancez :

```bash
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/app-debug.apk`.

Le poste local utilisé pour la première compilation ne disposait pas de la plateforme API 36.
Le prototype compile donc avec l'API 35 tout en déclarant `minSdk = 36` et `targetSdk = 36`.
Remplacez la chaîne de compilation par une version d'AGP/D8 qui connaît l'API 36 avant de
considérer la compatibilité de compilation comme une preuve valable pour la production.

## Essai sur l'appareil

Installez l'application, ouvrez-la, appuyez sur **Démarrer le VPN**, puis acceptez la fenêtre
d'autorisation VPN d'Android. Générez le trafic décrit dans `FIRESTACK_SPIKE_REPORT.md`, puis
récupérez le journal JSONL :

```bash
adb shell run-as com.trafficmonitor.firestackspike \
  cat files/firestack-spike/events.jsonl > firestack-events.jsonl
```

Aucun fichier PCAP ni contenu de paquet n'est enregistré. Le journal contient des métadonnées
réseau, notamment les adresses IP, les ports, les noms DNS lorsque Firestack les voit, les UID,
les noms de paquets et les compteurs.
