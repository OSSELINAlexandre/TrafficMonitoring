# Task: Android 16 Firestack Technical Spike

Read `SPEC.md` and `REVUE_ANDROID_16.md`.

This task is NOT the implementation of the production application.

Create a disposable technical spike whose only purpose is to determine whether Firestack is suitable as the forwarding engine for the Network Privacy Monitor.

Target device:

- Samsung Galaxy A57 5G
- Android 16 / API 36
- ARM64

## Primary objective

Determine whether Firestack can provide a reliable local `VpnService` forwarding layer while exposing enough information to implement our monitoring requirements.

Do not build the real UI, Room database, tracker classification system, history system, or production architecture.

## Required investigation

Determine whether Firestack can support:

- ownership of the Android TUN file descriptor;
- direct local Internet forwarding without a remote VPN server;
- protected outbound sockets;
- TCP;
- UDP;
- QUIC traffic;
- IPv4;
- IPv6;
- ICMP/ICMPv6 requirements;
- Wi-Fi;
- mobile data;
- switching between Wi-Fi and mobile data;
- DNS traffic observation when DNS is visible;
- bidirectional traffic counters;
- observation of original connection tuples before forwarding/proxy transformation;
- integration with `ConnectivityManager.getConnectionOwnerUid()`;
- clean VPN start;
- clean VPN stop;
- VPN revocation;
- ARM64;
- Android 16 16-KiB memory pages.

## UID attribution experiment

For every newly observed TCP/UDP flow, attempt application attribution as early as possible using the original flow tuple.

Record:

- protocol;
- source IP;
- source port;
- destination IP;
- destination port;
- returned UID;
- attribution status;
- package(s) associated with the UID.

The experiment must explicitly record cases where Android returns `INVALID_UID`.

Do not assume attribution is always possible.

## Traffic counter experiment

Determine at what layer Firestack exposes traffic counters.

Compare, where possible:

- original TUN IP packet sizes;
- forwarding-engine counters;
- socket-level counters.

Document which metric would be practical for V1.

Do not silently substitute payload bytes for IP-level bytes.

## DNS experiment

Observe ordinary DNS traffic where possible.

Test at least:

1. normal system DNS;
2. Android Private DNS if enabled/available;
3. an application using encrypted DNS if readily testable.

Do not disable encrypted DNS to make the experiment succeed.

Record when domains are observable and when only destination IP addresses are available.

Do not implement TLS ClientHello/SNI inspection.

## IPv6 requirements

Verify real IPv6 forwarding.

The VPN must not silently bypass IPv6.

Test:

- IPv6 connectivity;
- TCP over IPv6;
- UDP over IPv6;
- ICMPv6 behavior where relevant;
- PMTU behavior where practical.

If full IPv6 forwarding cannot be demonstrated, record the spike as failing the IPv6 requirement.

## Network transition test

While monitoring:

1. start on Wi-Fi;
2. generate traffic;
3. disable Wi-Fi;
4. continue on mobile data;
5. generate traffic;
6. restore Wi-Fi.

Record:

- whether existing flows survive;
- whether new flows work;
- whether the VPN must be recreated;
- whether attribution remains functional.

## Performance observations

This is not a formal benchmark.

Record at least:

- obvious CPU issues;
- obvious battery/thermal issues;
- memory consumption;
- crashes;
- network stalls;
- significant throughput degradation.

## 16-KiB / native library validation

Inspect all native libraries introduced by Firestack.

Verify compatibility/alignment expectations for Android 16 and 16-KiB page-size devices.

Record the ABI and native artifacts used.

## Deliverable

Produce:

`FIRESTACK_SPIKE_REPORT.md`

Do not merely state PASS or FAIL.

Include a matrix:

| Requirement | Result | Evidence | Limitation |
|---|---|---|---|
| TCP IPv4 | PASS/FAIL | ... | ... |
| UDP IPv4 | PASS/FAIL | ... | ... |
| TCP IPv6 | PASS/FAIL | ... | ... |
| UDP IPv6 | PASS/FAIL | ... | ... |
| QUIC | PASS/FAIL | ... | ... |
| UID attribution | ... | ... | ... |
| DNS observation | ... | ... | ... |
| Wi-Fi → 5G | ... | ... | ... |
| 5G → Wi-Fi | ... | ... | ... |
| Bidirectional counters | ... | ... | ... |
| 16-KiB compatibility | ... | ... | ... |

Also document:

- exact Firestack version/commit tested;
- exact dependencies;
- required permissions;
- native components;
- integration complexity;
- APIs/hooks available for monitoring;
- missing APIs/hooks;
- any modifications that would be required to Firestack.

## Final assessment

End the report with one of:

- `SUITABLE`
- `SUITABLE WITH MODIFICATIONS`
- `UNSUITABLE`

Explain the technical reasons.

Do NOT start implementing the production application after the report.

Wait for human review.
