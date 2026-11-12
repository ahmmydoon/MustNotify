package com.salahoard.mustnotify.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Persists [AppData] as one JSON file via [BlobStore]. A missing or corrupt file simply yields defaults. */
class JsonSettingsStore(
    private val blobs: BlobStore,
    private val fileName: String = "settings.json",
) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private var state: MutableStateFlow<AppData>? = null

    override val data: Flow<AppData> = flow { emitAll(loaded()) }

    private suspend fun loaded(): MutableStateFlow<AppData> = lock.withLock {
        state ?: MutableStateFlow(readFromDisk()).also { state = it }
    }

    override suspend fun update(transform: (AppData) -> AppData) {
        val flowState = loaded()
        lock.withLock {
            val next = transform(flowState.value)
            if (next != flowState.value) {
                blobs.write(fileName, json.encodeToString(AppData.serializer(), next).encodeToByteArray())
                flowState.value = next
            }
        }
    }

    private suspend fun readFromDisk(): AppData {
        val bytes = blobs.read(fileName) ?: return AppData()
        return try {
            json.decodeFromString(AppData.serializer(), bytes.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppData() // corrupt or from an incompatible version: start fresh rather than crash
        }
    }
}
