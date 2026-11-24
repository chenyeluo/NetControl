package com.netcontrol.core.backend.shizuku

import android.os.Build
import android.os.Process
import androidx.annotation.Keep
import com.netcontrol.core.shizuku.IFirewallService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 独立 Shell 进程（UID 2000 / 0）中的常驻 AIDL UserService。
 * 直接通过内核 netd / ConnectivityManager 命令行接口执行应用级断网，避免频繁 fork 外部终端，
 * 并通过 Shell 读取 Wi-Fi BSSID 绕开后台定位权限限制。
 */
@Keep
class FirewallUserService : IFirewallService.Stub() {

    companion object {
        const val DRIVER_CONNECTIVITY_CHAIN3 = "connectivity_chain3"
        const val DRIVER_NETPOLICY_RESTRICT = "netpolicy_restrict"
        private const val CMD_TIMEOUT_SECONDS = 5L
        private const val BATCH_TIMEOUT_SECONDS = 15L
        private val SAFE_PACKAGE_REGEX = Regex("""^[a-zA-Z0-9._]+$""")
    }

    @Volatile
    private var activeDriver: String = DRIVER_CONNECTIVITY_CHAIN3

    @Volatile
    private var chain3Initialized: Boolean = false

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun setDriver(driverName: String?): Boolean {
        return when (driverName) {
            DRIVER_CONNECTIVITY_CHAIN3 -> {
                activeDriver = DRIVER_CONNECTIVITY_CHAIN3
                ensureFirewallEnabled()
            }
            DRIVER_NETPOLICY_RESTRICT -> {
                activeDriver = DRIVER_NETPOLICY_RESTRICT
                true
            }
            else -> false
        }
    }

    override fun getActiveDriver(): String = activeDriver

    override fun ensureFirewallEnabled(): Boolean {
        if (activeDriver != DRIVER_CONNECTIVITY_CHAIN3) return true
        val res = execCmd("cmd", "connectivity", "set-chain3-enabled", "true")
        chain3Initialized = res.exitCode == 0
        return chain3Initialized
    }

    override fun disableFirewallChain(): Boolean {
        chain3Initialized = false
        val res = execCmd("cmd", "connectivity", "set-chain3-enabled", "false")
        return res.exitCode == 0
    }

    override fun setPackageBlocked(packageName: String?, uid: Int, blocked: Boolean): Boolean {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return false
        return when (activeDriver) {
            DRIVER_CONNECTIVITY_CHAIN3 -> {
                // 每次执行断网都强制确保内核 OEM_DENY_3 链处于开启态，防止系统/One UI 静默关闭导致规则形同虚设
                if (blocked || !chain3Initialized) {
                    ensureFirewallEnabled()
                }
                val allowFlag = if (blocked) "false" else "true"
                val res = execCmd("cmd", "connectivity", "set-package-networking-enabled", allowFlag, pkg)
                res.exitCode == 0
            }
            DRIVER_NETPOLICY_RESTRICT -> {
                if (uid <= 0) return false
                val op = if (blocked) "add" else "remove"
                val res = execCmd("cmd", "netpolicy", op, "restrict-background-blacklist", uid.toString())
                res.exitCode == 0
            }
            else -> false
        }
    }

