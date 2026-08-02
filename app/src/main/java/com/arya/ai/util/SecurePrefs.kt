package com.arya.ai.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Central place to get an encrypted SharedPreferences file (AES256-GCM values,
 * AES256-SIV keys), backed by a Keystore-protected master key.
 *
 * Used for anything that used to be a plain `context.getSharedPreferences(...)` call
 * and holds sensitive data: API keys, remembered facts (`remember`/`recall`), and
 * saved personas. Falls back to a plain (unencrypted) file only if the Keystore
 * operation itself fails (e.g. some very old / broken OEM keystores) — better to
 * keep the app working than to hard-crash on a device with a flaky Keystore.
 */
object SecurePrefs {

    private val cache = mutableMapOf<String, SharedPreferences>()

    @Synchronized
    fun get(context: Context, fileName: String): SharedPreferences {
        cache[fileName]?.let { return it }

        val prefs = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore unavailable/corrupted on this device — degrade gracefully
            // instead of crashing the app.
            context.applicationContext.getSharedPreferences("${fileName}_fallback_unencrypted", Context.MODE_PRIVATE)
        }

        cache[fileName] = prefs
        return prefs
    }
}
