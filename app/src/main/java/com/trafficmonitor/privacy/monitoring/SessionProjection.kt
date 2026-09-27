package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.classification.DestinationClassifier
import com.trafficmonitor.privacy.data.model.ApplicationSummaryDraft
import com.trafficmonitor.privacy.data.model.Category
import com.trafficmonitor.privacy.data.model.DestinationDraft
import com.trafficmonitor.privacy.data.model.PrivacyClassification
import com.trafficmonitor.privacy.data.model.SessionProjection

fun projectSession(
    rows: List<AggregateSnapshot>,
    classifier: DestinationClassifier,
): SessionProjection {
    val destinations = rows.map { row ->
        val classification = classifier.classify(row.domain, row.remoteIp)
        DestinationDraft(
            uid = row.uid,
            attributionStatus = row.attribution.status,
            packages = row.attribution.packages,
            label = row.attribution.label,
            remoteIp = row.remoteIp,
            domain = row.domain,
            domainSource = row.domainSource,
            protocol = row.protocol,
            remotePort = row.remotePort,
            connectionCount = row.connectionCount,
            bytesSent = row.bytesSent,
            bytesReceived = row.bytesReceived,
            category = classification.category,
            privacyClassification = classification.privacy,
            confidence = classification.confidence,
            owner = classification.owner,
            classificationSources = classification.sources,
        )
    }.sortedWith(
        compareByDescending<DestinationDraft> { it.bytesReceived }
            .thenByDescending { it.bytesSent }
            .thenBy { it.label },
    )

    val applications = destinations
        .groupBy { it.uid to it.packages }
        .map { (_, appRows) ->
            val first = appRows.first()
            val byDestination = appRows.groupBy { it.domain ?: it.remoteIp }
            ApplicationSummaryDraft(
                uid = first.uid,
                attributionStatus = first.attributionStatus,
                packages = first.packages,
                label = first.label,
                destinationCount = byDestination.size,
                knownTrackerCount = byDestination.values.count { dest ->
                    dest.any { it.privacyClassification == PrivacyClassification.KNOWN_TRACKER }
                },
                analyticsCount = byDestination.values.count { dest ->
                    dest.any { it.category == Category.ANALYTICS }
                },
                advertisingCount = byDestination.values.count { dest ->
                    dest.any { it.category == Category.ADVERTISING }
                },
                trackingCount = byDestination.values.count { dest ->
                    dest.any { it.category == Category.TRACKING }
                },
                unknownCount = byDestination.values.count { dest ->
                    dest.any { it.category == Category.UNKNOWN }
                },
                bytesSent = appRows.sumOf { it.bytesSent },
                bytesReceived = appRows.sumOf { it.bytesReceived },
            )
        }
        .sortedWith(
            compareByDescending<ApplicationSummaryDraft> { it.bytesReceived }
                .thenByDescending { it.bytesSent }
                .thenBy { it.label },
        )

    return SessionProjection(destinations, applications)
}
