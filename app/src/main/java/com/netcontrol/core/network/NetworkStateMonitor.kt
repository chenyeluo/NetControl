package com.netcontrol.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.telephony.SubscriptionManager
import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轻量级事件驱动网络监听器：
 * 1. 通过 registerDefaultNetworkCallback 监听 Wi-Fi / 移动数据 / 离线变化；
 * 2. 穿透 Clash/v2ray 等第三方 VPN 隧道，准确识别真实底层物理网络（Wi-Fi 还是移动数据）；
 * 3. Wi-Fi BSSID 采用双通道获取：Shizuku 激活时优先走 Shell（免定位权限），否则降级读 WifiInfo；
 * 4. 双 SIM 通过 SubscriptionManager.getDefaultDataSubscriptionId() + getSlotIndex() 区分 SIM 1 / SIM 2；
 * 5. 网络切换时提供 300ms 防抖与 Fail-Safe 安全过渡期。
 */
class NetworkStateMonitor(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val database: AppDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val DEBOUNCE_MS = 300L
    }

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val _currentContext = MutableStateFlow(ResolvedNetworkContext.OFFLINE)
    val currentContext: StateFlow<ResolvedNetworkContext> = _currentContext.asStateFlow()

    @Volatile
    private var isStarted = false

    private var debounceJob: Job? = null
    private var onContextChangedListener: (suspend (ResolvedNetworkContext, String) -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onAvailable(network: Network) {
            scheduleRefresh("NETWORK_AVAILABLE", enterFailSafeIfLeavingTrusted = false)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleRefresh("NETWORK_CAPABILITIES_CHANGED", enterFailSafeIfLeavingTrusted = false)
        }

        override fun onLost(network: Network) {
            scheduleRefresh("NETWORK_LOST", enterFailSafeIfLeavingTrusted = true)
        }
    }

    fun setOnContextChangedListener(listener: suspend (ResolvedNetworkContext, String) -> Unit) {
        onContextChangedListener = listener
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Throwable) {
            // 极少数异常环境下退化为手动刷新
        }
        // 当 Shizuku 刚连上时立即刷新一次（利用 Shell 能力补读 BSSID）
        appScope.launch {
            shizukuManager.isReady.collect { ready ->
                if (ready) {
                    refreshNow("SHIZUKU_READY_REPROBE_NETWORK")
                }
            }
        }
        appScope.launch {
            refreshNow("INITIAL_NETWORK_PROBE")
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        debounceJob?.cancel()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun scheduleRefresh(reason: String, enterFailSafeIfLeavingTrusted: Boolean) {
        debounceJob?.cancel()
        debounceJob = appScope.launch(Dispatchers.IO) {
            // 安全过渡期：离开可信 Wi-Fi 且开启了未知网络严防时，立即先进入 Fail-Safe 过渡态
            val prev = _currentContext.value
            if (enterFailSafeIfLeavingTrusted && prev.kind == ResolvedProfileKind.WIFI_TRUSTED) {
                val settings = settingsDataStore.getSnapshot()
                if (settings.blockUnknownWifi) {
                    val transitionCtx = prev.copy(transitionFailSafe = true)
                    _currentContext.value = transitionCtx
                    onContextChangedListener?.invoke(transitionCtx, "FAIL_SAFE_TRANSITION")
                }
            }
            delay(DEBOUNCE_MS)
            refreshNow(reason)
        }
    }

    suspend fun refreshNow(reason: String): ResolvedNetworkContext = withContext(Dispatchers.IO) {
        val raw = probeRawSnapshot()
        val settings = settingsDataStore.getSnapshot()
        val profiles = database.networkProfileDao().getAllProfilesOnce()
        val mappings = database.networkProfileDao().getAllBssidMappingsOnce()

        val resolved = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = profiles,
            bssidMappings = mappings,
            settings = settings,
            transitionFailSafe = false
        )
        val previous = _currentContext.value
        _currentContext.value = resolved

        if (previous != resolved || reason.startsWith("MANUAL") || reason == "INITIAL_NETWORK_PROBE") {
            onContextChangedListener?.invoke(resolved, reason)
        }
        resolved
    }

    @Suppress("DEPRECATION")
    private suspend fun probeRawSnapshot(): RawNetworkSnapshot {
        val cm = connectivityManager ?: return RawNetworkSnapshot(NetworkTransportKind.OFFLINE)

        val activeNetwork = cm.activeNetwork
        val activeCaps = activeNetwork?.let { cm.getNetworkCapabilities(it) }

        // 先尝试从默认网络判断；若默认网络是第三方 VPN 且未透传底层 Transport，则扫描底层物理网络
        var physicalCaps: NetworkCapabilities? = when {
            activeCaps == null -> null
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> activeCaps
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                findUnderlyingPhysicalCapabilities(cm)
            }
            else -> activeCaps
        }

        if (physicalCaps == null) {
            physicalCaps = findUnderlyingPhysicalCapabilities(cm)
        }

        if (physicalCaps == null) {
            return RawNetworkSnapshot(NetworkTransportKind.OFFLINE)
        }

        return when {
            physicalCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                probeWifiSnapshot(physicalCaps)
            }
            physicalCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                probeMobileSnapshot()
            }
            else -> RawNetworkSnapshot(NetworkTransportKind.OFFLINE)
        }
    }

    @Suppress("DEPRECATION")
    private fun findUnderlyingPhysicalCapabilities(cm: ConnectivityManager): NetworkCapabilities? {
        val networks = try {
            cm.allNetworks
        } catch (_: Throwable) {
            emptyArray()
        }
        var cellularFallback: NetworkCapabilities? = null
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return caps
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularFallback = caps
            }
        }
        return cellularFallback
    }

    private suspend fun probeWifiSnapshot(caps: NetworkCapabilities): RawNetworkSnapshot {
        // 通道 1（首选）：Shizuku Shell 读取 cmd wifi status（免定位权限）
        if (shizukuManager.isReady.value) {
            val shellWifi = shizukuManager.queryCurrentWifiViaShell()
            if (shellWifi != null && shellWifi.connected) {
                val normalized = NetworkProfileResolver.normalizeBssid(shellWifi.bssid)
                if (normalized != null || !shellWifi.ssid.isNullOrBlank()) {
                    return RawNetworkSnapshot(
                        transport = NetworkTransportKind.WIFI,
                        bssid = normalized,
                        ssid = shellWifi.ssid,
                        bssidSource = "shizuku_shell"
                    )
                }
            }
        }

        // 通道 2（降级）：系统 NetworkCapabilities.transportInfo (WifiInfo)
        val wifiInfo = caps.transportInfo as? WifiInfo
        val rawBssid = wifiInfo?.bssid
        val normalizedBssid = NetworkProfileResolver.normalizeBssid(rawBssid)
        val cleanSsid = wifiInfo?.ssid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) || it.isBlank() }

        return RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = normalizedBssid,
            ssid = cleanSsid,
            bssidSource = if (normalizedBssid != null) "wifi_info_api" else "unavailable"
        )
    }

    private fun probeMobileSnapshot(): RawNetworkSnapshot {
        val defaultSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val slotIndex = if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                val idx = SubscriptionManager.getSlotIndex(defaultSubId)
                if (idx >= 0) idx else null
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        return RawNetworkSnapshot(
            transport = NetworkTransportKind.MOBILE,
            dataSubId = defaultSubId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID },
            simSlotIndex = slotIndex
        )
    }
}
