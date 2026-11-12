package com.salahoard.mustnotify.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BlobStore] backed by a directory. Pass an app-private one (Android: Context.filesDir,
 * desktop: a per-user config dir). Writes go to a temp file first so a crash can't leave a half-written file.
 */
class FileBlobStore(private val dir: File) : BlobStore {
    init { dir.mkdirs() }

    private fun file(name: String): File {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && ".." !in name) {
            "unsafe blob name"
        }
        return File(dir, name)
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        file(name).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = file(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace $name" }
        }
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) { file(name).delete() }
}
