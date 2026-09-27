package com.trafficmonitor.privacy.monitoring

import com.trafficmonitor.privacy.forwarding.EndpointParser
import com.trafficmonitor.privacy.forwarding.ParsedEndpoint

/**
 * Structured metadata for a flow endpoint.
 *
 * The production path does not parse raw IP packets from the TUN. That descriptor
 * has a single reader, the forwarding engine. Re-parsing it here would consume
 * packets instead of observing them. [EndpointParser] interprets the tuples and
 * DNS answers the engine already reports.
 */
object PacketParser {
    fun endpoint(value: String?): ParsedEndpoint? = EndpointParser.parse(value)
}
