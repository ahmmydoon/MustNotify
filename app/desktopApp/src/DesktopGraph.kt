package com.salahoard.mustnotify

import com.salahoard.mustnotify.api.CloudServices
import com.salahoard.mustnotify.data.FileBlobStore
import com.salahoard.mustnotify.data.InMemorySecretStore
import com.salahoard.mustnotify.data.JsonSettingsStore
import com.salahoard.mustnotify.poll.LiveHub
import com.salahoard.mustnotify.poll.PollEngine
import com.salahoard.mustnotify.ui.AppDependencies
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Builds the desktop dependencies. Call once from main: `val deps = remember { buildDesktopDependencies() }`. */
fun buildDesktopDependencies(): AppDependencies {
    val dir = configDir()
    dir.mkdirs()
    // settings.json holds pn/sn: make the folder owner-only where the OS supports POSIX permissions.
    try { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) } catch (_: Exception) {}

    val blobs = FileBlobStore(dir)
    val cloud = CloudServices.create(blobs)
    val settings = JsonSettingsStore(blobs)
    val secrets = InMemorySecretStore() // temporary, like on Android
    return AppDependencies(
        api = cloud.api,
        settings = settings,
        secrets = secrets,
        icons = cloud.icons,
        engine = PollEngine(cloud.api, settings, secrets, nowEpochSeconds = { System.currentTimeMillis() / 1000 }),
        hub = LiveHub(),
    )
}

private fun configDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        "win" in os -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "MustNotify")
        "mac" in os -> File(home, "Library/Application Support/MustNotify")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "must-notify")
    }
}
