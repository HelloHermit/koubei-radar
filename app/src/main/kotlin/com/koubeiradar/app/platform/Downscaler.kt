package com.koubeiradar.app.platform

import kotlin.math.max
import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int)

/** Size arithmetic for screenshots sent to a vision model: small enough to be cheap, large enough to stay legible. */
object Downscaler {
    const val MAX_LONG_EDGE = 1568
    const val JPEG_QUALITY = 85

    /** Largest accepted source file; bigger ones are rejected before any decoding. */
    const val MAX_SOURCE_BYTES = 12 * 1024 * 1024

    /** Decompression-bomb guard: sources with more pixels than this are rejected. */
    const val MAX_SOURCE_PIXELS = 100_000_000L

    /** [width]x[height] scaled down (never up) so that the long edge is at most [maxEdge], keeping the aspect ratio. */
    fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE): PixelSize {
        require(width > 0 && height > 0 && maxEdge > 0) { "sizes must be positive" }
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return PixelSize(width, height)
        val scale = maxEdge.toDouble() / longEdge
        return PixelSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * Power-of-two `inSampleSize` for the first, cheap decoding stage: the largest factor that still leaves the
     * long edge at or above [maxEdge], so the exact resize afterwards never has to upscale.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE): Int {
        require(width > 0 && height > 0 && maxEdge > 0) { "sizes must be positive" }
        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}
