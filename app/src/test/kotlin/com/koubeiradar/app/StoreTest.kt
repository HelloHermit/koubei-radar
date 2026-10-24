package com.koubeiradar.app

import com.koubeiradar.app.store.FileImportedShopStore
import com.koubeiradar.app.store.ImportedShopStore
import com.koubeiradar.app.store.PersistingShopStore
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.Shop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun imported(n: Int, reviewText: String? = null) = Shop(
    id = "import:$n",
    name = "导入的店 $n",
    source = "import",
    rating = 4.6,
    ratingCount = 120,
    monthlySales = 900,
    platforms = setOf(Platform.MEITUAN),
    reviews = listOfNotNull(reviewText?.let { Review(it, 4.0) }),
    tags = listOf("川菜", "辣"),
)

class FileImportedShopStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(maxShops: Int = FileImportedShopStore.MAX_SHOPS, maxBytes: Int = FileImportedShopStore.MAX_BYTES) =
        FileImportedShopStore(folder.root, Dispatchers.Unconfined, maxShops, maxBytes)

    private val file get() = File(folder.root, FileImportedShopStore.FILE_NAME)

    @Test
    fun `saved shops come back unchanged`() = runTest {
        val shops = listOf(imported(1, "好吃"), imported(2))
        store().save(shops)
        assertEquals(shops, store().load())
    }

    @Test
    fun `no file means no shops`() = runTest {
        assertEquals(emptyList(), store().load())
    }

    @Test
    fun `a corrupt file is ignored and deleted`() = runTest {
        file.writeText("{ this is not json")
        assertEquals(emptyList(), store().load())
        assertFalse(file.exists())
    }

    @Test
    fun `a file with an unknown version or wrong shape is ignored and deleted`() = runTest {
        file.writeText("""{"version":99,"shops":[]}""")
        assertEquals(emptyList(), store().load())
        assertFalse(file.exists())
        file.writeText("""{"version":1,"shops":[{"id":5}]}""")
        assertEquals(emptyList(), store().load())
        assertFalse(file.exists())
    }

    @Test
    fun `unknown json fields are tolerated`() = runTest {
        file.writeText("""{"version":1,"future":true,"shops":[{"id":"import:1","name":"店","source":"import","newField":1}]}""")
        assertEquals(listOf("import:1"), store().load().map { it.id })
    }

    @Test
    fun `an oversized file is treated as corrupt`() = runTest {
        file.writeText("x".repeat(4_000))
        assertEquals(emptyList(), store(maxBytes = 2_000).load())
        assertFalse(file.exists())
    }

    @Test
    fun `the newest shops are kept when there are too many`() = runTest {
        store(maxShops = 3).save((1..10).map { imported(it) })
        assertEquals(listOf("import:8", "import:9", "import:10"), store(maxShops = 3).load().map { it.id })
    }

    @Test
    fun `the oldest shops are dropped when the size limit is exceeded`() = runTest {
        val shops = (1..10).map { imported(it, reviewText = "评价".repeat(100)) }
        store(maxBytes = 3_000).save(shops)
        val kept = store().load()
        assertTrue(kept.isNotEmpty() && kept.size < shops.size, "kept ${kept.size}")
        assertEquals(shops.takeLast(kept.size), kept)
        assertTrue(file.length() <= 3_000, "file is ${file.length()} bytes")
    }

    @Test
    fun `saving nothing and clearing remove the file and leave no temporary file behind`() = runTest {
        store().save(listOf(imported(1)))
        assertTrue(file.exists())
        store().save(emptyList())
        assertFalse(file.exists())
        store().save(listOf(imported(1)))
        store().clear()
        assertFalse(file.exists())
        assertEquals(emptyList(), folder.root.list()?.toList())
    }

    @Test
    fun `a later save replaces the earlier content completely`() = runTest {
        store().save(listOf(imported(1), imported(2)))
        store().save(listOf(imported(3)))
        assertEquals(listOf("import:3"), store().load().map { it.id })
        assertEquals(listOf(FileImportedShopStore.FILE_NAME), folder.root.list()?.toList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersistingShopStoreTest {
    private class MemoryPersistence(var content: List<Shop> = emptyList()) : ImportedShopStore {
        var saves = 0
        var clears = 0
        var failSaves = false
        var failure: Exception = IOException("disk full")

        override suspend fun load() = content

        override suspend fun save(shops: List<Shop>) {
            if (failSaves) throw failure
            saves++
            content = shops
        }

        override suspend fun clear() {
            clears++
            content = emptyList()
        }
    }

    private val delegate = FakeShopStore()
    private val persistence = MemoryPersistence()

    /** The writer runs eagerly on an unconfined dispatcher: advancing a test scheduler never runs background coroutines. */
    private fun TestScope.store() = PersistingShopStore(delegate, persistence, backgroundScope + UnconfinedTestDispatcher(testScheduler))

    private suspend fun TestScope.restoredStore() = store().also { it.restore() }

    @Test
    fun `added shops reach the pool and are saved`() = runTest {
        val store = restoredStore()
        store.addImported(listOf(imported(1), imported(2)))
        advanceUntilIdle()
        assertEquals(listOf("import:1", "import:2"), delegate.imported.map { it.id })
        assertEquals(listOf("import:1", "import:2"), persistence.content.map { it.id })
    }

    @Test
    fun `a re-imported shop replaces its earlier version in the saved copy`() = runTest {
        val store = restoredStore()
        store.addImported(listOf(imported(1), imported(2)))
        store.addImported(listOf(imported(1, reviewText = "新的")))
        advanceUntilIdle()
        assertEquals(listOf("import:2", "import:1"), persistence.content.map { it.id })
        assertEquals(1, persistence.content.last().reviews.size)
    }

    @Test
    fun `clearing deletes the saved copy, in the order the calls were made`() = runTest {
        val store = restoredStore()
        store.addImported(listOf(imported(1)))
        store.clearImported()
        advanceUntilIdle()
        assertEquals(emptyList(), persistence.content)
        assertEquals(1, delegate.clearedImported)
        store.addImported(listOf(imported(2)))
        store.clearAll()
        advanceUntilIdle()
        assertEquals(emptyList(), persistence.content)
        assertEquals(1, delegate.clearedAll)
    }

    @Test
    fun `restore feeds the saved shops to the pool once`() = runTest {
        persistence.content = listOf(imported(1), imported(2))
        val store = store()
        store.restore()
        assertEquals(listOf("import:1", "import:2"), delegate.imported.map { it.id })
        assertEquals(0, persistence.saves, "restoring must not rewrite the file")
    }

    @Test
    fun `shops imported before the restore finished win and the saved ones are not lost`() = runTest {
        persistence.content = listOf(imported(1), imported(2))
        val store = store()
        store.addImported(listOf(imported(1, reviewText = "新的")))
        assertEquals(0, persistence.saves, "writing waits until the saved shops are loaded")

        store.restore()

        assertEquals(listOf("import:1", "import:2"), delegate.imported.map { it.id }.sorted())
        assertEquals(1, delegate.imported.first { it.id == "import:1" }.reviews.size)
        assertEquals(2, delegate.imported.size, "the older saved copy of import:1 is not added again")
        assertEquals(listOf("import:2", "import:1"), persistence.content.map { it.id }, "the file now holds both")
        assertEquals(1, persistence.content.last().reviews.size)
    }

    @Test
    fun `an unreadable saved file does not stop the app, and later imports are still saved`() = runTest {
        val broken = object : ImportedShopStore by persistence {
            override suspend fun load(): List<Shop> = throw IllegalStateException("corrupt")
        }
        val store = PersistingShopStore(delegate, broken, backgroundScope + UnconfinedTestDispatcher(testScheduler))
        store.restore()
        store.addImported(listOf(imported(1)))
        assertEquals(listOf("import:1"), persistence.content.map { it.id })
    }

    @Test
    fun `a failing write never breaks the store`() = runTest {
        persistence.failSaves = true
        val store = restoredStore()
        store.addImported(listOf(imported(1)))
        advanceUntilIdle()
        persistence.failSaves = false
        store.addImported(listOf(imported(2)))
        advanceUntilIdle()
        assertEquals(listOf("import:1", "import:2"), persistence.content.map { it.id })
    }

    @Test
    fun `any failure of a save is survived and later saves still happen`() = runTest {
        persistence.failSaves = true
        persistence.failure = IllegalStateException("the directory vanished")
        val store = restoredStore()
        store.addImported(listOf(imported(1)))
        advanceUntilIdle()
        persistence.failSaves = false
        store.addImported(listOf(imported(2)))
        advanceUntilIdle()
        assertEquals(listOf("import:1", "import:2"), persistence.content.map { it.id })
    }

    @Test
    fun `nothing is saved for an empty import`() = runTest {
        val store = restoredStore()
        store.addImported(emptyList())
        advanceUntilIdle()
        assertEquals(0, persistence.saves)
    }
}
