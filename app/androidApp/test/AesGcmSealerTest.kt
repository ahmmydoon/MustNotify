package com.salahoard.mustnotify

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AesGcmSealerTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val aad = "purpose-a".encodeToByteArray()
    private val secret = "HK-example-token".encodeToByteArray()

    @Test fun roundTrips() {
        val key = newKey()
        val sealer = AesGcmSealer({ key }, aad)
        assertContentEquals(secret, sealer.open(sealer.seal(secret)))
    }

    @Test fun neverStoresThePlainText() {
        val key = newKey()
        val sealed = AesGcmSealer({ key }, aad).seal(secret)
        assertEquals(1 + 12 + secret.size + 16, sealed.size) // version + IV + ciphertext + tag
        assertFalse(sealed.decodeToString().contains("HK-example-token"))
    }

    @Test fun usesAFreshIvEveryTime() {
        val key = newKey()
        val sealer = AesGcmSealer({ key }, aad)
        assertNotEquals(sealer.seal(secret).toList(), sealer.seal(secret).toList())
    }

    @Test fun detectsTampering() {
        val key = newKey()
        val sealer = AesGcmSealer({ key }, aad)
        val sealed = sealer.seal(secret)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertFailsWith<Exception> { sealer.open(sealed) }
    }

    @Test fun rejectsAWrongKey() {
        val sealed = AesGcmSealer({ newKey() }, aad).seal(secret)
        assertFailsWith<Exception> { AesGcmSealer({ newKey() }, aad).open(sealed) }
    }

    @Test fun rejectsAnotherPurpose() {
        val key = newKey()
        val sealed = AesGcmSealer({ key }, aad).seal(secret)
        assertFailsWith<Exception> { AesGcmSealer({ key }, "purpose-b".encodeToByteArray()).open(sealed) }
    }

    @Test fun rejectsGarbage() {
        val key = newKey()
        assertFailsWith<IllegalArgumentException> { AesGcmSealer({ key }, aad).open(byteArrayOf(1, 2, 3)) }
    }
}
