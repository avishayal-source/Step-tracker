package com.steptracker.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-256 encrypted SharedPreferences for health-related data (coach profile,
 * training plans). Falls back to plain MODE_PRIVATE if the keystore is unavailable
 * (rare emulators / broken devices) so the app still works.
 *
 * On first open, copies any legacy plain-text prefs into the encrypted store and
 * clears the old file so data isn't left readable in two places.
 */
object SecurePrefs {

    fun open(context: Context, name: String): SharedPreferences {
        val encrypted = createEncrypted(context, name)
        migrateFromPlain(context, name, encrypted)
        return encrypted
    }

    private fun createEncrypted(context: Context, name: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "${name}_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    private fun migrateFromPlain(
        context: Context,
        legacyName: String,
        target: SharedPreferences
    ) {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        if (target.getBoolean(MIGRATION_DONE_KEY, false)) return

        val editor = target.edit()
        for ((key, value) in legacy.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.putBoolean(MIGRATION_DONE_KEY, true).apply()
        legacy.edit().clear().apply()
    }

    private const val MIGRATION_DONE_KEY = "__secure_migration_done"
}
