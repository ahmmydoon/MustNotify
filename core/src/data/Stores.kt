package com.salahoard.mustnotify.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Observable, atomically-updatable settings. The UI edits [AppData.rules]; the poller edits [AppData.runtime]. */
interface SettingsStore {
    val data: Flow<AppData>

    /** [transform] must be pure; it may be re-run if two writers collide. */
    suspend fun update(transform: (AppData) -> AppData)
}

/** The login token. Platform code should back this with encrypted storage (Android Keystore etc.). */
interface SecretStore {
    suspend fun token(): String?
    suspend fun saveToken(token: String?)
}

/** Minimal byte storage, implemented per platform on top of an app-private directory. */
interface BlobStore {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun delete(name: String)
}

class InMemorySettingsStore(initial: AppData = AppData()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val data: Flow<AppData> = state.asStateFlow()
    override suspend fun update(transform: (AppData) -> AppData) = state.update(transform)
}

/** Forgets the token when the process dies. Temporary until a real encrypted store exists. */
class InMemorySecretStore(private var token: String? = null) : SecretStore {
    override suspend fun token(): String? = token
    override suspend fun saveToken(token: String?) { this.token = token }
}
