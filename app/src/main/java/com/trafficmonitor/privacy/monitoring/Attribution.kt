package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.data.model.AttributionStatus

data class Attribution(
    val uid: Int,
    val status: AttributionStatus,
    val packages: List<String>,
    val label: String,
) {
    fun packageCsv(): String = packages.joinToString(",")

    companion object {
        fun unknown(uid: Int = UNKNOWN_UID): Attribution = Attribution(
            uid = uid,
            status = if (uid < 0) AttributionStatus.UNKNOWN else AttributionStatus.SYSTEM,
            packages = emptyList(),
            label = if (uid < 0) LABEL_UNKNOWN else LABEL_SYSTEM,
        )

        const val UNKNOWN_UID = -1
        const val LABEL_UNKNOWN = "Application non déterminée"
        const val LABEL_SYSTEM = "Système"
    }
}

object AttributionRules {
    fun status(uid: Int, packages: List<String>, selfUid: Int): AttributionStatus = when {
        uid < 0 -> AttributionStatus.UNKNOWN
        uid == selfUid -> AttributionStatus.SELF
        packages.size > 1 -> AttributionStatus.SHARED_UID
        packages.size == 1 -> AttributionStatus.RESOLVED
        uid in 0 until FIRST_APP_UID -> AttributionStatus.SYSTEM
        else -> AttributionStatus.UNKNOWN
    }

    private const val FIRST_APP_UID = 10_000
}
