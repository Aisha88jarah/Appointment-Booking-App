package com.example.bookings.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val TOKEN_REGEX = Regex("^\\d{5,15}:[A-Za-z0-9_-]{20,}$")

/** Bot token encrypted with an AES-256-GCM key that lives in the Android Keystore. */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_token", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
    }

    fun get(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val ct = prefs.getString("ct", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            clear()   // unreadable (key invalidated / restored backup): drop it, never log it
            null
        }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val ALIAS = "tg_registrar_token_key"
    }
}

class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    var monitoring: Boolean
        get() = p.getBoolean("monitoring", false)
        set(v) { p.edit().putBoolean("monitoring", v).apply() }
}
