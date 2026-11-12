package com.salahoard.mustnotify.data

import com.salahoard.mustnotify.api.sha1Hex
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads a device icon once and serves it from disk afterwards (it is never re-fetched while cached).
 * Only https URLs are accepted, and downloads are size-capped.
 */
class IconRepository internal constructor(
    private val client: HttpClient,
    private val blobs: BlobStore,
    private val maxBytes: Long = 512 * 1024,
) {
    suspend fun get(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val name = "icon_${sha1Hex(url)}.bin"
        blobs.read(name)?.let { return it }
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) return null
            val declared = response.contentLength()
            if (declared != null && declared > maxBytes) return null
            val bytes = response.readRawBytes()
            if (bytes.isEmpty() || bytes.size > maxBytes) return null
            blobs.write(name, bytes)
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
