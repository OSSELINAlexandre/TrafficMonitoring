package com.trafficmonitor.privacy

import com.trafficmonitor.privacy.data.model.UnderlayType
import com.trafficmonitor.privacy.monitoring.UnderlayPreference
import com.trafficmonitor.privacy.ui.underlaySessionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlayPreferenceTest {
    @Test
    fun validatedWifiOutranksMobile() {
        val wifi = UnderlayPreference.score(validated = true, wifi = true, cellular = false)
        val cellular = UnderlayPreference.score(validated = true, wifi = false, cellular = true)
        val plainWifi = UnderlayPreference.score(validated = false, wifi = true, cellular = false)
        assertTrue(wifi > cellular)
        assertTrue(cellular > plainWifi)
    }

    @Test
    fun unvalidatedWifiStillBeatsUnvalidatedMobile() {
        val wifi = UnderlayPreference.score(validated = false, wifi = true, cellular = false)
        val cellular = UnderlayPreference.score(validated = false, wifi = false, cellular = true)
        assertTrue(wifi > cellular)
    }

    @Test
    fun kindAndSessionLabelFollowTheRecordedUnderlay() {
        assertEquals(UnderlayType.WIFI, UnderlayPreference.kind(wifi = true, cellular = false))
        assertEquals(UnderlayType.WIFI, UnderlayPreference.kind(wifi = true, cellular = true))
        assertEquals(UnderlayType.CELLULAR, UnderlayPreference.kind(wifi = false, cellular = true))
        assertEquals(UnderlayType.OTHER, UnderlayPreference.kind(wifi = false, cellular = false))
        assertEquals("Session en Wi\u2011Fi", underlaySessionLabel(UnderlayType.WIFI))
        assertEquals("Session en 5G / mobile", underlaySessionLabel(UnderlayType.CELLULAR))
        assertNull(underlaySessionLabel(UnderlayType.UNKNOWN))
        assertNull(underlaySessionLabel(UnderlayType.OTHER))
    }
}
