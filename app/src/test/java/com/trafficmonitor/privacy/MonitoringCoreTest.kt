package com.trafficmonitor.privacy

import com.trafficmonitor.privacy.classification.StubDestinationClassifier
import com.trafficmonitor.privacy.data.model.AttributionStatus
import com.trafficmonitor.privacy.data.model.Category
import com.trafficmonitor.privacy.data.model.DomainSource
import com.trafficmonitor.privacy.data.model.PrivacyClassification
import com.trafficmonitor.privacy.forwarding.ClosedFlow
import com.trafficmonitor.privacy.forwarding.EndpointParser
import com.trafficmonitor.privacy.forwarding.ObservedDns
import com.trafficmonitor.privacy.forwarding.ObservedFlow
import com.trafficmonitor.privacy.monitoring.AggregateSnapshot
import com.trafficmonitor.privacy.monitoring.Attribution
import com.trafficmonitor.privacy.monitoring.AttributionRules
import com.trafficmonitor.privacy.monitoring.DomainResolver
import com.trafficmonitor.privacy.monitoring.FlowTracker
import com.trafficmonitor.privacy.monitoring.projectSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringCoreTest {
    private val instagram = Attribution(10342, AttributionStatus.RESOLVED, listOf("com.instagram.android"), "Instagram")
    private val firefox = Attribution(10400, AttributionStatus.RESOLVED, listOf("org.mozilla.firefox"), "Firefox")

    @Test
    fun parsesIpv4AndIpv6EndpointsWithoutResolvingNames() {
        val v4 = EndpointParser.parse("203.0.113.10:443")
        assertEquals("203.0.113.10", v4?.canonicalIp)
        assertEquals(443, v4?.port)
        val v6 = EndpointParser.parse("[2001:db8::1]:443")
        assertEquals("2001:db8:0:0:0:0:0:1", v6?.canonicalIp)
        assertEquals(443, v6?.port)
        assertNull(EndpointParser.parse("example.com:443"))
        assertNull(EndpointParser.normalizeHostname("203.0.113.10"))
        assertEquals("www.example.com", EndpointParser.normalizeHostname("WWW.Example.com."))
    }

    @Test
    fun aggregatesSameFlowAndKeepsApplicationsSeparate() {
        val tracker = FlowTracker(DomainResolver(), ignoredDestination = { false })
        tracker.onFlowOpened(flow("a", "203.0.113.10:443", null), instagram)
        tracker.onFlowOpened(flow("b", "203.0.113.10:443", null), instagram)
        tracker.onFlowClosed(closed("a", "203.0.113.10:443", 100, 40), instagram)
        tracker.onFlowClosed(closed("a", "203.0.113.10:443", 999, 999), instagram)
        tracker.onFlowClosed(closed("b", "203.0.113.10:443", 5, 2), instagram)
        tracker.onFlowClosed(closed("c", "203.0.113.10:443", 7, 1), firefox)
        tracker.onFlowClosed(closed("d", "203.0.113.10:80", 1, 1), instagram)

        val rows = tracker.snapshot()
        val instagram443 = rows.single { it.uid == 10342 && it.remotePort == 443 }
        assertEquals(2, instagram443.connectionCount)
        assertEquals(105, instagram443.bytesReceived)
        assertEquals(42, instagram443.bytesSent)
        assertEquals(1, rows.single { it.uid == 10400 }.connectionCount)
        assertEquals(80, rows.single { it.uid == 10342 && it.remotePort == 80 }.remotePort)
        assertEquals(4, rows.sumOf { it.connectionCount })
    }

    @Test
    fun usesASingleDnsNameAndDoesNotInventOneWhenNamesConflict() {
        var now = 1_000L
        val domains = DomainResolver { now }
        val tracker = FlowTracker(domains, ignoredDestination = { false })
        tracker.onDns(ObservedDns("www.example.com", 1, 0, 30, "203.0.113.8"))
        tracker.onFlowOpened(flow("a", "203.0.113.8:443", null), instagram)
        assertEquals("www.example.com", tracker.snapshot().single().domain)
        assertEquals(DomainSource.DNS_OBSERVED, tracker.snapshot().single().domainSource)

        tracker.onDns(ObservedDns("cdn.example.net", 1, 0, 30, "203.0.113.8"))
        assertNull(tracker.snapshot().single().domain)

        tracker.onFlowOpened(flow("b", "198.51.100.9:443", "Graph.Instagram.com"), instagram)
        val reported = tracker.snapshot().single { it.remoteIp == "198.51.100.9" }
        assertEquals("graph.instagram.com", reported.domain)

        now = 1_000L + 30_000L
        assertNull(domains.lookup("203.0.113.8"))
    }

    @Test
    fun ignoresVirtualDnsAndKeepsUnknownClassification() {
        val tracker = FlowTracker(DomainResolver())
        tracker.onFlowClosed(closed("dns", "10.111.222.3:53", 20, 20), instagram)
        tracker.onFlowClosed(closed("web", "203.0.113.50:443", 10, 4), instagram)
        val rows = tracker.snapshot()
        assertEquals(1, rows.size)
        assertEquals("203.0.113.50", rows.single().remoteIp)

        val projection = projectSession(rows, StubDestinationClassifier())
        assertEquals(Category.UNKNOWN, projection.destinations.single().category)
        assertEquals(PrivacyClassification.UNCLASSIFIED, projection.destinations.single().privacyClassification)
        assertEquals(1, projection.applications.single().unknownCount)
        assertEquals(0, projection.applications.single().knownTrackerCount)
    }

    @Test
    fun classifiesSeededDomainsAndCountsTrackersPerApplication() {
        val rows = listOf(
            snapshot(instagram, "142.250.1.1", "www.google-analytics.com", 443, 10, 4),
            snapshot(instagram, "142.250.1.2", "ads.doubleclick.net", 443, 8, 2),
            snapshot(firefox, "142.250.1.2", "ads.doubleclick.net", 443, 3, 1),
        )
        val projection = projectSession(rows, StubDestinationClassifier())
        val instagramSummary = projection.applications.single { it.uid == 10342 }
        assertEquals(2, instagramSummary.destinationCount)
        assertEquals(1, instagramSummary.analyticsCount)
        assertEquals(1, instagramSummary.advertisingCount)
        assertEquals(1, instagramSummary.knownTrackerCount)
        assertEquals(1, projection.applications.single { it.uid == 10400 }.knownTrackerCount)
        val analytics = projection.destinations.single { it.domain == "www.google-analytics.com" }
        assertEquals(Category.ANALYTICS, analytics.category)
        assertEquals(PrivacyClassification.POTENTIAL_TRACKER, analytics.privacyClassification)
        assertTrue(analytics.classificationSources.contains(StubDestinationClassifier.SOURCE))
        assertEquals(ClassificationUnknown.category, StubDestinationClassifier().classify(null, "203.0.113.1").category)
    }

    @Test
    fun attributionDoesNotGuessAPackage() {
        assertEquals(AttributionStatus.UNKNOWN, AttributionRules.status(-1, emptyList(), 1000))
        assertEquals(AttributionStatus.RESOLVED, AttributionRules.status(10342, listOf("com.instagram.android"), 1000))
        assertEquals(AttributionStatus.SHARED_UID, AttributionRules.status(12000, listOf("a.b", "c.d"), 1000))
        assertEquals(AttributionStatus.SELF, AttributionRules.status(1000, listOf("com.trafficmonitor.privacy"), 1000))
        assertEquals(AttributionStatus.SYSTEM, AttributionRules.status(1000, emptyList(), 2000))
        assertEquals("203.0.113.9", EndpointParser.ipLiterals("A 203.0.113.9 extra").single())
    }

    private fun flow(id: String, destination: String, domain: String?) = ObservedFlow(
        connectionId = id,
        protocol = 6,
        uid = 0,
        source = "10.111.222.2:40000",
        destination = destination,
        reportedDomain = domain,
    )

    private fun closed(id: String, destination: String, received: Long, sent: Long) = ClosedFlow(
        connectionId = id,
        protocol = 6,
        uid = 0,
        source = "10.111.222.2:40000",
        destination = destination,
        reportedDomain = null,
        bytesReceived = received,
        bytesSent = sent,
    )

    private fun snapshot(
        attribution: Attribution,
        ip: String,
        domain: String?,
        port: Int,
        received: Long,
        sent: Long,
    ) = AggregateSnapshot(
        uid = attribution.uid,
        attribution = attribution,
        remoteIp = ip,
        domain = domain,
        domainSource = if (domain == null) DomainSource.UNKNOWN else DomainSource.DNS_OBSERVED,
        protocol = "TCP",
        remotePort = port,
        connectionCount = 1,
        bytesSent = sent,
        bytesReceived = received,
    )

    private object ClassificationUnknown {
        val category = Category.UNKNOWN
    }
}
