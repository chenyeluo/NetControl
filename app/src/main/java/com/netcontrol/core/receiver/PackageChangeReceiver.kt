package com.netcontrol.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.netcontrol.NetControlApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 监听应用安装、更新与卸载广播，实时增量维护 apps 表与分组关联，
 * 并对重装应用执行 SHA-256 签名安全核验。
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        if (pkg == context.packageName) return

        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        // 覆盖更新时会先发一次 EXTRA_REPLACING=true 的 REMOVED，忽略它并等待随后的 ADDED/REPLACED
        if (action == Intent.ACTION_PACKAGE_REMOVED && isReplacing) return

        val app = context.applicationContext as? NetControlApp ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        app.container.repository.handlePackageAddedOrReplaced(pkg)
                        app.container.ruleEngine.evaluateAndApply("PACKAGE_ADDED_OR_REPLACED:$pkg")
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        app.container.repository.handlePackageRemoved(pkg)
                        app.container.ruleEngine.evaluateAndApply("PACKAGE_REMOVED:$pkg")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
