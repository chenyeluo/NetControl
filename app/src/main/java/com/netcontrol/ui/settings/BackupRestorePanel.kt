package com.netcontrol.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.netcontrol.data.backup.BackupManager
import com.netcontrol.data.backup.BackupRestorePreview
import com.netcontrol.data.backup.BackupType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupRestorePanel(
    backupManager: BackupManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf(BackupType.FULL) }
    var includeAuditLogs by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // 导出流程状态
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf("") }

    // 恢复流程状态
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreview by remember { mutableStateOf<BackupRestorePreview?>(null) }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExportPassword.isNotEmpty()) {
            val pwdChars = pendingExportPassword.toCharArray()
            pendingExportPassword = ""
            scope.launch {
                isBusy = true
                val report = backupManager.exportEncryptedBackup(
                    targetUri = uri,
                    backupType = selectedType,
                    includeAuditLogs = includeAuditLogs,
                    password = pwdChars
                )
                statusMessage = report.message
                isBusy = false
            }
        } else {
            pendingExportPassword = ""
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
        }
    }

    if (showExportPasswordDialog) {
        var pwd1 by remember { mutableStateOf("") }
        var pwd2 by remember { mutableStateOf("") }
        val mismatch = pwd2.isNotEmpty() && pwd1 != pwd2
        val valid = pwd1.length >= 6 && pwd1 == pwd2

        AlertDialog(
            onDismissRequest = { showExportPasswordDialog = false },
            title = { Text("设置加密备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "备份文件使用 PBKDF2-HMAC-SHA256 (310,000 次迭代) + AES-256-GCM 认证加密，本地不保存明文密码，遗忘将无法恢复。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pwd1,
                        onValueChange = { pwd1 = it },
                        label = { Text("备份密码（至少 6 位）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pwd2,
                        onValueChange = { pwd2 = it },
                        label = { Text("再次确认密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = mismatch,
                        supportingText = { if (mismatch) Text("两次输入的密码不一致") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        pendingExportPassword = pwd1
                        showExportPasswordDialog = false
                        val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        exportFileLauncher.launch("netcontrol_${selectedType.name.lowercase()}_$dateTag.ncbk")
                    }
                ) { Text("选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) { Text("取消") }
            }
        )
    }

    pendingRestoreUri?.let { uri ->
        var pwd by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("输入密码验证并解密备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将先验证文件头与 AES-256-GCM 认证标签，解密后展示导入预览，确认前不会改动现有规则。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it; errorText = null },
                        label = { Text("备份密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorText?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pwd.isNotEmpty() && !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val result = backupManager.decryptAndPreviewBackup(uri, pwd.toCharArray())
                            isBusy = false
                            result.onSuccess { preview ->
                                pendingRestoreUri = null
                                restorePreview = preview
                            }.onFailure { err ->
                                errorText = err.message ?: "解密校验失败"
                            }
                        }
                    }
                ) { Text("验证并预览") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("取消") }
            }
        )
    }

    restorePreview?.let { preview ->
        val p = preview.payload
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

        AlertDialog(
            onDismissRequest = { restorePreview = null },
            title = { Text("导入前安全预览确认") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = buildString {
                            appendLine("类型：${p.backupType.label}")
                            appendLine("来源设备：${p.sourceDevice}")
                            appendLine("备份时间：${fmt.format(Date(p.createdAtMs))}")
                            appendLine("包含：${p.groups.size} 分组 · ${p.scheduleRules.size} 定时 · ${p.networkProfiles.size} 网络档案 · ${p.bssidMappings.size} BSSID · ${p.trafficBuckets.size} 流量桶")
                            append("匹配可安全恢复的应用规则：${preview.matchedInstalledApps.size} 个")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    if (preview.uninstalledBackupApps.isNotEmpty()) {
                        Text(
                            "本机未安装的备份应用：${preview.uninstalledBackupApps.size} 个（已自动跳过其规则恢复）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (preview.signatureMismatchApps.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "安全隔离警告：发现 ${preview.signatureMismatchApps.size} 个应用签名不一致！",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "为防假冒重装应用继承旧放行白名单，以下应用将被跳过不恢复其联网规则：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                preview.signatureMismatchApps.forEach { m ->
                                    Text(
                                        "• ${m.appName} (${m.packageName})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val report = backupManager.commitRestore(preview)
                            isBusy = false
                            statusMessage = report.message
                            restorePreview = null
                        }
                    }
                ) { Text("确认导入并重算规则") }
            },
            dismissButton = {
                TextButton(onClick = { restorePreview = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本地加密导出（.ncbk）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "数据 100% 仅保存在本地，不注册账号、不上传任何云端服务器。默认强制密码加密（PBKDF2-SHA256 + AES-256-GCM + AAD 防篡改）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("选择备份内容范围：", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackupType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.label) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeAuditLogs, onCheckedChange = { includeAuditLogs = it })
                    Text("附带最近 7 天拦截审计日志", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    enabled = !isBusy,
                    onClick = { showExportPasswordDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("设置密码并导出加密备份") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("从加密备份安全恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "恢复前会先校验文件完整性、展示导入数量预览，并自动比对已安装应用的 SHA-256 签名指纹，拒绝让签名变更的同名应用继承旧白名单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    enabled = !isBusy,
                    onClick = { openBackupLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择 .ncbk 备份文件并验证恢复") }
            }
        }

        statusMessage?.let { msg ->
            HorizontalDivider()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
