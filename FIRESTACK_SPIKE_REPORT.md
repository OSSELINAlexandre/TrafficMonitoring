# Firestack spike report — Android 16

Date: 2026-09-26  
Target: Samsung Galaxy A57 5G (`SM-A576B`), Android 16 / API 36, ARM64  
Scope: disposable feasibility spike for point 41; not the production application

## Executive result

Firestack exposes the essential integration points for a local userspace VPN monitor:
the Android TUN file descriptor, direct egress, socket protection/binding, original flow
tuples, early UID injection, DNS callbacks, closed-flow counters, and aggregate stack
statistics. The exact pinned AAR compiles into an API-36-targeted ARM64 APK and its native
ELF and APK packaging satisfy the static 16-KiB alignment checks.

The APK was subsequently installed and exercised on the target over Wi-Fi. The validated
run lasted from 13:14:35 to 13:17:13 UTC and demonstrated direct TCP/UDP forwarding over
IPv4 and IPv6, UDP/443 traffic consistent with QUIC, plaintext DNS observation, Android UID
attribution, bidirectional counters, protected/bound sockets, and clean start/stop. ICMP,
VPN revocation, mobile data, Wi-Fi/5G transitions and a 16-KiB runtime remain untested.

## Artifact and version under test

- Maven coordinate: `com.celzero:firestack:c4a33649be@aar`
- Full Git commit: `c4a33649be94e6a4709dc3b098cd57453aacf34b`
- Commit date/message: 2026-09-20, `rpn: rebuild auto exclusions on each fork to simplify`
- AAR SHA-256: `8fe63dac2028831b4fc57bd15c304a948c7e2eb1c6f11969f568d1deefeb0731`
- License declared by source and Maven POM: MPL-2.0
- Spike build: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, Java 17 bytecode
- Android configuration: `compileSdk 35`, `minSdk 36`, `targetSdk 36`
- APK: `spike/app/build/outputs/apk/debug/app-debug.apk`, about 22 MiB
- Build result: `./gradlew assembleDebug` — **PASS**

The API mismatch above is intentional but is not production-ready. Only API 35 and a
future platform directory named `android-37.0` were installed locally; AGP 8.7 cannot
consume the latter. D8 warned that it only understands API levels through 35. The manifest
nevertheless contains both `minSdkVersion=36` and `targetSdkVersion=36`. A production proof
must rebuild with an AGP/D8 and stable platform that officially understand API 36.

## Device facts actually obtained

ADB returned:

| Property | Value |
|---|---|
| Model | `SM-A576B` |
| Android release | `16` |
| API | `36` |
| Primary ABI | `arm64-v8a` |
| Runtime page size | `4096` bytes |

This specific handset is therefore not a 16-KiB runtime test device.

## Spike implementation

The implementation is under `spike/`. It deliberately contains only:

- a minimal activity for VPN consent, start, stop, and log deletion;
- a foreground `VpnService` with full IPv4 and IPv6 default routes;
- one Firestack `Bridge` that sends Internet flows through `Backend.Exit`, fake-DNS port 53
  through `Backend.Base`, and blocks unsupported DoT to the virtual port 853;
- `VpnService.protect(fd)` plus `Network.bindSocket(fd)` on non-VPN underlays;
- early `ConnectivityManager.getConnectionOwnerUid()` lookup from `preflow` tuples;
- explicit UDP retries using destination port zero, then an unspecified destination;
- JSONL logging for original tuples, attribution status, package names, DNS, flow counters,
  underlay changes, socket protection/binding, lifecycle, and aggregate stack statistics;
- a Wi-Fi-first, validated-network selection policy that falls back to cellular and updates
  the tunnel link MTU without silently removing the IPv6 route;
- injection of the physical network's DNS servers into Firestack on startup/underlay change;
- no PCAP, TLS/SNI inspection, Room, tracker classification, history UI, or production layers.

Event file on device:
`files/firestack-spike/events.jsonl`. Unknown attribution is recorded as UID `-1` with an
explicit status (`UNKNOWN`, `UNSUPPORTED_PROTOCOL`, `INVALID_SOURCE`, or
`INVALID_DESTINATION`). Shared UIDs retain every visible package name.

## Requirement matrix

