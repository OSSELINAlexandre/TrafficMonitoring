# Traffic Monitoring

Personal Android network privacy monitor. V1 records which installed apps talked to which remote servers during a monitoring session. It does not decrypt TLS, inspect payloads, block apps, or upload metadata.

The disposable Firestack feasibility spike stays in `spike/` and is unchanged. Production code is the `:app` module at the repository root. The forwarding choice and its limits are in `FIRESTACK_SPIKE_REPORT.md`: Firestack is **suitable with modifications**, pinned to the same build the spike ran on a Galaxy A57.

## What this version does

- Jetpack Compose navigation: Surveillance, Résultats, Réglages.
- Foreground `VpnService` (`systemExempted`), VPN consent, persistent notification, start/stop.
- `ForwardingEngine` implemented by Firestack (`com.celzero:firestack:c4a33649be@aar`, commit `c4a33649be94e6a4709dc3b098cd57453aacf34b`, MPL-2.0). The rest of the app does not call Firestack directly.
- The engine owns the TUN. Socket `protect` + bind onto a non-VPN underlay. Validated Wi-Fi is preferred when it is available; otherwise the session uses mobile data. `preflow` calls `ConnectivityManager.getConnectionOwnerUid`. Closed-flow rx/tx and plaintext DNS answers feed an in-memory `FlowTracker`. The same attribution, destination, and classifier path runs on either underlay.
- On stop (and about every 20 seconds), a `MonitoringSession` plus aggregated per-app destination rows are written to Room. The session stores the underlay type (`WIFI` or `CELLULAR`) recorded at start, and updates that value if the preferred underlay changes. Bytes are Firestack forwarding counters, not raw TUN IP lengths.
- Results show the latest finished session: applications, destinations, and a category when the local stub list matches. Otherwise the category stays **Inconnu**. The session line is « Session en Wi‑Fi » or « Session en 5G / mobile ».

## Wi-Fi

Wi-Fi is supported as a bonus. The primary target remains mobile data / 5G. When a validated Wi-Fi network is available, outbound sockets bind to it, `VpnService.setUnderlyingNetworks` points at it, and Firestack is given that network's DNS servers. Otherwise the session uses mobile. This version does not claim that switching between Wi-Fi and mobile in the middle of a route is reliable, and it does not recreate the VPN when the radio changes.

## What is stubbed

- Per-app VPN filter (the control is visible and disabled; monitoring is all apps in the current profile).
- Full history, per-session delete, and automatic 30-day / 500 MB retention. Settings can erase every saved session. Defaults are shown but not enforced. A session left `RUNNING` after a process death is marked `FAILED` on the next launch.
- Tracker Radar and Disconnect datasets. `DestinationClassifier` exists; `StubDestinationClassifier` matches a few well-known suffixes from a built-in list (`LISTE_LOCALE_MINIMALE`). There is no network lookup.
- `PolicyEngine` always returns allow. Virtual-DNS ports other than 53 are not forwarded (`Backend.Block`) because this build does not terminate DNS-over-TLS. That is the Firestack DNS contract from the spike, not an app firewall. Real remote traffic, including real port 853, still uses `Backend.Exit`.
- No packet capture, payload store, TLS metadata, or content analysis.

## Toolchain

| | |
|---|---|
| AGP | 8.13.2 (understands API 36; the spike's AGP 8.7 did not) |
| Gradle | 8.13 |
| Kotlin | 2.1.21 |
| compileSdk / minSdk / targetSdk | 36 |
| JDK | 17 or newer |
| ABI | `arm64-v8a` only |
| Firestack | `com.celzero:firestack:c4a33649be@aar` |

`Intra.connect` is called with a positive link MTU. At this pin, `connect2` / `connect3` can build an extra tunnel.

## Open, build, install

1. Install Android SDK platform 36 and build-tools 36 (Android Studio SDK Manager, or `sdkmanager "platforms;android-36" "build-tools;36.0.0"`).
2. Create `local.properties` in the repository root:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```

3. Open the repository root in Android Studio (not `spike/`).
4. Build and install a debug APK on the Galaxy A57 (ARM64, Android 16):

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Open **Moniteur réseau**, allow notifications, tap **Démarrer la surveillance**, and accept the VPN prompt. There is no remote VPN server.
6. Use other apps, then tap **Arrêter** (screen or notification).
7. Open **Résultats**. The latest session lists apps and destinations. Names appear when plaintext DNS was visible; otherwise the row is the IP. Categories are mostly **Inconnu** until a real local list is added.

Unit tests (no device):

```bash
./gradlew :app:testDebugUnitTest
```

## Logs and database

Nothing is uploaded. The Room file and its WAL live in app-private storage:

```text
/data/data/com.trafficmonitor.privacy/databases/traffic_monitor.db
```

The same path is shown under Réglages. Pull or inspect it with `adb shell run-as com.trafficmonitor.privacy`.

Lifecycle and closed-flow lines go to logcat under the tag `TrafficMonitor` (flow lines are debug):

```bash
adb logcat -s TrafficMonitor
```

There is no PCAP and no payload log. The spike's `files/firestack-spike/events.jsonl` is a different app (`com.trafficmonitor.firestackspike`).

## Layout

Package `com.trafficmonitor.privacy`, aligned with SPEC §33:

```text
ui/            Compose screens
monitoring/    VpnService, FlowTracker, attribution, DNS evidence
forwarding/    ForwardingEngine + Firestack adapter
classification/ DestinationClassifier + local stub
data/          Room and repository
privacy/       Retention hook (manual erase; automatic limits later)
```

The TUN is not parsed a second time. `PacketParser` only interprets endpoints the forwarding engine already reports.
