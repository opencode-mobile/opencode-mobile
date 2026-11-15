package dev.opencode.voiceengine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches one model file into `<target>.part`, then checks its SHA-256 and moves it into place. A dropped connection
 * doesn't start the file over: the next attempt asks for the rest with an HTTP `Range` and appends it, and a `.part`
 * left by an earlier process is carried on the same way. Network errors are retried after 1, 2, 4 and 8 s (as the
 * event stream reconnects), and an attempt that got more of the file onto the phone starts that count again, so a
 * long download on a flaky network keeps going as long as it moves. A stalled connection times out instead of
 * hanging. A damaged file is deleted and fetched once more from the start.
 */
class ModelDownload(
    private val open: (url: String, from: Long) -> Response = ::openHttp,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What the server sent: its HTTP [status] (206 when it honoured the range) and the [body] from there. */
    class Response(
        val status: Int,
        val body: InputStream,
        val close: () -> Unit = {},
    )

    /** The `.part` file and the SHA-256 of what's in it so far, kept in step as it grows. */
    private class Part(
        val file: File,
    ) {
        var digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var hashed = -1L

        /** Hashes what's already in the file, unless the digest already covers exactly that. */
        fun catchUp() {
            if (hashed == file.length()) return
            digest = MessageDigest.getInstance("SHA-256")
            hashed = 0
            if (file.exists()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        hashed += n
                    }
                }
            }
        }

        fun restart() {
            file.delete()
            digest = MessageDigest.getInstance("SHA-256")
            hashed = 0
        }

        fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fetches [file] into [target]. [progress] gets the bytes of it on the phone, at most every 250 ms. */
    fun fetch(
        file: ModelFile,
        target: File,
        progress: (Long) -> Unit,
    ) {
        val part = Part(File(target.path + ".part"))
        var damaged = 0
        while (true) {
            download(file, part, progress)
            part.catchUp()
            if (part.sha256() == file.sha256) break
            part.restart()
            if (++damaged > 1) throw IOException("${file.name} downloaded damaged. Try again.")
        }
        if (!part.file.renameTo(target)) throw IOException("Could not save ${file.name}")
    }

    /** Fills the part up to [ModelFile.bytes], retrying network errors while the file keeps growing. */
    private fun download(
        file: ModelFile,
        part: Part,
        progress: (Long) -> Unit,
    ) {
        var failures = 0
        while (true) {
            if (part.file.length() > file.bytes) part.restart()
            val before = part.file.length()
            if (before == file.bytes) return
            try {
                attempt(file, part, progress)
                if (part.file.length() == file.bytes) return
                throw IOException("${file.name} ended early")
            } catch (error: IOException) {
                if (part.file.length() > before) failures = 0
                if (failures >= BACKOFF_MS.size) throw error
                sleep(BACKOFF_MS[failures++])
            }
        }
    }

    /** One request: the rest of the file from what the part holds, or all of it when the server ignores the range. */
    private fun attempt(
        file: ModelFile,
        part: Part,
        progress: (Long) -> Unit,
    ) {
        val from = part.file.length()
        val response = open(file.url, from)
        try {
            val append =
                when (response.status) {
                    HttpURLConnection.HTTP_PARTIAL -> from > 0
                    HttpURLConnection.HTTP_OK -> false
                    // The part doesn't fit the file the server has: start it over.
                    RANGE_NOT_SATISFIABLE -> {
                        part.restart()
                        throw IOException("${file.name}: range not satisfiable")
                    }
                    else -> throw IOException("${file.name}: HTTP ${response.status}")
                }
            if (append) part.catchUp() else part.restart()
            var done = part.hashed
            progress(done)
            var reported = now()
            response.body.use { input ->
                FileOutputStream(part.file, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        part.digest.update(buffer, 0, n)
                        done += n
                        part.hashed = done
                        val at = now()
                        if (at - reported >= PROGRESS_MS) {
                            reported = at
                            progress(done)
                        }
                    }
                }
            }
            progress(done)
        } finally {
            response.close()
        }
    }

    companion object {
        /** Waits between failed attempts that got no further: four retries, then the download fails. */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

        const val PROGRESS_MS = 250L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        private const val RANGE_NOT_SATISFIABLE = 416

        private fun openHttp(
            url: String,
            from: Long,
        ): Response {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            val status = connection.responseCode
            val body = if (status < 400) connection.inputStream else ByteArrayInputStream(ByteArray(0))
            return Response(status, body) { connection.disconnect() }
        }
    }
}
