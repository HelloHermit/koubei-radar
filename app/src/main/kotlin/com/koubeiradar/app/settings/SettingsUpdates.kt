package com.koubeiradar.app.settings

import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class SaveResult {
    SAVED,

    /** The write failed (disk full, I/O error). */
    FAILED,

    /** A credential could not be encrypted because the device's key store is unusable; nothing was written. */
    SECURE_STORAGE_UNAVAILABLE,
}

/** Applies [transform] and reports the outcome instead of throwing. */
suspend fun SettingsRepository.tryUpdate(transform: (AppSettings) -> AppSettings): SaveResult = try {
    update(transform)
    SaveResult.SAVED
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    SaveResult.FAILED
} catch (e: SecretStorageException) {
    SaveResult.SECURE_STORAGE_UNAVAILABLE
}
