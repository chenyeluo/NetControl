package com.netcontrol.core.backend.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStatusParserTest {

    @Test
    fun parseConnectedCmdWifiStatus_extractsSsidAndBssid() {
        val sample = """
            Wifi is enabled
            Wifi is connected to "HomeMesh-5G"
            WifiInfo: SSID: "HomeMesh-5G", BSSID: AA:BB:CC:11:22:33, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, RSSI: -52
        """.trimIndent()

        val info = WifiStatusParser.parse(sample)
        assertTrue(info.connected)
        assertEquals("HomeMesh-5G", info.ssid)
        assertEquals("aa:bb:cc:11:22:33", info.bssid)
    }

    @Test
    fun parseDisconnectedWifiStatus_returnsDisconnected() {
        val sample = """
            Wifi is enabled
            Wifi is not connected
        """.trimIndent()

        val info = WifiStatusParser.parse(sample)
        assertFalse(info.connected)
        assertNull(info.ssid)
        assertNull(info.bssid)
    }

    @Test
    fun parsePlaceholderBssid_filtersOutInvalidMac() {
        val sample = """
            Wifi is connected to "OfficeWiFi"
            WifiInfo: SSID: "OfficeWiFi", BSSID: 02:00:00:00:00:00
        """.trimIndent()

        val info = WifiStatusParser.parse(sample)
        assertTrue(info.connected)
        assertEquals("OfficeWiFi", info.ssid)
        assertNull(info.bssid)
    }
}
