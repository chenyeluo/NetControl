package com.netcontrol.core.shizuku;

interface IFirewallService {
    void destroy() = 16777114;

    int getUid() = 1;

    /**
     * 运行阶段 0 诊断探针：检测当前固件下 chain3 / netpolicy / cmd wifi status 的支持情况，
     * 返回以换行分隔的 key=value 诊断文本。
     */
    String probeEnvironment(String samplePackage) = 2;

    /**
     * 设置底层驱动："connectivity_chain3" 或 "netpolicy_restrict"
     */
    boolean setDriver(String driverName) = 3;

    String getActiveDriver() = 4;

    boolean ensureFirewallEnabled() = 5;

    boolean setPackageBlocked(String packageName, int uid, boolean blocked) = 6;

    int applyBatchBlocked(in String[] packages, in int[] uids, in boolean[] blockedFlags) = 7;

    boolean isPackageBlocked(String packageName, int uid) = 8;

    /**
     * 通过 Shell 读取当前 Wi-Fi 的 SSID / BSSID 摘要（无需定位权限）。
     */
    String getCurrentWifiSummary() = 9;

    boolean clearAllBlocked(in String[] packages, in int[] uids) = 10;

    /**
     * 原子关闭内核 chain3 防火墙链（<10ms 立即恢复全机联网），作为关闭总开关与急救按钮的第一防线。
     */
    boolean disableFirewallChain() = 11;
}
