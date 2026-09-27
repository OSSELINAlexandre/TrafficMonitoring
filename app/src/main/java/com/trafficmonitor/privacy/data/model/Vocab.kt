package com.trafficmonitor.privacy.data.model

/**
 * Visible destination categories. Insufficient evidence stays [UNKNOWN].
 * TLS metadata is intentionally absent: V1 does not inspect ClientHello or payloads.
 */
enum class Category {
    FUNCTIONAL,
    CDN,
    ANALYTICS,
    ADVERTISING,
    TRACKING,
    CRASH_REPORTING,
    UNKNOWN,
}

enum class PrivacyClassification {
    KNOWN_TRACKER,
    POTENTIAL_TRACKER,
    UNCLASSIFIED,
}

enum class Confidence {
    HIGH,
    MEDIUM,
    UNKNOWN,
}

enum class DomainSource {
    DNS_OBSERVED,
    UNKNOWN,
}

/**
 * Result of `ConnectivityManager.getConnectionOwnerUid` plus package visibility.
 * A missing or shared identity is stored as-is; it is not guessed.
 */
enum class AttributionStatus {
    RESOLVED,
    SHARED_UID,
    SELF,
    SYSTEM,
    UNKNOWN,
    UNSUPPORTED_PROTOCOL,
}

object SessionStatus {
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

object MonitoringMode {
    const val ALL_APPS = "ALL_APPS"
    const val SELECTED_APPS = "SELECTED_APPS"
}

/** Firestack `FlowSummary` rx/tx. These are forwarding-layer counters, not raw TUN IP lengths. */
object CountingMethod {
    const val FIRESTACK_FLOW_RX_TX = "FIRESTACK_FLOW_RX_TX"
}
