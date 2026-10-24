package com.koubeiradar.app.ui

import androidx.annotation.StringRes
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.R
import com.koubeiradar.data.ShopSourceException

@StringRes
fun ShopSourceException.Kind.messageRes(): Int = when (this) {
    ShopSourceException.Kind.AUTH -> R.string.error_source_auth
    ShopSourceException.Kind.QUOTA -> R.string.error_source_quota
    ShopSourceException.Kind.NETWORK -> R.string.error_source_network
    ShopSourceException.Kind.PARSE -> R.string.error_source_parse
    ShopSourceException.Kind.OTHER -> R.string.error_source_other
}

@StringRes
fun LlmException.Kind.messageRes(): Int = when (this) {
    LlmException.Kind.AUTH -> R.string.error_llm_auth
    LlmException.Kind.RATE_LIMIT -> R.string.error_llm_rate_limit
    LlmException.Kind.NETWORK -> R.string.error_llm_network
    LlmException.Kind.BAD_REQUEST -> R.string.error_llm_bad_request
    LlmException.Kind.SERVER -> R.string.error_llm_server
    LlmException.Kind.REFUSAL -> R.string.error_llm_refusal
    LlmException.Kind.OTHER -> R.string.error_llm_other
}

/** Screenshot import can fail with a 400 mostly because the chosen model has no vision support; say so. */
@StringRes
fun LlmException.Kind.importMessageRes(): Int =
    if (this == LlmException.Kind.BAD_REQUEST) R.string.error_import_bad_request else messageRes()
