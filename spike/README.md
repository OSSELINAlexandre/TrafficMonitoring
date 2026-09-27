# Firestack Android 16 spike

Disposable test application for `CODEX_TASK.md`. It is not production architecture.

## Build

Set `sdk.dir` in `local.properties`, then run:

```bash
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The local workstation used for the first build did not have the API 36 platform. The
spike therefore compiles with API 35 while declaring `minSdk = 36` and `targetSdk = 36`.
Replace the toolchain with an API-36-aware AGP/D8 before treating build compatibility as
production evidence.

## Device run

Install, open the app, press **Démarrer le VPN**, and accept Android's VPN consent dialog.
Generate the traffic described in `FIRESTACK_SPIKE_REPORT.md`, then retrieve the JSONL log:

```bash
adb shell run-as com.trafficmonitor.firestackspike \
  cat files/firestack-spike/events.jsonl > firestack-events.jsonl
```

No PCAP or packet payload is recorded. The log contains network metadata including IP
addresses, ports, DNS names when Firestack sees them, UIDs, package names, and counters.
