# ==============================================================================
# NetControl R8 / ProGuard Release Hardening Rules
# ==============================================================================

# 1. Shizuku UserService & AIDL IPC (instantiated reflectively by Shizuku server)
-keep class com.netcontrol.core.backend.shizuku.FirewallUserService { *; }
-keep class com.netcontrol.core.shizuku.IFirewallService { *; }
-keep class com.netcontrol.core.shizuku.IFirewallService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 2. Android System Components (Services & BroadcastReceivers)
-keep class com.netcontrol.core.backend.vpn.BlackholeVpnService { *; }
-keep class com.netcontrol.core.accessibility.SplashBlockService { *; }
-keep class com.netcontrol.core.alarm.AlarmReceiver { *; }
-keep class com.netcontrol.core.receiver.PackageChangeReceiver { *; }
-keep class com.netcontrol.core.receiver.SystemEventReceiver { *; }

# 3. Room Entities, DAOs, and Database
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.netcontrol.data.local.entity.** { *; }
-keep class com.netcontrol.core.gkd.** { *; }

# 4. Enum values used by valueOf() in DataStore & BackupPayloadCodec
-keepclassmembers enum com.netcontrol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 5. Keep stack trace line numbers for clean crash diagnostics
-keepattributes SourceFile,LineNumberTable
