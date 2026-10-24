package com.koubeiradar.agent.llm

/** Lower-cases a media type and maps the common but non-standard `image/jpg` to `image/jpeg`. */
internal fun normalizeImageMediaType(mediaType: String): String {
    val lowered = mediaType.trim().lowercase()
    return if (lowered == "image/jpg") "image/jpeg" else lowered
}
