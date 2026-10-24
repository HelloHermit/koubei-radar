package com.koubeiradar.agent.util

import java.util.concurrent.FutureTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs the blocking [block] on [Dispatchers.IO] and suspends until it finishes.
 *
 * Unlike `runInterruptible`, cancellation resumes the caller immediately instead of waiting for the blocking call:
 * the worker thread is interrupted, but a plain socket read does not react to an interrupt, so waiting for it could
 * keep a cancelled agent run alive until the network timeout. The abandoned result is discarded.
 */
internal suspend fun <T> awaitBlocking(block: () -> T): T = suspendCancellableCoroutine { continuation ->
    val task = FutureTask<Unit> {
        try {
            continuation.resume(block())
        } catch (t: Throwable) {
            continuation.resumeWithException(t)
        }
    }
    continuation.invokeOnCancellation { task.cancel(true) }
    Dispatchers.IO.asExecutor().execute(task)
}