    override fun applyBatchBlocked(
        packages: Array<out String>?,
        uids: IntArray?,
        blockedFlags: BooleanArray?
    ): Int {
        if (packages == null || uids == null || blockedFlags == null) return 0
        val count = minOf(packages.size, uids.size, blockedFlags.size)
        if (count == 0) return 0

        val hasAnyBlock = (0 until count).any { blockedFlags[it] }
        if (activeDriver == DRIVER_CONNECTIVITY_CHAIN3 && (hasAnyBlock || !chain3Initialized)) {
            ensureFirewallEnabled()
        }

        // 单条或两条直接走 execCmd；多条改用单个 sh 管道高速批处理，避免频繁 fork 超时
        if (count <= 2) {
            var succeeded = 0
            for (i in 0 until count) {
                if (setPackageBlocked(packages[i], uids[i], blockedFlags[i])) {
                    succeeded++
                }
            }
            return succeeded
        }

        val script = StringBuilder()
        if (activeDriver == DRIVER_CONNECTIVITY_CHAIN3 && hasAnyBlock) {
            script.append("cmd connectivity set-chain3-enabled true\n")
        }
        var validCount = 0
        for (i in 0 until count) {
            val pkg = packages[i]
            val uid = uids[i]
            val blocked = blockedFlags[i]
            when (activeDriver) {
                DRIVER_CONNECTIVITY_CHAIN3 -> {
                    if (SAFE_PACKAGE_REGEX.matches(pkg)) {
                        val allowFlag = if (blocked) "false" else "true"
                        script.append("cmd connectivity set-package-networking-enabled ")
                            .append(allowFlag).append(' ').append(pkg).append('\n')
                        validCount++
                    }
                }
                DRIVER_NETPOLICY_RESTRICT -> {
                    if (uid > 0) {
                        val op = if (blocked) "add" else "remove"
                        script.append("cmd netpolicy ").append(op)
                            .append(" restrict-background-blacklist ").append(uid).append('\n')
                        validCount++
                    }
                }
            }
        }
        if (validCount == 0) return 0
        val batchRes = execShellScript(script.toString())
        return if (batchRes.exitCode == 0) validCount else 0
    }

    override fun isPackageBlocked(packageName: String?, uid: Int): Boolean {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return false
        return when (activeDriver) {
            DRIVER_CONNECTIVITY_CHAIN3 -> {
                val res = execCmd("cmd", "connectivity", "get-package-networking-enabled", pkg)
                res.exitCode == 0 && (
                    res.output.contains(":deny", ignoreCase = true) ||
                        res.output.contains("false", ignoreCase = true)
                    )
            }
            DRIVER_NETPOLICY_RESTRICT -> {
                if (uid <= 0) return false
                val res = execCmd("cmd", "netpolicy", "list", "restrict-background-blacklist")
                res.exitCode == 0 && Regex("""\b$uid\b""").containsMatchIn(res.output)
            }
            else -> false
        }
    }

    override fun getCurrentWifiSummary(): String {
        val res = execCmd("cmd", "wifi", "status")
        if (res.exitCode == 0 && res.output.isNotBlank()) {
            return res.output
        }
        return execCmd("dumpsys", "wifi").output.lines()
            .filter { line ->
                line.contains("mWifiInfo", ignoreCase = true) ||
                    line.contains("BSSID", ignoreCase = true) ||
                    line.contains("SSID", ignoreCase = true)
            }
            .take(6)
            .joinToString("\n")
    }

    override fun clearAllBlocked(packages: Array<out String>?, uids: IntArray?): Boolean {
        // 第一防线：立即原子关闭 chain3 内核链，10ms 内恢复全机联网
        val chainDisabled = if (activeDriver == DRIVER_CONNECTIVITY_CHAIN3) {
            disableFirewallChain()
        } else {
            true
        }
        if (packages == null || uids == null || packages.isEmpty()) return chainDisabled
        val count = minOf(packages.size, uids.size)
        val allowAllFlags = BooleanArray(count) { false }
        // 注意：不要重新开启 chain3，仅清除已记录包的 deny 位
        val script = StringBuilder()
        for (i in 0 until count) {
            val pkg = packages[i]
            val uid = uids[i]
            when (activeDriver) {
                DRIVER_CONNECTIVITY_CHAIN3 -> {
                    if (SAFE_PACKAGE_REGEX.matches(pkg)) {
                        script.append("cmd connectivity set-package-networking-enabled true ")
                            .append(pkg).append('\n')
                    }
                }
                DRIVER_NETPOLICY_RESTRICT -> {
                    if (uid > 0) {
                        script.append("cmd netpolicy remove restrict-background-blacklist ")
                            .append(uid).append('\n')
                    }
                }
            }
        }
        if (script.isNotEmpty()) {
            execShellScript(script.toString())
        }
        if (activeDriver == DRIVER_CONNECTIVITY_CHAIN3) {
            disableFirewallChain()
        }
        return chainDisabled && allowAllFlags.size == count
    }

