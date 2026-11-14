package com.salahoard.mustnotify.ui

import com.salahoard.mustnotify.api.MustCloudApi
import com.salahoard.mustnotify.data.IconRepository
import com.salahoard.mustnotify.data.SecretStore
import com.salahoard.mustnotify.data.SettingsStore
import com.salahoard.mustnotify.poll.LiveHub
import com.salahoard.mustnotify.poll.PollEngine

/** Everything the screens need, built once by the platform app (Android / desktop) and passed to [App]. */
class AppDependencies(
    val api: MustCloudApi,
    val settings: SettingsStore,
    val secrets: SecretStore,
    val icons: IconRepository,
    val engine: PollEngine,
    val hub: LiveHub,
    /** Null on platforms that need no special background permission (desktop). */
    val background: BackgroundAccess? = null,
    /** Null where home-screen widgets don't exist (desktop). */
    val widgets: WidgetAccess? = null,
)
