package com.koubeiradar.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.koubeiradar.agent.importer.ImageInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

sealed interface ImageLoadResult {
    /** [image] is a JPEG whose long edge is at most [Downscaler.MAX_LONG_EDGE]. */
    class Loaded(val image: ImageInput) : ImageLoadResult

    /** The source exceeds the size or pixel limits. */
    data object TooLarge : ImageLoadResult

    data object Unreadable : ImageLoadResult
}

/** Turns an image the user picked or shared (a `content://` URI as text) into the bytes sent to the LLM. */
interface ImageLoader {
    suspend fun load(ref: String): ImageLoadResult

    /** Small preview for the UI; `null` when the image cannot be decoded. */
    suspend fun thumbnail(ref: String, maxEdgePx: Int): Bitmap?
}

class AndroidImageLoader(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageLoader {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun load(ref: String): ImageLoadResult = withContext(ioDispatcher) {
        val uri = contentUri(ref) ?: return@withContext ImageLoadResult.Unreadable
        try {
            val bytes = readCapped(uri) ?: return@withContext ImageLoadResult.TooLarge
            encode(bytes)
        } catch (e: IOException) {
            ImageLoadResult.Unreadable
        } catch (e: RuntimeException) {
            // SecurityException, or whatever the sharing app's provider throws: Binder re-throws IllegalArgumentException,
            // IllegalStateException, UnsupportedOperationException and NullPointerException from another process as is.
            ImageLoadResult.Unreadable
        } catch (e: OutOfMemoryError) {
            ImageLoadResult.TooLarge
        }
    }

    override suspend fun thumbnail(ref: String, maxEdgePx: Int): Bitmap? = withContext(ioDispatcher) {
        val uri = contentUri(ref) ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = Downscaler.sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // Called from a composition: an exception from another app's provider (see load) must not take the app down.
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Only `content://` is accepted: a `file://` URI from another app could point into this app's private storage. */
    private fun contentUri(ref: String): Uri? = ref.toUri().takeIf { it.scheme == "content" }

    /** Reads the whole source, or returns `null` as soon as it turns out to be bigger than [Downscaler.MAX_SOURCE_BYTES]. */
    private fun readCapped(uri: Uri): ByteArray? {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open image")
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > Downscaler.MAX_SOURCE_BYTES) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun encode(bytes: ByteArray): ImageLoadResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImageLoadResult.Unreadable
        if (bounds.outWidth.toLong() * bounds.outHeight > Downscaler.MAX_SOURCE_PIXELS) return ImageLoadResult.TooLarge

        val options = BitmapFactory.Options().apply {
            inSampleSize = Downscaler.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return ImageLoadResult.Unreadable
        val target = Downscaler.fitWithin(decoded.width, decoded.height)
        val scaled = if (target.width == decoded.width && target.height == decoded.height) {
            decoded
        } else {
            decoded.scale(target.width, target.height)
        }
        val flattened = flattenOnWhite(scaled)
        val out = ByteArrayOutputStream()
        val compressed = flattened.compress(Bitmap.CompressFormat.JPEG, Downscaler.JPEG_QUALITY, out)
        listOf(decoded, scaled, flattened).distinct().forEach { it.recycle() }
        return if (compressed) ImageLoadResult.Loaded(ImageInput(JPEG_MEDIA_TYPE, out.toByteArray())) else ImageLoadResult.Unreadable
    }

    /** JPEG has no alpha channel; transparent pixels would turn black and hide dark text. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = createBitmap(bitmap.width, bitmap.height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val JPEG_MEDIA_TYPE = "image/jpeg"
    }
}