`SOURCE PASS` means the pinned API/source and compiled integration support the path, but it
was not exercised on the device. `NOT RUN` is not equivalent to PASS. Runtime figures below
refer to the final corrected run only.

| Requirement | Result | Evidence | Limitation |
|---|---|---|---|
| Android TUN ownership | PASS | `Intra.connect(fd, ...)` started on API 36; Firestack build string reported Go 1.27.1/ARM64; 504 events captured | Leak analysis was limited to one clean stop |
| Direct local forwarding | PASS | No remote VPN configured; external IPv4/IPv6 destinations exchanged traffic through `Backend.Exit` | Short functional run, not a soak test |
| Protected outbound sockets | PASS | Every one of the final run's recorded socket binds succeeded; protected and bound to Wi-Fi handle | Mobile binding not tested |
| TCP IPv4 | PASS | Five external IPv4 TCP flows; one closed successfully with `rx=5634`, `tx=723`; aggregate TCP counters advanced | One endpoint refused a connection; not a Firestack routing failure |
| UDP IPv4 | PASS | Nine external IPv4 UDP/443 flows plus successful physical-DNS traffic | No long-running UDP benchmark |
| TCP IPv6 | PASS | Eight external IPv6 TCP flows; a closed IPv6 TLS flow transferred `rx=2338`, `tx=2237` before peer reset | Peer reset occurred after data transfer |
| UDP IPv6 | PASS | External UDP flow to `[2a03:2880:...]:443` through `Backend.Exit` | Single observed external flow |
| QUIC / HTTP/3 | PASS WITH LIMITATION | Ten UDP/443 flows, including Chrome UID 10271 to `www.google.com`; IPv4 and IPv6 UDP/443 forwarded | HTTP/3 ALPN/qlog was not captured, so identification is transport/domain based |
| ICMP / ICMPv6 | SOURCE PARTIAL | Firestack declares ICMP flow type and exports ICMPv4/v6 statistics | Android owner lookup is only attempted for TCP/UDP; echo and PMTU behavior not run |
| DNS observation | PASS WITH LIMITATION | 59 queries and 59 successful responses; A/AAAA names and answers observed through physical DNS `[fd0f:ee:b0::1]:53` | Virtual DoT/853 attempts were blocked; app DoH plaintext remains intentionally invisible |
| Original tuples | PASS | 88 runtime flows logged protocol and original source/destination tuple before routing | Corpus was short |
| UID attribution | PASS WITH LIMITATION | 84 flows resolved to visible packages/UIDs; four system UID flows had no visible package; no `INVALID_UID` occurred | INVALID_UID handling is implemented but was not exercised in this run |
| Bidirectional counters | PARTIAL | Closed flows exposed non-zero Rx/Tx; final aggregate was 198.07 KiB Rx and 1.90 MiB Tx, 926/1881 packets | No controlled comparison against original packet sizes; no per-flow TUN-byte hook |
| Wi-Fi | PASS | Validated Wi-Fi selected; IPv4/IPv6 DNS and Internet traffic forwarded | Only one Wi-Fi network tested |
| Mobile data | NOT RUN | Cellular underlay discovery/binding implemented | No packets forwarded |
| Wi-Fi → 5G | NOT RUN | Lost-underlay fallback and link-MTU update implemented | Existing-flow behavior unknown |
| 5G → Wi-Fi | NOT RUN | Validated Wi-Fi receives higher selection score than cellular | Existing-flow behavior unknown |
| Clean start | PASS | Consent accepted, foreground VPN created, Firestack callbacks and notification active; no crash | Reboot/always-on not tested |
| Clean stop | PASS | User stop produced disconnect summaries and removed the service; no start/disconnect failure event | One-run verification, no repeated-cycle leak profiling |
| VPN revocation | SOURCE PASS / RUNTIME NOT RUN | `onRevoke()` logs then uses the same stop path | System revocation not triggered |
| ARM64 | PASS | APK contains only `lib/arm64-v8a/libgojni.so`; native Go/JNI loaded and forwarded traffic on `SM-A576B` | None for the target ABI |
| 16-KiB compatibility | STATIC PASS / RUNTIME NOT RUN | ARM64 ELF LOAD alignment is `0x4000`; `zipalign -c -P 16 -v 4` passes | Target handset reports 4-KiB pages, so no 16-KiB process launch test |
| Performance | PARTIAL | No crash/ANR/stall observed; measured process `TOTAL PSS` 68,752 KiB and `TOTAL RSS` 188,060 KiB | CPU, battery, thermal and throughput were not formally measured; RSS is high |

