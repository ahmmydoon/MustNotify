package com.salahoard.mustnotify.ui

/** Platform hook for placing a home-screen widget. Android implements it; desktop passes null (no such widget). */
interface WidgetAccess {
    /**
     * Asks the launcher to place a widget showing the inverter with serial number [sn].
     * Returns false when the launcher can't be asked from inside an app, so the screen can explain how to add it by hand.
     */
    fun requestPin(sn: String): Boolean
}
