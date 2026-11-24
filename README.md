# NetControl

**免 Root Android 应用级内核防火墙 + GKD 跳过广告双擎一体化工具**

专为现代 Android（Android 12–16 / API 31–36，已在 **Samsung Galaxy S25+ / One UI 8.5** 真机深度实测）打造的轻量、零转发耗电、与 Clash / v2ray 等代理软件完美共存的应用联网控制与开屏/弹窗跳过应用。

---

## 核心特性

### 1. 双引擎无 Root 拦截（与代理完美共存）
- **Shizuku 内核主引擎（首选）**：通过常驻 AIDL `UserService` 直接操作 Android 内核 `FIREWALL_CHAIN_OEM_DENY_3`（`cmd connectivity set-chain3-enabled` / `set-package-networking-enabled`），**不占用系统 VPN 槽位、零流量转发开销**，自带 `chain3` 内核链自愈激活机制与 UID Diff 增量批处理下发；同时提供 `netpolicy` 备选驱动。
- **轻量黑洞 VPN 兜底引擎**：在无 Shizuku 环境下按需启用，仅通过 `VpnService.Builder.addAllowedApplication(blockedPkg)` 精准隔离被阻断应用，零流量转发、自动检测第三方 VPN 冲突；Shizuku 断连/重启时通过系统通知支持一键切换兜底。
- **防自锁与一键急救**：永久豁免 NetControl 自身、Shizuku 及核心系统 UID（`< 10000`），提供随时可触发的「一键急救」原子卸载 `chain3` 并恢复全机联网。

### 2. 三星「模式与日常程序」风格首页与 P0~P5 确定性裁决引擎
- **One UI 风格模式卡片**：把自定义分组直接以圆角模式卡片呈现在首页，支持一键**手动启动/关闭**、点击卡片自由设计**自动启动条件**（按网络档案、按时间段），并可单击顶部防火墙总闸面板直达当前「已阻断应用」列表。
- **多对多自定义分组与定时模块库**：支持 15 种图标、12 种配色、8 套快捷模板、排序与批量带图标成员勾选；内置可自由新建、命名、编辑与一键套用的 **自定义定时模块库**（支持跨午夜时段如 `23:00 → 次日 07:00` 与星期循环）。
- **单点链式精准定时**：基于 `ScheduleCalculator` + `AlarmManager`（`setExactAndAllowWhileIdle` / 可选 `setAlarmClock`），Doze 休眠零轮询保活，开机/改时/切时区自动重建排程。
- **网络档案与双 SIM 识别**：支持受信任 Wi-Fi（多 BSSID / Mesh 映射，优先经 Shizuku Shell 免定位读取）、未知 Wi-Fi 默认拦截与优雅降级、双 SIM 卡槽独立策略。

### 3. 双擎广告跳过（10ms 开屏断网 + 完整内置 GKD 引擎）
- **毫秒级开屏断网**：无障碍状态机具备五重防误触、桌面冷启动与二级开屏页捕获、微信/支付宝过渡白名单，以及 `< 15ms` Shizuku 独立快通道。
- **完整 GKD 跳过引擎**：纯 Kotlin 实现 GKD 高级选择器 AST 与匹配引擎（支持 `@` 目标重定向、`>` / `<` / `<<` / `->` / `+` / `-` 步长范围、`fastQuery`、正则/逻辑组合）、JSON5 订阅解析与 HTTPS 远程订阅更新、`activityIds` / `preKeys` / `actionCd` / `actionMaximum` / `position` 状态机、`ACTION_CLICK` + 手势模拟点击，以及 **3 秒延时屏幕节点快照抓拍与离线选择器调试器**。

### 4. 零耗电统计、安全审计与加密备份
- **零耗电流量统计**：基于系统 `NetworkStatsManager` 按日/周/月聚合展示排行与趋势图表。
- **应用签名安全守护**：全量扫描自动记录 APK SHA-256 签名指纹，重装签名不一致时自动隔离并告警。
- **7 天审计日志与无障碍轨迹**：独立二级页面查看与筛选，权限设置页清爽不冗长。
- **`.ncbk` 密码加密备份与恢复预览**：基于 SAF + `PBKDF2-HMAC-SHA256`（310,000 次迭代）+ `AES-256-GCM`（全头 AAD 认证），恢复前支持完整预览并隔离异常签名应用。

---

## 技术栈与架构

- **语言与构建**：Kotlin 2.2.10 · Jetpack Compose (Material 3) · Gradle 9.6.0 · AGP 9.4.1 · KSP 2.3.6
- **数据层**：Room 2.7.1（14 张业务表，v1→v4 无损迁移与 Schema 导出）· DataStore Preferences 1.1.7
- **特权通信**：Shizuku API 13.1.5（AIDL `IFirewallService`）
- **系统目标**：`minSdk = 31`（Android 12）· `compileSdk = 36.1` · `targetSdk = 36`（Android 16）

```text
app/src/main/java/com/netcontrol/
├── core/
│   ├── accessibility/   # 双擎无障碍服务与开屏断网防误触状态机
│   ├── alarm/           # 单点链式精准定时调度与 Doze 唤醒保活
│   ├── backend/         # Shizuku UserService + 黑洞 VPN + 双引擎协调器
│   ├── engine/          # P0~P5 确定性规则裁决器、UID Diff 与 ScheduleCalculator
│   ├── gkd/             # 完整 GKD 高级选择器、JSON5 订阅仓库、运行时与快照抓拍
│   ├── network/         # Wi-Fi BSSID / 双 SIM 监听与网络档案解析
│   ├── notification/    # Shizuku 断连/重启一键切黑洞兜底通知
│   └── receiver/        # 包变更、开机、时间/时区变更系统广播接收器
├── data/
│   ├── backup/          # PBKDF2 + AES-256-GCM (.ncbk) 加密备份与恢复预览
│   ├── local/           # Room 14 表实体、DAO 与 v1->v4 平滑迁移
│   ├── prefs/           # DataStore 全局设置
│   ├── repository/      # 统一数据仓库
│   ├── scanner/         # 全量应用扫描与 SHA-256 签名防篡改比对
│   └── stats/           # NetworkStatsManager 零耗电流量采集聚合
└── ui/                  # One UI 模式首页、轻量 120Hz 应用列表、分组/定时模块、网络、统计与设置、GKD 中心
```

---

## 构建说明

> 需要 **JDK 17+**（推荐使用 Android Studio 自带的 JBR）与 **Android SDK Platform 36.1**。

```bash
# 运行全部 JVM 单元测试（54 项）
./gradlew testDebugUnitTest

# 构建开发调试包
./gradlew assembleDebug

# 构建 R8 极速正式优化包（同 debug 包名与签名，约 3.1 MB，120Hz 满帧）
./gradlew assembleFast

# 构建未签名 Release 发布包
./gradlew assembleRelease
```

### 推送到真机（推荐三星/多用户设备显式指定 `--user 0`）

```bash
adb install --user 0 -r app/build/outputs/apk/fast/app-fast.apk
# 可选：触发 ART 全量 AOT 机器码预编译以获得最佳 120Hz 流畅度
adb shell cmd package compile -m speed -f com.netcontrol.debug
```

---

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
