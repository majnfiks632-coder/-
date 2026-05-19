package com.aiagent.android.kiro.keys

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Зашифрованное хранилище ключей. Использует
 * [androidx.security.crypto.EncryptedSharedPreferences] поверх
 * Android Keystore — ключ шифрования сидит в Hardware-Backed
 * KeyStore (если устройство поддерживает) и не покидает железо.
 *
 * Один файл на провайдера: `keys_kiro.dat`, `keys_soniox.dat` и т.д.
 * Это упрощает миграции и точечное удаление.
 *
 * Все операции suspend и выполняются на [Dispatchers.IO] — обращение
 * к Keystore синхронное и может занять десятки миллисекунд.
 */
class KeyStorage(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun prefsFor(provider: ProviderType): SharedPreferences = withContext(Dispatchers.IO) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "keys_${provider.name.lowercase()}",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun load(provider: ProviderType): List<KeyEntry> = withContext(Dispatchers.IO) {
        val prefs = prefsFor(provider)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<KeyEntry>>(raw) }
            .getOrElse { emptyList() }
    }

    suspend fun save(provider: ProviderType, entries: List<KeyEntry>) = withContext(Dispatchers.IO) {
        val prefs = prefsFor(provider)
        val raw = json.encodeToString(entries)
        prefs.edit().putString(KEY_ENTRIES, raw).apply()
    }

    suspend fun clear(provider: ProviderType) = withContext(Dispatchers.IO) {
        prefsFor(provider).edit().remove(KEY_ENTRIES).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries_v1"
    }
}
