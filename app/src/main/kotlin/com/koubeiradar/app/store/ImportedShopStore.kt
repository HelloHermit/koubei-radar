package com.koubeiradar.app.store

import com.koubeiradar.core.model.Shop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable copy of the shops the user imported, so they survive the process being killed. */
interface ImportedShopStore {
    /** The saved shops, or an empty list when there is no file or it cannot be read (an unreadable file is deleted). */
    suspend fun load(): List<Shop>

    @Throws(IOException::class)
    suspend fun save(shops: List<Shop>)

    suspend fun clear()
}

/**
 * Stores the imported shops as one JSON file inside [directory] (the app-private `filesDir` in production).
 *
 * Writes go to a temporary file that is flushed to disk and then renamed over the target, so a crash mid-write leaves
 * the previous version intact. At most [maxShops] shops and [maxBytes] bytes are kept; when over the limit the oldest
 * shops (the front of the list) are dropped. All I/O runs on [ioDispatcher].
 */
class FileImportedShopStore(
    directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxShops: Int = MAX_SHOPS,
    private val maxBytes: Int = MAX_BYTES,
) : ImportedShopStore {
    private val file = File(directory, FILE_NAME)
    private val tempFile = File(directory, "$FILE_NAME.tmp")
    private val mutex = Mutex()

    override suspend fun load(): List<Shop> = withContext(ioDispatcher) {
        mutex.withLock { readOrDiscard() }
    }

    override suspend fun save(shops: List<Shop>) = withContext(ioDispatcher) {
        mutex.withLock {
            val kept = fit(shops)
            if (kept.isEmpty()) {
                deleteFiles()
            } else {
                writeAtomically(JSON.encodeToString(Snapshot.serializer(), Snapshot(VERSION, kept)).toByteArray(Charsets.UTF_8))
            }
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock { deleteFiles() }
    }

    /** The newest shops that satisfy both limits. */
    private fun fit(shops: List<Shop>): List<Shop> {
        var total = SNAPSHOT_OVERHEAD_BYTES
        val kept = ArrayDeque<Shop>()
        for (shop in shops.takeLast(maxShops).asReversed()) {
            total += JSON.encodeToString(Shop.serializer(), shop).toByteArray(Charsets.UTF_8).size + SEPARATOR_BYTES
            if (total > maxBytes) break
            kept.addFirst(shop)
        }
        return kept.toList()
    }

    private fun readOrDiscard(): List<Shop> {
        if (!file.exists()) return emptyList()
        return try {
            if (file.length() > maxBytes) throw IOException("snapshot exceeds the size limit")
            val snapshot = JSON.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8))
            if (snapshot.version != VERSION) throw IOException("unsupported snapshot version")
            snapshot.shops.takeLast(maxShops)
        } catch (e: IOException) {
            deleteFiles()
            emptyList()
        } catch (e: SerializationException) {
            deleteFiles()
            emptyList()
        } catch (e: IllegalArgumentException) {
            deleteFiles()
            emptyList()
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(tempFile).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteFiles() {
        file.delete()
        tempFile.delete()
    }

    @Serializable
    private class Snapshot(val version: Int, val shops: List<Shop>)

    companion object {
        const val MAX_SHOPS = 500
        const val MAX_BYTES = 2 * 1024 * 1024
        const val FILE_NAME = "imported_shops.json"

        private const val VERSION = 1
        private const val SNAPSHOT_OVERHEAD_BYTES = 64
        private const val SEPARATOR_BYTES = 1
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
