package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderFailureCategory
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderResult
import java.io.IOException

internal class ProviderHttpException(val provider: String, val code: Int) : IOException("$provider HTTP $code") {
    fun unavailable() = ProviderResult.Unavailable(
        category = when (code) {
            401, 403 -> ProviderFailureCategory.AUTHENTICATION
            in 400..499 -> ProviderFailureCategory.CLIENT_REQUEST
            else -> ProviderFailureCategory.SERVER
        },
        message = message,
        retryable = code >= 500,
    )
}
