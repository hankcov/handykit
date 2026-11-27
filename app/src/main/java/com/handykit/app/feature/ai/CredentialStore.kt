package com.handykit.app.feature.ai

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.handykit.app.core.crypto.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.crypto.SecretKey

/** 独立凭据区，不与设置 DataStore 共用，便于单独备份/清理策略。 */
private val Context.aiCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_credentials",
)

private val FIRST_USE_SEEN = booleanPreferencesKey("ai_first_use_seen")

private fun apiKeyPref(providerId: String) = stringPreferencesKey("ai_key_$providerId")
private fun headersPref(providerId: String) = stringPreferencesKey("ai_headers_$providerId")

/** 自定义请求头条目。明文只在内存中存在，不写入日志、不导出。 */
@Serializable
data class AiHeaderEntry(val key: String, val value: String)

/** 明文查看的准入结果。无本机验证方式时只能替换，不能显示。 */
sealed interface RevealGate {
    data object NoLock : RevealGate
    data class NeedAuth(val intent: android.content.Intent) : RevealGate
}

/**
 * AI 服务商凭据区。
 *
 * - API Key 与自定义请求头用 [VaultCrypto.aiCredentialKey]（设备绑定、独立于安全笔记）加密后存 DataStore；
 *   绝不写 Room、不写日志、不提供明文导出接口。
 * - 网络请求需要明文时走 [apiKeyForRequest]/[headersForRequest]，只在进程内存中短暂存在。
 * - UI 查看明文必须先用 KeyguardManager 验证本机身份（[revealGate] + [revealApiKey]/[revealHeaders]）。
 */
class CredentialStore(private val context: Context) {

    private val appContext = context.applicationContext
    private val ds: DataStore<Preferences> = appContext.aiCredentialDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val key: SecretKey by lazy { VaultCrypto.aiCredentialKey() }

    val firstUseSeen: Flow<Boolean> = ds.data.map { it[FIRST_USE_SEEN] ?: false }

    suspend fun setFirstUseSeen() = withContext(Dispatchers.IO) {
        ds.edit { it[FIRST_USE_SEEN] = true }
    }

    suspend fun hasApiKey(providerId: String): Boolean = withContext(Dispatchers.IO) {
        !ds.data.first()[apiKeyPref(providerId)].isNullOrBlank()
    }

    /** 保存/替换 API Key。空白值表示删除，避免留下空密文。 */
    suspend fun saveApiKey(providerId: String, apiKey: String) = withContext(Dispatchers.IO) {
        val pref = apiKeyPref(providerId)
        ds.edit { p ->
            if (apiKey.isBlank()) {
                p.remove(pref)
            } else {
                p[pref] = VaultCrypto.encrypt(apiKey.trim().toByteArray(Charsets.UTF_8), key)
            }
        }
    }

    /** 自定义请求头整包替换；解析失败返回空列表，不抛异常到 UI。 */
    suspend fun saveHeaders(providerId: String, headers: List<AiHeaderEntry>) =
        withContext(Dispatchers.IO) {
            val pref = headersPref(providerId)
            val valid = headers.filter { it.key.isNotBlank() && it.key !in RESERVED_HEADERS }
            ds.edit { p ->
                if (valid.isEmpty()) {
                    p.remove(pref)
                } else {
                    p[pref] = VaultCrypto.encrypt(
                        json.encodeToString(valid).toByteArray(Charsets.UTF_8),
                        key,
                    )
                }
            }
        }

    suspend fun delete(providerId: String) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            p.remove(apiKeyPref(providerId))
            p.remove(headersPref(providerId))
        }
    }

    /** 仅供构建网络请求使用；解密失败视为未配置，不向调用方泄漏原因。 */
    suspend fun apiKeyForRequest(providerId: String): String = withContext(Dispatchers.IO) {
        val payload = ds.data.first()[apiKeyPref(providerId)] ?: return@withContext ""
        runCatching {
            String(VaultCrypto.decrypt(payload, key), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** 仅供构建网络请求使用；非法/保留头名在此再次过滤。 */
    suspend fun headersForRequest(providerId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val payload = ds.data.first()[headersPref(providerId)] ?: return@withContext emptyMap()
            val entries = runCatching {
                json.decodeFromString<List<AiHeaderEntry>>(
                    String(VaultCrypto.decrypt(payload, key), Charsets.UTF_8),
                )
            }.getOrDefault(emptyList())
            entries.associate { it.key.trim() to it.value }
                .filterKeys { it.isNotBlank() && it !in RESERVED_HEADERS }
        }

    /**
     * 检查本机是否有可用的验证方式。无锁屏/无生物识别时返回 [RevealGate.NoLock]，
     * UI 必须禁用明文查看，但仍允许直接输入新值替换。
     */
    fun revealGate(activity: Activity): RevealGate {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null || !km.isKeyguardSecure) return RevealGate.NoLock
        val intent = km.createConfirmDeviceCredentialIntent(
            "验证身份",
            "查看 AI 凭据明文前需要解锁本机",
        ) ?: return RevealGate.NoLock
        return RevealGate.NeedAuth(intent)
    }

    /** 仅在调用方已完成 [revealGate] 验证且拿到 RESULT_OK 后调用。 */
    suspend fun revealApiKey(providerId: String): String = withContext(Dispatchers.IO) {
        apiKeyForRequest(providerId)
    }

    /** 仅在调用方已完成 [revealGate] 验证且拿到 RESULT_OK 后调用。 */
    suspend fun revealHeaders(providerId: String): List<AiHeaderEntry> =
        withContext(Dispatchers.IO) {
            val payload = ds.data.first()[headersPref(providerId)] ?: return@withContext emptyList()
            runCatching {
                json.decodeFromString<List<AiHeaderEntry>>(
                    String(VaultCrypto.decrypt(payload, key), Charsets.UTF_8),
                )
            }.getOrDefault(emptyList())
        }

    companion object {
        /** 不允许覆盖这些头，避免破坏鉴权/分块/主机路由。 */
        val RESERVED_HEADERS = setOf(
            "authorization", "content-type", "host", "content-length", "connection",
        )
    }
}

/** 保留头名的大小写无关判断，供 UI 即时提示。 */
fun isReservedHeaderName(name: String): Boolean =
    name.trim().lowercase() in CredentialStore.RESERVED_HEADERS
