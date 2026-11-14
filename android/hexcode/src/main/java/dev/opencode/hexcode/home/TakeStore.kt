package dev.opencode.hexcode.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a take's audio until it has text (app-plan.md, The speech model): takes released before the model is ready,
 * and takes the engine failed on, for Try again.
 */
interface TakeStore {
    suspend fun save(
        id: Long,
        samples: FloatArray,
    )

    /** The take's samples, or null when none are kept. */
    suspend fun load(id: Long): FloatArray?

    suspend fun delete(id: Long)

    /** Forgets every take, when the draft is cleared. */
    suspend fun clear()
}

/**
 * Takes as files in [dir], so a long wait for the first download can't use up memory: 64 KB a second of audio. Takes
 * left from an earlier run are deleted when it's made, since waiting takes don't survive the app being closed.
 */
class FileTakeStore(
    private val dir: File,
) : TakeStore {
    init {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun file(id: Long) = File(dir, "take-$id.f32")

    override suspend fun save(
        id: Long,
        samples: FloatArray,
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        DataOutputStream(BufferedOutputStream(file(id).outputStream())).use { out ->
            out.writeInt(samples.size)
            for (sample in samples) out.writeFloat(sample)
        }
    }

    override suspend fun load(id: Long): FloatArray? =
        withContext(Dispatchers.IO) {
            val file = file(id)
            if (!file.exists()) return@withContext null
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                FloatArray(input.readInt()) { input.readFloat() }
            }
        }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { file(id).delete() }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }
}

/** Takes in memory: the tests, and builds without a files folder. */
class MemoryTakeStore : TakeStore {
    private val takes = ConcurrentHashMap<Long, FloatArray>()

    val ids: Set<Long> get() = takes.keys

    override suspend fun save(
        id: Long,
        samples: FloatArray,
    ) {
        takes[id] = samples
    }

    override suspend fun load(id: Long): FloatArray? = takes[id]

    override suspend fun delete(id: Long) {
        takes.remove(id)
    }

    override suspend fun clear() = takes.clear()
}
