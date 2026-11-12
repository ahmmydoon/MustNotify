package com.salahoard.mustnotify

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.salahoard.mustnotify.ui.App

/** Must match `mainClass: com.salahoard.mustnotify.MainKt` in desktopApp/module.yaml (file name main.kt -> MainKt). */
fun main() = application {
    val deps = remember { buildDesktopDependencies() }
    Window(onCloseRequest = ::exitApplication, title = "Must Notify") {
        App(deps)
    }
}
