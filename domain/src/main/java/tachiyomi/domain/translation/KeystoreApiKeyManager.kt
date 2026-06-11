package tachiyomi.domain.translation

import android.content.Context
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreApiKeyManager(private val context: Context) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val MASTER_KEY_ALIAS = "translation_master_key"

        fun getAliasForEngine(engine: String): String {
            return "api_key_${engine.lowercase().replace(" ", "_")}"
        }
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private val secureRandom = SecureRandom()

    fun storeApiKey(engine: String, apiKey: String): Result<Unit> {
        return try {
            val masterKey = getMasterKey() ?: generateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val iv = cipher.iv
            val encryptedData = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val combined = iv + encryptedData

            context.getSharedPreferences("encrypted_api_keys", Context.MODE_PRIVATE)
                .edit()
                .putString(getAliasForEngine(engine), Base64.encodeToString(combined, Base64.NO_WRAP))
                .apply()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getApiKey(engine: String): Result<String> {
        return try {
            val encryptedData = context.getSharedPreferences("encrypted_api_keys", Context.MODE_PRIVATE)
                .getString(getAliasForEngine(engine), null)
                ?: return Result.failure(IOException("API key not found"))

            val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val masterKey = getMasterKey() ?: return Result.failure(SecurityException("Master key not found"))

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

            val decryptedData = cipher.doFinal(ciphertext)
            Result.success(String(decryptedData, Charsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun rotateApiKey(engine: String, oldKey: String): Result<String> {
        return try {
            val currentKey = getApiKey(engine).getOrNull()
            if (currentKey == null || currentKey != oldKey) {
                return Result.failure(SecurityException("Key rotation failed: current key mismatch"))
            }

            val newKey = generateNewApiKey()
            storeApiKey(engine, newKey)
            Result.success(newKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteApiKey(engine: String): Result<Unit> {
        return try {
            context.getSharedPreferences("encrypted_api_keys", Context.MODE_PRIVATE)
                .edit()
                .remove(getAliasForEngine(engine))
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun hasApiKey(engine: String): Boolean {
        return context.getSharedPreferences("encrypted_api_keys", Context.MODE_PRIVATE)
            .contains(getAliasForEngine(engine))
    }

    private fun generateMasterKey(): SecretKey {
        return try {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(KEY_SIZE)
            val secretKey = keyGenerator.generateKey()

            val keyEntry = KeyStore.SecretKeyEntry(secretKey)
            val protection = android.security.keystore.KeyProtection.Builder(
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()

            keyStore.setEntry(MASTER_KEY_ALIAS, keyEntry, protection)
            secretKey
        } catch (e: Exception) {
            throw SecurityException("Failed to generate master key in keystore", e)
        }
    }

    private fun getMasterKey(): SecretKey? {
        return try {
            val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            null
        }
    }

    private fun generateNewApiKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder()
        repeat(32) {
            sb.append(chars[secureRandom.nextInt(chars.length)])
        }
        return sb.toString()
    }
}
