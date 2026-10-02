package com.trafficmonitor.privacy.classification

import com.trafficmonitor.privacy.data.model.Category
import com.trafficmonitor.privacy.data.model.Confidence
import com.trafficmonitor.privacy.data.model.PrivacyClassification

data class Classification(
    val category: Category,
    val privacy: PrivacyClassification,
    val confidence: Confidence,
    val owner: String?,
    val sources: List<String>,
) {
    companion object {
        val UNKNOWN = Classification(
            category = Category.UNKNOWN,
            privacy = PrivacyClassification.UNCLASSIFIED,
            confidence = Confidence.UNKNOWN,
            owner = null,
            sources = emptyList(),
        )
    }
}

/**
 * Local-only destination classification. Implementations must not call a network API.
 * V2 may consult the result from a [com.trafficmonitor.privacy.forwarding.PolicyEngine];
 * V1 only displays it.
 */
interface DestinationClassifier {
    fun classify(domain: String?, ip: String): Classification
}
