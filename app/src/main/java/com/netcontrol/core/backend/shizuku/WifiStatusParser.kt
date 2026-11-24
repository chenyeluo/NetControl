package com.netcontrol.core.backend.shizuku

import com.netcontrol.core.backend.WifiShellInfo

/**
 * 纯 Kotlin 解析器：从 `cmd wifi status` 或 `dumpsys wifi` 输出中提取当前 Wi-Fi 的 SSID / BSSID，
 * 便于脱离设备进行 JVM 单元测试。
 */
object WifiStatusParser {

    private val CONNECTED_REGEX = Regex("""Wifi is connected to "([^"]*)"""", RegexOption.IGNORE_CASE)
    private val SSID_REGEX = Regex("""\bSSID:\s*"?([^",\r\n]+)"?""")
    private val BSSID_REGEX = Regex("""\bBSSID:\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")
    private val MAC_PLACEHOLDER = setOf("02:00:00:00:00:00", "00:00:00:00:00:00")

    fun parse(rawOutput: String): WifiShellInfo {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) {
            return WifiShellInfo(
                connected = false,
                ssid = null,
                bssid = null,
                rawSnippet = ""
            )
        }

        val disconnected = trimmed.contains("Wifi is not connected", ignoreCase = true) ||
            trimmed.contains("Wifi is disabled", ignoreCase = true)

        val connectedSsid = CONNECTED_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        val fieldSsid = SSID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.takeUnless {
            it.equals("<unknown ssid>", ignoreCase = true) || it.equals("null", ignoreCase = true)
        }
        val ssid = connectedSsid ?: fieldSsid

        val rawBssid = BSSID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase()
        val bssid = rawBssid?.takeUnless { it in MAC_PLACEHOLDER }

        val isConnected = !disconnected && (connectedSsid != null || bssid != null || ssid != null)
        val snippet = trimmed.lines().take(8).joinToString("\n")

        return WifiShellInfo(
            connected = isConnected,
            ssid = if (isConnected) ssid else null,
            bssid = if (isConnected) bssid else null,
            rawSnippet = snippet
        )
    }
}
