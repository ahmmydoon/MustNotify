package com.salahoard.mustnotify.ui

/**
 * Platform hook for "may the OS pause my background checks?". Android implements it (battery optimization);
 * desktop passes null because nothing needs to be granted there.
 */
interface BackgroundAccess {
    /** True while the OS may suspend background work, so the device screen should offer the settings shortcut. */
    fun isRestricted(): Boolean

    fun openSettings()
}
