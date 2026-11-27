package com.handykit.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 安全笔记加密核心。
 *
 * 结构（K2/K3）：
 *  - 主口令 --PBKDF2WithHmacSHA256(随机盐, 迭代)--> KEK（256-bit），不落盘
 *  - 随机数据密钥 DEK（256-bit），独立于主口令、备份口令、AI 凭据
 *  - DEK 用 KEK 封装后落盘（wrappedDek）；改口令只需重新封装 DEK，不重加密数据
 *  - 每条笔记字段用 DEK + 每次随机 12 字节 nonce 做 AES-256-GCM，认证失败抛异常拒绝解密
 *
 * 会话内 DEK 由 [VaultSession] 持有，锁定即清零；所有解密都要经过有效会话检查。
 */
object VaultCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEK_ALIAS_PREFIX = "hk_note_kek_"
    private const val AI_CRED_ALIAS_PREFIX = "hk_ai_cred_"
    private const val BACKUP_ALIAS_PREFIX = "hk_backup_"

    const val PBKDF2_ITERATIONS = 210_000
    const val KEY_BITS = 256
    const val GCM_BITS = 128
    const val NONCE_BYTES = 12
    const val SALT_BYTES = 16

    private val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** 主口令 → KEK。无 keystore 依赖，纯 PBKDF2 派生。 */
    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSha256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** AES-GCM 加密，返回 "nonce|ciphertext" 的 base64 串。 */
    fun encrypt(plain: ByteArray, key: SecretKey): String {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_BITS, nonce))
        val ct = cipher.doFinal(plain)
        val out = ByteArray(nonce.size + ct.size)
        System.arraycopy(nonce, 0, out, 0, nonce.size)
        System.arraycopy(ct, 0, out, nonce.size, ct.size)
        return b64(out)
    }

    /** AES-GCM 解密。认证失败会抛 AEADBadTagException，调用方必须拒绝展示。 */
    fun decrypt(payload: String, key: SecretKey): ByteArray {
        val raw = unb64(payload)
        require(raw.size > NONCE_BYTES) { "密文长度非法" }
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val ct = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_BITS, nonce))
        return cipher.doFinal(ct)
    }

    fun newDek(): SecretKey = SecretKeySpec(randomBytes(32), "AES")

    fun wrapDek(dek: SecretKey, kek: SecretKey): String = encrypt(dek.encoded, kek)

    fun unwrapDek(wrapped: String, kek: SecretKey): SecretKey =
        SecretKeySpec(decrypt(wrapped, kek), "AES")

    // ---- Keystore：保存与设备绑定、独立用途的密钥 ----

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureAesKey(alias: String, requireAuth: Boolean, authDurationSec: Int): SecretKey {
        val ks = keystore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (requireAuth) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationValidityDurationSeconds(authDurationSec)
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    /** 生物识别约束的 KEK：认证必须真正约束密钥使用（K4/K6）。 */
    fun biometricKek(scope: String): SecretKey =
        ensureAesKey("$KEK_ALIAS_PREFIX$scope", requireAuth = true, authDurationSec = 30)

    /** AI 凭据专用设备密钥，不与安全笔记解锁绑定（A10）。 */
    fun aiCredentialKey(): SecretKey = ensureAesKey(AI_CRED_ALIAS_PREFIX + "default", false, 0)

    /** 备份密钥（K3：独立于笔记主口令）。 */
    fun backupKey(scope: String): SecretKey =
        ensureAesKey("$BACKUP_ALIAS_PREFIX$scope", false, 0)

    fun hasKey(alias: String): Boolean = keystore().containsAlias(alias)

    fun deleteKey(alias: String) {
        keystore().deleteEntry(alias)
    }

    /** 用 Keystore 中的非导出密钥封装 DEK，落盘；解密时需 Keystore 可用。 */
    fun wrapWithKeystore(plain: ByteArray, key: SecretKey): String = encrypt(plain, key)
    fun unwrapWithKeystore(payload: String, key: SecretKey): ByteArray = decrypt(payload, key)

    fun secondsToLive(): Long = TimeUnit.SECONDS.toMillis(30)
}
