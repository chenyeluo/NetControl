package com.netcontrol.core.backend.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.netcontrol.BuildConfig
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.DiagnosticProbeReport
import com.netcontrol.core.backend.FirewallApplyResult
import com.netcontrol.core.backend.FirewallBackend
import com.netcontrol.core.backend.WifiShellInfo
import com.netcontrol.core.shizuku.IFirewallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    BINDING,
    CONNECTED
}

/**
 * 负责管理 Shizuku Binder 生命周期、授权请求与 AIDL FirewallUserService 绑定，
 * 同时作为主引擎实现 FirewallBackend 接口。
 */
class ShizukuManager(private val context: Context) : FirewallBackend {

    companion object {
        const val REQUEST_CODE_SHIZUKU = 1001
    }

    override val type: BackendType = BackendType.SHIZUKU

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var service: IFirewallService? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, FirewallUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("firewall_shizuku")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val iface = IFirewallService.Stub.asInterface(binder)
                service = iface
                _status.value = ShizukuStatus.CONNECTED
                _isReady.value = true
                _lastError.value = null
            } else {
                _lastError.value = "Shizuku Binder 无效或已断开"
                _status.value = ShizukuStatus.NOT_RUNNING
                _isReady.value = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _isReady.value = false
            refreshState()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        _isReady.value = false
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    _status.value = ShizukuStatus.PERMISSION_REQUIRED
                    _lastError.value = "用户拒绝了 Shizuku 授权"
                }
            }
        }

    fun initialize() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshState()
    }

    fun refreshState() {
        val pingOk = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!pingOk) {
            service = null
            _isReady.value = false
            _status.value = ShizukuStatus.NOT_RUNNING
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!granted) {
            _isReady.value = false
            _status.value = ShizukuStatus.PERMISSION_REQUIRED
            return
        }

        if (service?.asBinder()?.pingBinder() == true) {
            _status.value = ShizukuStatus.CONNECTED
            _isReady.value = true
        } else {
            bindUserService()
        }
    }

    fun requestPermissionOrBind() {
        val pingOk = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!pingOk) {
            _status.value = ShizukuStatus.NOT_RUNNING
            _lastError.value = "Shizuku 服务未运行，请先打开 Shizuku 应用通过无线调试启动"
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (granted) {
            bindUserService()
        } else {
            try {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            } catch (t: Throwable) {
                _lastError.value = "请求 Shizuku 授权失败: ${t.message}"
            }
        }
    }

    private fun bindUserService() {
        if (_status.value == ShizukuStatus.BINDING) return
        _status.value = ShizukuStatus.BINDING
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            _status.value = ShizukuStatus.NOT_RUNNING
            _isReady.value = false
            _lastError.value = "绑定 UserService 失败: ${t.message}"
        }
    }

    suspend fun runDiagnosticProbe(samplePackage: String): Result<DiagnosticProbeReport> =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext Result.failure(
                IllegalStateException("Shizuku UserService 尚未连接")
            )
            runCatching {
                val raw = svc.probeEnvironment(samplePackage)
                parseProbeReport(raw)
            }
        }

    suspend fun queryCurrentWifiViaShell(): WifiShellInfo? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        runCatching {
            WifiStatusParser.parse(svc.currentWifiSummary)
        }.getOrNull()
    }

    suspend fun switchDriver(driverName: String): Boolean = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext false
        runCatching { svc.setDriver(driverName) }.getOrDefault(false)
    }

    suspend fun ensureFirewallChainActive(): Boolean = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext false
        runCatching { svc.ensureFirewallEnabled() }.getOrDefault(false)
    }

    /**
     * 仅对发生状态变更的包列表执行增量批量 IPC 下发，供 RuleEngine Diff 同步调用。
     */
    suspend fun applyDeltaChanges(
        packages: List<String>,
        uids: List<Int>,
        blockedFlags: List<Boolean>
    ): FirewallApplyResult = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext FirewallApplyResult(
            success = false,
            backendType = BackendType.SHIZUKU,
            driverName = "disconnected",
            appliedCount = 0,
            message = "Shizuku UserService 未连接"
        )
        if (packages.isEmpty()) {
            return@withContext FirewallApplyResult(
                success = true,
                backendType = BackendType.SHIZUKU,
                driverName = svc.activeDriver ?: FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3,
                appliedCount = 0,
                message = "规则无变化（Diff 为空）"
            )
        }
        runCatching {
            val driver = svc.activeDriver ?: FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3
            val count = svc.applyBatchBlocked(
                packages.toTypedArray(),
                uids.toIntArray(),
                blockedFlags.toBooleanArray()
            )
            FirewallApplyResult(
                success = count == packages.size,
                backendType = BackendType.SHIZUKU,
                driverName = driver,
                appliedCount = count,
                message = "Shizuku 增量下发 $count/${packages.size} 项 (driver=$driver)"
            )
        }.getOrElse { err ->
            FirewallApplyResult(
                success = false,
                backendType = BackendType.SHIZUKU,
                driverName = "error",
                appliedCount = 0,
                message = "增量 IPC 异常: ${err.message}"
            )
        }
    }

    override suspend fun setAppBlocked(
        packageName: String,
        uid: Int,
        blocked: Boolean
    ): FirewallApplyResult = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext FirewallApplyResult(
            success = false,
            backendType = BackendType.SHIZUKU,
            driverName = "disconnected",
            appliedCount = 0,
            message = "Shizuku UserService 未连接"
        )
        runCatching {
            val driver = svc.activeDriver ?: FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3
            val ok = svc.setPackageBlocked(packageName, uid, blocked)
            FirewallApplyResult(
                success = ok,
                backendType = BackendType.SHIZUKU,
                driverName = driver,
                appliedCount = if (ok) 1 else 0,
                message = if (ok) {
                    "${if (blocked) "已阻断" else "已放行"} $packageName (driver=$driver)"
                } else {
                    "底层命令返回失败 (driver=$driver)"
                }
            )
        }.getOrElse { err ->
            FirewallApplyResult(
                success = false,
                backendType = BackendType.SHIZUKU,
                driverName = "error",
                appliedCount = 0,
                message = "IPC 调用异常: ${err.message}"
            )
        }
    }

    override suspend fun syncBlockedApps(
        blockedPackages: Set<String>,
        allKnownPackages: Map<String, Int>
    ): FirewallApplyResult = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext FirewallApplyResult(
            success = false,
            backendType = BackendType.SHIZUKU,
            driverName = "disconnected",
            appliedCount = 0,
            message = "Shizuku UserService 未连接"
        )
        runCatching {
            val entries = allKnownPackages.entries.toList()
            val pkgs = Array(entries.size) { entries[it].key }
            val uids = IntArray(entries.size) { entries[it].value }
            val flags = BooleanArray(entries.size) { entries[it].key in blockedPackages }
            val count = svc.applyBatchBlocked(pkgs, uids, flags)
            val driver = svc.activeDriver ?: FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3
            FirewallApplyResult(
                success = count == entries.size,
                backendType = BackendType.SHIZUKU,
                driverName = driver,
                appliedCount = count,
                message = "批量同步完成: $count/${entries.size} (阻断 ${blockedPackages.size} 项)"
            )
        }.getOrElse { err ->
            FirewallApplyResult(
                success = false,
                backendType = BackendType.SHIZUKU,
                driverName = "error",
                appliedCount = 0,
                message = "批量同步异常: ${err.message}"
            )
        }
    }

    suspend fun disableFirewallChain(): Boolean = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext false
        runCatching { svc.disableFirewallChain() }.getOrDefault(false)
    }

    override suspend fun resetAll(allKnownPackages: Map<String, Int>): FirewallApplyResult =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext FirewallApplyResult(
                success = false,
                backendType = BackendType.SHIZUKU,
                driverName = "disconnected",
                appliedCount = 0,
                message = "Shizuku UserService 未连接"
            )
            runCatching {
                val entries = allKnownPackages.entries.toList()
                val pkgs = Array(entries.size) { entries[it].key }
                val uids = IntArray(entries.size) { entries[it].value }
                val ok = svc.clearAllBlocked(pkgs, uids)
                FirewallApplyResult(
                    success = ok,
                    backendType = BackendType.SHIZUKU,
                    driverName = svc.activeDriver ?: "unknown",
                    appliedCount = entries.size,
                    message = if (ok) "已关闭内核防火墙链并恢复全部应用联网" else "内核链关闭或部分应用恢复失败"
                )
            }.getOrElse { err ->
                FirewallApplyResult(
                    success = false,
                    backendType = BackendType.SHIZUKU,
                    driverName = "error",
                    appliedCount = 0,
                    message = "重置异常: ${err.message}"
                )
            }
        }

    internal fun parseProbeReport(raw: String): DiagnosticProbeReport {
        val map = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        return DiagnosticProbeReport(
            shellUid = map["shell_uid"]?.toIntOrNull() ?: -1,
            androidRelease = map["android_release"].orEmpty(),
            sdkInt = map["sdk_int"]?.toIntOrNull() ?: -1,
            displayId = map["display_id"].orEmpty(),
            chain3Supported = map["chain3_supported"].equals("true", ignoreCase = true),
            chain3Detail = map["chain3_detail"].orEmpty(),
            netPolicySupported = map["netpolicy_supported"].equals("true", ignoreCase = true),
            netPolicyDetail = map["netpolicy_detail"].orEmpty(),
            wifiInfo = WifiShellInfo(
                connected = map["wifi_connected"].equals("true", ignoreCase = true),
                ssid = map["wifi_ssid"]?.ifBlank { null },
                bssid = map["wifi_bssid"]?.ifBlank { null },
                rawSnippet = map["wifi_raw"].orEmpty()
            ),
            recommendedDriver = map["recommended_driver"].orEmpty()
        )
    }
}
