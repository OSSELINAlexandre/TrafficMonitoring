package com.trafficmonitor.privacy.classification

import com.trafficmonitor.privacy.data.model.Category
import com.trafficmonitor.privacy.data.model.Confidence
import com.trafficmonitor.privacy.data.model.PrivacyClassification

/**
 * Tiny on-device seed so the classifier seam is real before Tracker Radar and Disconnect
 * are bundled. A match is one local rule, so confidence stays [Confidence.MEDIUM].
 * Unknown names and bare IPs stay [Classification.UNKNOWN]. Nothing here is fetched.
 */
class StubDestinationClassifier : DestinationClassifier {
    private val rules = listOf(
        Rule("google-analytics.com", Category.ANALYTICS, PrivacyClassification.POTENTIAL_TRACKER, "Google"),
        Rule("analytics.google.com", Category.ANALYTICS, PrivacyClassification.POTENTIAL_TRACKER, "Google"),
        Rule("googletagmanager.com", Category.ANALYTICS, PrivacyClassification.POTENTIAL_TRACKER, "Google"),
        Rule("app-measurement.com", Category.ANALYTICS, PrivacyClassification.POTENTIAL_TRACKER, "Google"),
        Rule("doubleclick.net", Category.ADVERTISING, PrivacyClassification.KNOWN_TRACKER, "Google"),
        Rule("googlesyndication.com", Category.ADVERTISING, PrivacyClassification.KNOWN_TRACKER, "Google"),
        Rule("googleadservices.com", Category.ADVERTISING, PrivacyClassification.KNOWN_TRACKER, "Google"),
        Rule("adservice.google.com", Category.ADVERTISING, PrivacyClassification.KNOWN_TRACKER, "Google"),
        Rule("amazon-adsystem.com", Category.ADVERTISING, PrivacyClassification.KNOWN_TRACKER, "Amazon"),
        Rule("scorecardresearch.com", Category.TRACKING, PrivacyClassification.KNOWN_TRACKER, "Comscore"),
        Rule("crashlytics.com", Category.CRASH_REPORTING, PrivacyClassification.POTENTIAL_TRACKER, "Google"),
        Rule("bugsnag.com", Category.CRASH_REPORTING, PrivacyClassification.POTENTIAL_TRACKER, "SmartBear"),
        Rule("sentry.io", Category.CRASH_REPORTING, PrivacyClassification.POTENTIAL_TRACKER, "Functional Software"),
    ).sortedByDescending { it.suffix.length }

    override fun classify(domain: String?, ip: String): Classification {
        val host = domain?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isEmpty()) return Classification.UNKNOWN
        val rule = rules.firstOrNull { host == it.suffix || host.endsWith(".${it.suffix}") }
            ?: return Classification.UNKNOWN
        return Classification(
            category = rule.category,
            privacy = rule.privacy,
            confidence = Confidence.MEDIUM,
            owner = rule.owner,
            sources = listOf(SOURCE),
        )
    }

    private data class Rule(
        val suffix: String,
        val category: Category,
        val privacy: PrivacyClassification,
        val owner: String,
    )

    companion object {
        const val SOURCE = "LISTE_LOCALE_MINIMALE"
    }
}
