package com.netcontrol.data.scanner

import android.content.pm.PackageInfo
import java.security.MessageDigest

/**
 * 计算应用 APK 签名证书的确定性 SHA-256 指纹，用于识别同名应用重装后签名是否被替换。
 */
object SignatureUtils {

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    /**
     * 对一组证书字节计算与顺序无关的 64 位小写 SHA-256 指纹：
     * - 单证书直接返回其 SHA-256；
     * - 多证书先各自哈希、字典序排序拼接后再做一次 SHA-256，保证定长且顺序无关。
     */
    fun computeFingerprint(certByteArrays: List<ByteArray>): String {
        val nonEmpty = certByteArrays.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""
        val sortedHashes = nonEmpty.map { sha256Hex(it) }.sorted()
        return if (sortedHashes.size == 1) {
            sortedHashes.first()
        } else {
            sha256Hex(sortedHashes.joinToString(":").toByteArray(Charsets.UTF_8))
        }
    }

    fun extractPackageSignatureSha256(packageInfo: PackageInfo): String {
        val signingInfo = packageInfo.signingInfo ?: return ""
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
        }
        if (signers.isNullOrEmpty()) return ""
        return computeFingerprint(signers.map { it.toByteArray() })
    }
}
