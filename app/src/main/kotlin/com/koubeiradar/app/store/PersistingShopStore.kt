package com.koubeiradar.app.store

import com.koubeiradar.core.model.Shop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Keeps what the user imported across process restarts. Wraps another [ShopStore]: every change to the imported shops
 * is forwarded to it, and one background writer then saves the latest state through [persistence]; changes that arrive
 * faster than the disk are coalesced. Saving is best effort: a failed write must never break the screen that triggered it.
 *
 * Call [restore] once at startup. The writer waits for it, so a change made while the saved shops are still being
 * loaded can never overwrite them: the first write that follows contains both.
 */
class PersistingShopStore(
    private val delegate: ShopStore,
    private val persistence: ImportedShopStore,
    scope: CoroutineScope,
) : ShopStore by delegate {
    private val lock = Any()
    private var imported: List<Shop> = emptyList()

    /** A pending element means "the imported shops changed since the last write"; it carries no data on purpose. */
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val restored = CompletableDeferred<Unit>()

    init {
        scope.launch {
            restored.await()
            for (change in changes) persistLatest()
        }
    }

    /**
     * Loads the saved shops into the pool. Shops imported since the process started are newer and win over saved ones.
     * Never throws: starting the app must not depend on the saved file.
     */
    suspend fun restore() {
        try {
            val saved = try {
                persistence.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            synchronized(lock) {
                val known = imported.mapTo(HashSet()) { it.id }
                val older = saved.filter { it.id !in known }
                if (older.isNotEmpty()) {
                    imported = older + imported
                    delegate.addImported(older)
                }
            }
        } finally {
            restored.complete(Unit)
        }
    }

    override fun addImported(shops: List<Shop>) {
        if (shops.isEmpty()) return
        synchronized(lock) {
            delegate.addImported(shops)
            imported = replacingById(imported, shops)
        }
        changes.trySend(Unit)
    }

    override fun clearImported() {
        synchronized(lock) {
            delegate.clearImported()
            imported = emptyList()
        }
        changes.trySend(Unit)
    }

    override fun clearAll() {
        synchronized(lock) {
            delegate.clearAll()
            imported = emptyList()
        }
        changes.trySend(Unit)
    }

    private suspend fun persistLatest() {
        val latest = synchronized(lock) { imported }
        try {
            if (latest.isEmpty()) persistence.clear() else persistence.save(latest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort, whatever went wrong (I/O, serialization, a vanished directory ...): an exception that escaped
            // would end the writer loop, and later changes would silently stop being saved.
        }
    }

    /** [old] without the shops that [added] replaces, followed by [added] (the last duplicate wins). */
    private fun replacingById(old: List<Shop>, added: List<Shop>): List<Shop> {
        val newest = added.associateBy { it.id }
        return old.filter { it.id !in newest } + newest.values
    }
}
