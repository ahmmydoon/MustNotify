package com.salahoard.mustnotify

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
