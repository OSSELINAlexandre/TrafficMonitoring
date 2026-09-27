package com.trafficmonitor.privacy.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val locale: Locale = Locale.FRANCE
private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withLocale(locale)

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    val kilo = 1024.0
    val mega = kilo * 1024
    val giga = mega * 1024
    return when {
        value < 1024 -> "$value o"
        value < mega -> String.format(locale, "%.1f Ko", value / kilo)
        value < giga -> String.format(locale, "%.1f Mo", value / mega)
        else -> String.format(locale, "%.1f Go", value / giga)
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remain = minutes % 60
        "$hours h $remain min"
    } else if (minutes > 0) {
        "$minutes min"
    } else {
        "$seconds s"
    }
}

fun formatWhen(epochMs: Long): String =
    dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun categoryLabel(category: String): String = when (category) {
    "FUNCTIONAL" -> "Fonctionnel"
    "CDN" -> "CDN"
    "ANALYTICS" -> "Analytique"
    "ADVERTISING" -> "Publicité"
    "TRACKING" -> "Suivi"
    "CRASH_REPORTING" -> "Rapports de plantage"
    else -> "Inconnu"
}

fun privacyLabel(value: String): String = when (value) {
    "KNOWN_TRACKER" -> "Traqueur connu"
    "POTENTIAL_TRACKER" -> "Traqueur potentiel"
    else -> "Non classé"
}

fun attributionLabel(status: String): String = when (status) {
    "RESOLVED" -> "Application identifiée"
    "SHARED_UID" -> "UID partagé"
    "SELF" -> "Cette application"
    "SYSTEM" -> "Système"
    "UNSUPPORTED_PROTOCOL" -> "Protocole non attribué"
    else -> "Application non déterminée"
}
