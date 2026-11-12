package com.salahoard.mustnotify.api

/**
 * Lowercase hex SHA-1 of the UTF-8 bytes of [input]. The vendor login expects the password in this form.
 * Note: the hash is password-equivalent for this API and unsalted, so never store or log it.
 */
expect fun sha1Hex(input: String): String
