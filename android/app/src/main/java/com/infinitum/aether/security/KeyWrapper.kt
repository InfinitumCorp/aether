package com.infinitum.aether.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the 32-byte data key that opens every ÆTHER vault.
 *
 * The data key is random, generated once, and stored on disk only wrapped
 * (AES-256-GCM) by a non-exportable Android Keystore key, StrongBox-backed
 * where the device has a secure element. Raw key bytes exist in memory only
 * while a vault is being opened by the Rust core.
 */
class KeyWrapper(private val context: Context) {
    private val wrappedFile = File(context.noBackupFilesDir, "vault.key")

    var hardwareBacking: String = "unknown"
        private set

    fun dataKey(): ByteArray {
        val master = masterKey()
        if (wrappedFile.exists()) {
            val blob = wrappedFile.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob, 0, IV_LEN))
            cipher.updateAAD(AAD)
            return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, master)
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(key)
        val tmp = File(wrappedFile.parentFile, "vault.key.tmp")
        tmp.writeBytes(cipher.iv + ct)
        check(tmp.renameTo(wrappedFile)) { "could not persist wrapped key" }
        return key
    }

    /**
     * A random 32-byte key for one burner number, wrapped by its own Keystore key [alias]
     * and stored at [file]. [destroyScoped] deletes both, crypto-shredding everything
     * encrypted under it even if the ciphertext survives on flash.
     */
    fun scopedKey(alias: String, file: File): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapping = (ks.getKey(alias, null) as? SecretKey) ?: generate(alias, strongBox = false)
        if (file.exists()) {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, blob, 0, IV_LEN))
            cipher.updateAAD(SCOPED_AAD)
            return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        cipher.updateAAD(SCOPED_AAD)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(key))
        check(tmp.renameTo(file)) { "could not persist wrapped key" }
        return key
    }

    fun destroyScoped(alias: String, file: File) {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            .onFailure { Log.w(TAG, "could not delete keystore entry", it) }
        file.delete()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let {
            hardwareBacking = describe(it)
            return it
        }
        val key = try {
            generate(ALIAS, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "StrongBox unavailable, using TEE-backed keystore")
            generate(ALIAS, strongBox = false)
        }
        hardwareBacking = describe(key)
        return key
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    private fun describe(key: SecretKey): String = try {
        val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                else -> "Unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "Secure hardware" else "Software"
        }
    } catch (e: Exception) {
        "Unknown"
    }

    private companion object {
        const val TAG = "AetherKeys"
        const val ALIAS = "aether.vault.master.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        val AAD = "AETHER-VAULT-KEY-v1".toByteArray()
        val SCOPED_AAD = "AETHER-BURNER-KEY-v1".toByteArray()
    }
}
