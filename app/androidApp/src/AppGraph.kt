package com.salahoard.mustnotify

import android.content.Context
import com.salahoard.mustnotify.api.CloudServices
import com.salahoard.mustnotify.data.FileBlobStore
import com.salahoard.mustnotify.data.InMemorySecretStore
import com.salahoard.mustnotify.data.JsonSettingsStore
import com.salahoard.mustnotify.poll.LiveHub
import com.salahoard.mustnotify.poll.PollEngine
import com.salahoard.mustnotify.ui.AppDependencies

/**
 * One set of dependencies per process, shared by MainActivity and (next step) the foreground service, which will
 * run `PollLoop(deps.engine, deps.settings, notifier, deps.hub)`.
 * The token store is in-memory for now: you will have to sign in again after the app process dies.
 * It gets replaced by an Android Keystore-backed store in the service step.
 */
object AppGraph {
    @Volatile private var instance: AppDependencies? = null

    fun get(context: Context): AppDependencies = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(context: Context): AppDependencies {
        val blobs = FileBlobStore(context.filesDir) // app-private; keep android:allowBackup="false" in the manifest
        val cloud = CloudServices.create(blobs)
        val settings = JsonSettingsStore(blobs)
        val secrets = InMemorySecretStore()
        return AppDependencies(
            api = cloud.api,
            settings = settings,
            secrets = secrets,
            icons = cloud.icons,
            engine = PollEngine(cloud.api, settings, secrets, nowEpochSeconds = { System.currentTimeMillis() / 1000 }),
            hub = LiveHub(),
        )
    }
}