## Monitoring hooks available

### Flow lifecycle

- `preflow(protocol, uid, src, dst)` is documented as occurring before a new connection is
  established. It is the correct place to call Android ownership attribution on the
  unmodified tuple.
- `flow(...)` supplies protocol, UID, source, destination, original destination IPs,
  associated/probable domains, blocklists and an ALG flag. Returning `Backend.Exit`
  selects direct egress.
- `flowing(mark)` confirms the final applied mark.
- `postflow(FlowSummary)` returns connection ID, protocol, UID, source, target, selected
  proxy, Rx, Tx, duration, RTT and message after close.

The pinned source explicitly documents protocol 6/TCP, 17/UDP, 1/ICMP and UID `-1` when
ownership is unknown.

### DNS

`onQuery` and `onResponse` exposed ordinary DNS entering Firestack's fake DNS path. In the
validated run, all 59 observed responses had `rcode=0`, including A and AAAA responses for
`example.com` and Google/Facebook endpoints. The physical Wi-Fi DNS servers were injected
with `Intra.setSystemDNS()`; omitting this call made Firestack fall back to unusable
`localhost:53` on Android.

This cannot make encrypted DNS plaintext visible:

- system plaintext DNS was observable;
- Android's opportunistic Private DNS attempted TCP/853 against the VPN's virtual DNS IP.
  The spike blocked those four virtual DoT attempts because it does not terminate TLS, after
  which Android used the working port-53 path. Strict Private DNS still needs a dedicated test;
- application DoH/DoQ is expected to expose only IP/transport metadata;
- no TLS ClientHello or SNI inspection was added.

Those are intended visibility boundaries, not attribution failures.

### Network and sockets

The `Controller` side supplies `protect`, `bind4`, and `bind6`. The spike protects every
outbound FD and binds it to the selected physical `Network`. This is necessary both to
avoid a VPN routing loop and to steer new sockets during Wi-Fi/mobile transitions.

## Traffic counter finding

Three layers are exposed, but only two are directly consumable without modifying Firestack:

1. **Original TUN IP packet size** — no per-packet/per-flow callback is exposed. Firestack
   can write a PCAP, but the spike intentionally leaves PCAP disabled because it would
   persist packet contents. Aggregate NIC Rx/Tx and packet counts are the closest IP-stack
   view.
2. **Forwarding engine / protocol aggregates** — `Tunnel.stat()` exposes NIC, IP, TCP, UDP
   and ICMP statistics. These are useful for health checks and aggregate reconciliation,
   not app-level accounting.
3. **Closed-flow socket counters** — `FlowSummary.rx/tx` are practical for V1 app and
   destination totals, but must be labelled as Firestack forwarding/socket bytes until a
   controlled transfer proves whether headers are included.

Practical V1 choice: use `FlowSummary.rx/tx` for per-flow aggregation, disclose that values
are estimates from the forwarding layer, and periodically reconcile against NIC aggregates.
If the product needs exact original IP bytes per app, Firestack must be modified to expose
per-flow ingress/egress IP-byte counters before transport processing. Payload counters must
not be presented as IP-level totals.

## Native artifact inspection

The Maven AAR contains one Go/JNI shared library for each ABI:

| ABI | Artifact | Uncompressed size |
|---|---|---:|
| `arm64-v8a` | `libgojni.so` | 19,784,296 bytes |
| `armeabi-v7a` | `libgojni.so` | 19,192,532 bytes |
| `x86_64` | `libgojni.so` | 21,123,080 bytes |
| `x86` | `libgojni.so` | 19,402,404 bytes |

The spike filters packaging to `arm64-v8a`. `readelf -lW` reports all ARM64 LOAD segments
with `Align 0x4000`; the APK places the uncompressed library on a 16-KiB boundary and passes
the Android build-tools 36 `zipalign -P 16` verification. Static packaging is therefore
compatible with 16-KiB requirements, but runtime proof still requires a 16-KiB device or
emulator.

