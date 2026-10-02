package com.trafficmonitor.privacy.data.model

data class DestinationDraft(
    val uid: Int,
    val attributionStatus: AttributionStatus,
    val packages: List<String>,
    val label: String,
    val remoteIp: String,
    val domain: String?,
    val domainSource: DomainSource,
    val protocol: String,
    val remotePort: Int,
    val connectionCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val category: Category,
    val privacyClassification: PrivacyClassification,
    val confidence: Confidence,
    val owner: String?,
    val classificationSources: List<String>,
)

data class ApplicationSummaryDraft(
    val uid: Int,
    val attributionStatus: AttributionStatus,
    val packages: List<String>,
    val label: String,
    val destinationCount: Int,
    val knownTrackerCount: Int,
    val analyticsCount: Int,
    val advertisingCount: Int,
    val trackingCount: Int,
    val unknownCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
)

data class SessionProjection(
    val destinations: List<DestinationDraft>,
    val applications: List<ApplicationSummaryDraft>,
)
