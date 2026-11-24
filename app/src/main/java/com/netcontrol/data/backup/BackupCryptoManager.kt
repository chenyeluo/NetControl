package com.netcontrol.data.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 强加密备份引擎（NCBK v1 文件格式）：
 * - KDF：PBKDF2WithHmacSHA256（默认 310,000 次迭代，每次备份独立 16 字节随机 Salt）
 * - 认证加密：AES-256-GCM（每次备份独立 12 字节随机 Nonce + 128 位认证标签）
 * - 完整文件头（Magic + Version + KDF + Iterations + Salt + Nonce）全部作为 GCM AAD 参与认证，
 *   密码错误或文件任意 1 字节被篡改都会在解密阶段直接拒绝导入。
 */
object BackupCryptoManager {

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    const val DEFAULT_ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES

    private val secureRandom = SecureRandom()

    fun encryptPayload(
        payload: BackupPayload,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        if (password.isEmpty()) {
            throw BackupCryptoException("备份密码不能为空")
        }
        val plaintext = BackupPayloadCodec.encode(payload)
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }

        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(FORMAT_VERSION)
            .put(KDF_PBKDF2_SHA256)
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()

        val keyBytes = deriveAes256Key(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)

            ByteArrayOutputStream(header.size + ciphertext.size).apply {
                write(header)
                write(ciphertext)
            }.toByteArray()
        } catch (t: Throwable) {
            throw BackupCryptoException("加密备份失败: ${t.message}", t)
        } finally {
            keyBytes.fill(0)
        }
    }

    fun decryptPayload(
        encryptedBytes: ByteArray,
        password: CharArray
    ): BackupPayload {
        if (password.isEmpty()) {
            throw BackupCryptoException("请输入备份密码")
        }
        if (encryptedBytes.size <= HEADER_BYTES + 16) {
            throw BackupCryptoException("备份文件格式无效或已截断")
        }

        val header = encryptedBytes.copyOfRange(0, HEADER_BYTES)
        val buf = ByteBuffer.wrap(header)

        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) {
            throw BackupCryptoException("不是有效的 NetControl 加密备份文件 (.ncbk)")
        }

        val version = buf.get()
        if (version != FORMAT_VERSION) {
            throw BackupCryptoException("不支持的备份文件格式版本: $version")
        }

        val kdfId = buf.get()
        if (kdfId != KDF_PBKDF2_SHA256) {
            throw BackupCryptoException("不支持的密钥派生算法标识: $kdfId")
        }

        val iterations = buf.int
        if (iterations !in 10_000..2_000_000) {
            throw BackupCryptoException("异常的密码派生迭代参数: $iterations")
        }

        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buf.get(it) }
        val ciphertext = encryptedBytes.copyOfRange(HEADER_BYTES, encryptedBytes.size)

        val keyBytes = deriveAes256Key(password, salt, iterations)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (badTag: AEADBadTagException) {
            throw BackupCryptoException("密码错误或备份文件已被篡改（认证校验失败）", badTag)
        } catch (t: Throwable) {
            throw BackupCryptoException("解密备份失败: ${t.message}", t)
        } finally {
            keyBytes.fill(0)
        }

        return try {
            BackupPayloadCodec.decode(plaintext)
        } catch (t: Throwable) {
            throw BackupCryptoException("解析备份数据失败: ${t.message}", t)
        }
    }

    private fun deriveAes256Key(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