    override fun probeEnvironment(samplePackage: String?): String {
        val sb = StringBuilder()
        sb.appendLine("shell_uid=${Process.myUid()}")
        sb.appendLine("android_release=${Build.VERSION.RELEASE}")
        sb.appendLine("sdk_int=${Build.VERSION.SDK_INT}")
        sb.appendLine("display_id=${Build.DISPLAY}")

        // 1. 探测 cmd connectivity set-chain3-enabled
        val chain3Enable = execCmd("cmd", "connectivity", "set-chain3-enabled", "true")
        val pkgToCheck = samplePackage?.takeIf { it.isNotBlank() } ?: "com.android.settings"
        val chain3Query = execCmd("cmd", "connectivity", "get-package-networking-enabled", pkgToCheck)
        val chain3Ok = chain3Enable.exitCode == 0 && chain3Query.exitCode == 0
        sb.appendLine("chain3_supported=$chain3Ok")
        sb.appendLine(
            "chain3_detail=enable(${chain3Enable.exitCode}):${sanitizeOneLine(chain3Enable.output)} | " +
                "query(${chain3Query.exitCode}):${sanitizeOneLine(chain3Query.output)}"
        )

        // 2. 探测 cmd netpolicy 备选指令
        val netPolicyList = execCmd("cmd", "netpolicy", "list", "restrict-background-blacklist")
        val netPolicyOk = netPolicyList.exitCode == 0
        sb.appendLine("netpolicy_supported=$netPolicyOk")
        sb.appendLine(
            "netpolicy_detail=list(${netPolicyList.exitCode}):${sanitizeOneLine(netPolicyList.output)}"
        )

        // 3. 探测免定位读取 Wi-Fi BSSID
        val wifiRaw = getCurrentWifiSummary()
        val wifiInfo = WifiStatusParser.parse(wifiRaw)
        sb.appendLine("wifi_connected=${wifiInfo.connected}")
        sb.appendLine("wifi_ssid=${wifiInfo.ssid.orEmpty()}")
        sb.appendLine("wifi_bssid=${wifiInfo.bssid.orEmpty()}")
        sb.appendLine("wifi_raw=${sanitizeOneLine(wifiInfo.rawSnippet)}")

        val recommended = when {
            chain3Ok -> DRIVER_CONNECTIVITY_CHAIN3
            netPolicyOk -> DRIVER_NETPOLICY_RESTRICT
            else -> "none_fallback_blackhole_vpn"
        }
        sb.appendLine("recommended_driver=$recommended")
        return sb.toString()
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun execCmd(vararg args: String): CmdResult {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    line = reader.readLine()
                }
            }
            val finished = process.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CmdResult(-1, "Command timed out: ${args.joinToString(" ")}")
            } else {
                CmdResult(process.exitValue(), output.toString().trim())
            }
        } catch (t: Throwable) {
            CmdResult(-1, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun execShellScript(script: String): CmdResult {
        return try {
            val process = ProcessBuilder("/system/bin/sh")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit 0\n")
                writer.flush()
            }
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    line = reader.readLine()
                }
            }
            val finished = process.waitFor(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CmdResult(-1, "Batch shell timed out")
            } else {
                CmdResult(process.exitValue(), output.toString().trim())
            }
        } catch (t: Throwable) {
            CmdResult(-1, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun sanitizeOneLine(text: String): String =
        text.replace("\r", " ").replace("\n", " / ").take(180)
}
