package com.trafficmonitor.privacy.forwarding.implementation

/**
 * Same Maven coordinate the Android 16 spike validated on the Galaxy A57.
 * Full commit `c4a33649be94e6a4709dc3b098cd57453aacf34b` (2026-09-20), MPL-2.0.
 *
 * `Intra.connect` is called with a positive link MTU. At this commit, `connect2` /
 * `connect3` pass a negative MTU and can build an extra tunnel.
 */
object FirestackPin {
    const val COORDINATE = "com.celzero:firestack:c4a33649be@aar"
    const val COMMIT = "c4a33649be94e6a4709dc3b098cd57453aacf34b"
    const val LICENSE = "MPL-2.0"
}
