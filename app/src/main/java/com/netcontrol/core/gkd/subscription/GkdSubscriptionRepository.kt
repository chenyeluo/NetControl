package com.netcontrol.core.gkd.subscription

import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.GkdActionLogEntity
import com.netcontrol.data.local.entity.GkdGroupConfigEntity
import com.netcontrol.data.local.entity.GkdSubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class CompiledGkdRegistry(
    val subscriptions: List<GkdSubscription>,
    val enabledGlobalGroups: List<GkdRuleGroup>,
    val enabledAppGroupsByPkg: Map<String, List<GkdRuleGroup>>
)

/**
 * GKD 订阅仓库与远程更新器：
 * - 支持 HTTPS 远程 URL 导入/更新标准 GKD 订阅（JSON / JSON5）；
 * - 支持本地粘贴 JSON5 导入；
 * - 首次启动自动内置一份轻量开箱即用的「NetControl 内置通用开屏跳过订阅」（含百度网盘、通用「跳过」全局规则等）。
 */
class GkdSubscriptionRepository(private val database: AppDatabase) {

    companion object {
        const val BUILTIN_SUB_ID = -1L

        val BUILTIN_DEFAULT_SUBSCRIPTION_JSON5 = """
            {
              id: -1,
              name: "NetControl 内置通用跳过规则",
              version: 1,
              author: "NetControl",
              categories: [
                { key: 0, name: "开屏广告", enable: true },
                { key: 1, name: "青少年模式", enable: true }
              ],
              globalGroups: [
                {
                  key: 0,
                  name: "开屏广告-通用跳过按钮",
                  desc: "匹配常见应用开屏页右上角「跳过」「跳过广告」「5s 跳过」按钮（5 秒窗口，零日常开销）",
                  enable: true,
                  fastQuery: true,
                  matchTime: 8000,
                  actionMaximum: 1,
                  actionCd: 1500,
                  resetMatch: "app",
                  matchAnyApp: true,
                  matchSystemApp: false,
                  matchLauncher: false,
                  rules: [
                    {
                      key: 0,
                      matches: '[text*="跳过"][text.length<=10][visibleToUser=true]',
                      excludeMatches: '[text*="跳过此步" || text*="跳过登录" || text*="跳过绑定"]'
                    },
                    {
                      key: 1,
                      matches: '[desc*="跳过"][desc.length<=10][visibleToUser=true]'
                    }
                  ]
                }
              ],
              apps: [
                {
                  id: "com.baidu.netdisk.samsung",
                  name: "百度网盘（三星版）",
                  groups: [
                    {
                      key: 0,
                      name: "开屏广告",
                      fastQuery: true,
                      matchTime: 10000,
                      actionMaximum: 2,
                      resetMatch: "app",
                      rules: [
                        {
                          key: 0,
                          activityIds: [
                            "com.baidu.netdisk.advertise.ui.SplashAdActivity",
                            "com.baidu.netdisk.ui.MainActivity",
                            "com.baidu.netdisk.ui.Navigate"
                          ],
                          anyMatches: [
                            '[text*="跳过"][text.length<=10][visibleToUser=true]',
                            '[vid*="skip"][visibleToUser=true]',
                            '[desc*="跳过"][visibleToUser=true]'
                          ]
                        }
                      ]
                    }
                  ]
                },
                {
                  id: "com.baidu.netdisk",
                  name: "百度网盘",
                  groups: [
                    {
                      key: 0,
                      name: "开屏广告",
                      fastQuery: true,
                      matchTime: 10000,
                      actionMaximum: 2,
                      resetMatch: "app",
                      rules: [
                        {
                          key: 0,
                          anyMatches: [
                            '[text*="跳过"][text.length<=10][visibleToUser=true]',
                            '[vid*="skip"][visibleToUser=true]'
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private val gkdDao = database.gkdDao()

    val subscriptionsFlow: Flow<List<GkdSubscriptionEntity>> = gkdDao.observeSubscriptions()
    val groupConfigsFlow: Flow<List<GkdGroupConfigEntity>> = gkdDao.observeGroupConfigs()
    val actionLogsFlow: Flow<List<GkdActionLogEntity>> = gkdDao.observeActionLogs(200)

    private val _compiledRegistry = MutableStateFlow(
        CompiledGkdRegistry(emptyList(), emptyList(), emptyMap())
    )
    val compiledRegistry: StateFlow<CompiledGkdRegistry> = _compiledRegistry.asStateFlow()

    suspend fun ensureBuiltinSeedAndReload() = withContext(Dispatchers.IO) {
        if (gkdDao.countSubscriptions() == 0) {
            importRawJson5(BUILTIN_DEFAULT_SUBSCRIPTION_JSON5, sourceUrl = null)
        } else {
            reloadCompiledRegistry()
        }
    }

    suspend fun importFromUrl(url: String): Result<GkdSubscriptionEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = url.trim()
            require(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://")) {
                "订阅地址必须以 http:// 或 https:// 开头"
            }
            val raw = fetchUrlText(cleanUrl)
            importRawJson5(raw, sourceUrl = cleanUrl).getOrThrow()
        }
    }

    suspend fun importRawJson5(rawJson5: String, sourceUrl: String? = null): Result<GkdSubscriptionEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = GkdSubscriptionParser.parse(rawJson5)
                val entity = GkdSubscriptionEntity(
                    subId = parsed.id,
                    name = parsed.name,
                    version = parsed.version,
                    author = parsed.author,
                    updateUrl = sourceUrl ?: parsed.updateUrl,
                    rawJson5 = rawJson5,
                    isEnabled = true,
                    appCount = parsed.apps.size,
                    globalGroupCount = parsed.globalGroups.size,
                    ruleCount = parsed.totalRuleCount,
                    updatedAtMs = System.currentTimeMillis()
                )
                gkdDao.upsertSubscription(entity)
                reloadCompiledRegistry()
                entity
            }
        }

    suspend fun updateAllRemoteSubscriptions(): String = withContext(Dispatchers.IO) {
        val subs = gkdDao.getAllSubscriptionsOnce().filter { !it.updateUrl.isNullOrBlank() }
        if (subs.isEmpty()) return@withContext "当前没有可在线更新的远程订阅"
        var okCount = 0
        var failCount = 0
        for (sub in subs) {
            val res = importFromUrl(sub.updateUrl!!)
            if (res.isSuccess) okCount++ else failCount++
        }
        "远程订阅更新完成：成功 $okCount 个，失败 $failCount 个"
    }

    suspend fun setSubscriptionEnabled(subId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        gkdDao.setSubscriptionEnabled(subId, enabled)
        reloadCompiledRegistry()
    }

    suspend fun deleteSubscription(subId: Long) = withContext(Dispatchers.IO) {
        gkdDao.deleteSubscription(subId)
        gkdDao.deleteGroupConfigsForSub(subId)
        reloadCompiledRegistry()
    }

    suspend fun setGroupEnabled(subId: Long, appId: String, groupKey: Int, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            gkdDao.upsertGroupConfig(GkdGroupConfigEntity(subId, appId, groupKey, enabled))
            reloadCompiledRegistry()
        }

    suspend fun recordActionLog(log: GkdActionLogEntity) = withContext(Dispatchers.IO) {
        gkdDao.insertActionLog(log)
    }

    suspend fun clearActionLogs() = withContext(Dispatchers.IO) {
        gkdDao.clearAllActionLogs()
    }

    suspend fun reloadCompiledRegistry(): CompiledGkdRegistry = withContext(Dispatchers.IO) {
        val entities = gkdDao.getAllSubscriptionsOnce()
        val configMap = gkdDao.getAllGroupConfigsOnce()
            .associateBy { Triple(it.subId, it.appId, it.groupKey) }

        val parsedSubs = mutableListOf<GkdSubscription>()
        val enabledGlobals = mutableListOf<GkdRuleGroup>()
        val enabledByPkg = LinkedHashMap<String, MutableList<GkdRuleGroup>>()

        for (entity in entities) {
            val parsed = runCatching { GkdSubscriptionParser.parse(entity.rawJson5, entity.subId) }.getOrNull()
                ?: continue
            parsedSubs.add(parsed)
            if (!entity.isEnabled) continue

            for (gg in parsed.globalGroups) {
                val cfg = configMap[Triple(entity.subId, "*", gg.key)]
                val active = cfg?.enabled ?: gg.enableByDefault
                if (active && gg.rules.isNotEmpty()) {
                    enabledGlobals.add(gg)
                }
            }

            for ((appId, appSub) in parsed.apps) {
                for (ag in appSub.groups) {
                    val cfg = configMap[Triple(entity.subId, appId, ag.key)]
                    val active = cfg?.enabled ?: ag.enableByDefault
                    if (active && ag.rules.isNotEmpty()) {
                        enabledByPkg.getOrPut(appId) { mutableListOf() }.add(ag)
                    }
                }
            }
        }

        val registry = CompiledGkdRegistry(
            subscriptions = parsedSubs,
            enabledGlobalGroups = enabledGlobals,
            enabledAppGroupsByPkg = enabledByPkg
        )
        _compiledRegistry.value = registry
        registry
    }

    private fun fetchUrlText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NetControl-GKD/2.0")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP 请求失败 ($code)" }
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