## Permissions and Android components

Declared permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `QUERY_ALL_PACKAGES` (spike-only package attribution visibility)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`
- `POST_NOTIFICATIONS`

The service requires `android.permission.BIND_VPN_SERVICE`, is not exported, declares the
`systemExempted` foreground-service type, and explicitly disables always-on support for the
experiment. Production use of `QUERY_ALL_PACKAGES` needs Play policy review or a narrower
package-visibility strategy.

## Integration risks and required modifications

1. **Pinned API defect:** at the tested commit, `Connect()` calls `NewTunnel()` when
   `linkmtu <= 0` but does not return its result; it then also calls `NewTunnel2()`. Because
   `Connect2/Connect3` pass `-1`, they risk creating/leaking an extra tunnel. The spike avoids
   this by calling `Intra.connect` with a positive physical link MTU. Production should carry
   a tiny upstream patch (`return NewTunnel(...)`) or use a revision where it is fixed, plus
   a regression test.
2. **Counter gap:** no privacy-safe per-flow original-IP-byte callback. Add counters inside
   Firestack or explicitly accept and label socket/forwarding estimates.
3. **ICMP ownership:** Android's owner-UID API is not used for ICMP/ICMPv6. These events must
   remain unattributed or use a separately justified mechanism; they must not be assigned to
   an arbitrary app.
4. **Network transitions:** new sockets can be rebound, but existing sockets on a lost
   network may fail. Only the prescribed live test can decide whether tunnel recreation or
   selective flow closure is needed.
5. **Unstable surface:** Firestack describes its API as evolving and ships a large Go/JNI
   binary. Pin the commit, wrap it behind `ForwardingEngine`, retain a replaceable adapter,
   and regression-test upgrades.
6. **Toolchain:** rebuild with an API-36-aware Android Gradle Plugin/D8 before production.
7. **Licensing:** MPL-2.0 and distribution obligations require the project's normal legal
   review, especially if Firestack source is modified.
8. **DNS integration contract:** fake DNS endpoints must include port 53, fake-DNS flows must
   return `Backend.Base`, virtual port 853 must not be sent to `Exit`, the physical DNS list
   must be supplied with `Intra.setSystemDNS()`, and `onQuery` must select `Backend.System`.
   These requirements are not obvious from the generated Java API and need adapter tests.

## Remaining device protocol

The basic Wi-Fi path is complete. The remaining tests, without changing DNS settings merely
to make results pass, are:

1. Exercise IPv4 ping and IPv6 ping, including PMTU behavior.
2. Confirm HTTP/3 with browser net-export/qlog rather than UDP/443 inference alone.
3. Test strict Android Private DNS as configured/available, then a readily
   available application with encrypted DNS. Correlate flow and DNS events.
4. With USB ADB available, disable Wi-Fi during active and idle flows; test new traffic on
   5G; restore Wi-Fi and
   repeat. Record survival of old flows, new-flow reachability, UID status and MTU/PMTU.
5. Revoke the VPN from Android settings, restart it, then stop from the notification. Check
   for stale VPN state, open FDs and duplicate worker activity over repeated cycles.
6. Capture `dumpsys meminfo`, CPU, thermal state, obvious battery drain and representative
   throughput over a longer run. The short-run PSS/RSS figures are only a baseline.
7. Pull `events.jsonl` and calculate attribution success/`INVALID_UID` rate by protocol;
   compare controlled byte transfers with closed-flow and aggregate NIC counters.
8. Repeat install/start on a true 16-KiB page-size image or device.

## Final assessment

**SUITABLE WITH MODIFICATIONS**

Firestack's architecture and compiled API surface fit the monitor better than a remote VPN
or a packet-only toy forwarder: direct local egress, original flow tuples, early UID
injection, DNS events and useful counters are all present. It is not ready to adopt unchanged
because of the pinned `Connect2/Connect3` defect, the lack of per-flow original-IP-byte
counters, non-obvious DNS integration contract, API/toolchain volatility, high initial RSS,
and the remaining transition/ICMP/16-KiB runtime tests. Real IPv6 forwarding is now
demonstrated on the target; mobile transition reliability is still a release gate.

SUITABLE WITH MODIFICATIONS
