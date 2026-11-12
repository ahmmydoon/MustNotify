package com.salahoard.mustnotify.api

import com.salahoard.mustnotify.data.BlobStore
import com.salahoard.mustnotify.data.IconRepository
import io.ktor.client.HttpClient

/**
 * Builds the network-facing objects around one shared HTTP client. App modules only ever see
 * [MustCloudApi] and [IconRepository], never Ktor types, so they need no Ktor dependency.
 */
class CloudServices private constructor(client: HttpClient, blobs: BlobStore) {
    val api: MustCloudApi = MustCloudApi(client)
    val icons: IconRepository = IconRepository(client, blobs)

    companion object {
        fun create(blobs: BlobStore): CloudServices = CloudServices(MustCloudApi.defaultHttpClient(), blobs)
    }
}
