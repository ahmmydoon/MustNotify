package com.salahoard.mustnotify.api

import java.security.MessageDigest

actual fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
