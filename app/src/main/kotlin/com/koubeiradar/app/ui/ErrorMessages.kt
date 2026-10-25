package com.koubeiradar.app.ui

import androidx.annotation.StringRes
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.R
import com.koubeiradar.app.settings.PoiProvider
import com.koubeiradar.data.ShopSourceException

/** [provider] is the data source that failed, for the messages that name it; `null` = Amap, the default. */
@StringRes
fun ShopSourceException.Kind.messageRes(provider: PoiProvider? = null): Int = when (this) {
    ShopSourceException.Kind.AUTH -> if (provider == PoiProvider.BAIDU) R.string.error_source_auth_baidu else R.string.error_source_auth
    ShopSourceException.Kind.QUOTA -> if (provider == PoiProvider.BAIDU) R.string.error_source_quota_baidu else R.string.error_source_quota
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

/** The hint shown when a typed address cannot be resolved because the selected provider has no key. */
@StringRes
fun PoiProvider.addressNeedsKeyRes(): Int = when (this) {
    PoiProvider.AMAP -> R.string.home_address_needs_key
    PoiProvider.BAIDU -> R.string.home_address_needs_key_baidu
}
