package com.skkap.ktor.client.plugins.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleRefreshTokenErrorResponse(
    @SerialName("error") val error: String,
    @SerialName("error_description") val errorDescription: String?,
    @SerialName("error_uri") val errorUri: String?,
)
