package com.salahoard.mustnotify

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.salahoard.mustnotify.data.BlobStore
import com.salahoard.mustnotify.data.SecretStore
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keeps the login token encrypted with a non-exportable AES key that lives in the Android Keystore.
 * If the key is gone (backup restore, lock-screen change on some devices) or the file is damaged, the token is
 * simply forgotten and the user signs in again; nothing ever falls back to plain text.
 */
class KeystoreSecretStore(
    private val blobs: BlobStore,
    private val fileName: String = "token.bin",
) : SecretStore {
    private val sealer = AesGcmSealer(::keystoreKey, aad = "must-notify/login-token/v1".encodeToByteArray())

    override suspend fun token(): String? {
        val sealed = blobs.read(fileName) ?: return null
        return try {
            sealer.open(sealed).decodeToString()
        } catch (e: Exception) {
            blobs.delete(fileName)
            null
        }
    }

    override suspend fun saveToken(token: String?) {
        if (token == null) blobs.delete(fileName) else blobs.write(fileName, sealer.seal(token.encodeToByteArray()))
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "must_notify_token_key"
    }
}
