package com.skkap.lookback.server.gateway.plugins

import com.skkap.ktor.client.plugins.auth.GoogleOauthRefreshTokenFailedException
import com.skkap.ktor.client.plugins.auth.GoogleOauthRefreshTokenRejectedException
import com.skkap.ktor.client.plugins.auth.GoogleRefreshTokenErrorResponse
import com.skkap.ktor.client.plugins.auth.GoogleRefreshTokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.util.AttributeKey
import io.ktor.util.InternalAPI
import io.ktor.util.KtorDsl

@KtorDsl
class UserAwareGoogleAuth private constructor(
    val googleClientId: String,
    val googleClientSecret: String,
    val fetchAccessToken: suspend (uid: String) -> String,
    val fetchRefreshToken: suspend (uid: String) -> String,
    val accessTokenUpdatedCallback: suspend (
        uid: String,
        accessToken: String,
        expiresInSeconds: Long,
        scope: String?,
        idToken: String?,
    ) -> Unit,
    val refreshTokenRejectedCallback: suspend (uid: String) -> Unit,
) {
    class Config {
        var googleClientId: String? = null
        var googleClientSecret: String? = null
        var fetchAccessToken: suspend (uid: String) -> String = { throw Exception("Specify fetchAccessToken") }
        var fetchRefreshToken: suspend (uid: String) -> String = { throw Exception("Specify fetchRefreshToken") }
        var accessTokenUpdatedCallback: suspend (
            uid: String,
            accessToken: String,
            expiresInSeconds: Long,
            scope: String?,
            idToken: String?,
        ) -> Unit = { _: String, _: String, _: Long, _: String?, _: String? -> }
        var refreshTokenRejectedCallback: suspend (uid: String) -> Unit = {}
    }

    companion object : HttpClientPlugin<Config, UserAwareGoogleAuth> {
        override val key: AttributeKey<UserAwareGoogleAuth> = AttributeKey("UserAwareGoogleAuth")

        /**
         * Shows that request should skip auth and refresh token procedure.
         */
        val CircuitBreakerAttributeKey: AttributeKey<Unit> = AttributeKey("non-auth-request")

        /**
         * Shows that request should skip auth and refresh token procedure.
         */
        val UidAttributeKey: AttributeKey<String> = AttributeKey("uid")

        override fun prepare(block: Config.() -> Unit): UserAwareGoogleAuth {
            val config = Config().apply(block)
            return UserAwareGoogleAuth(
                googleClientId = config.googleClientId ?: throw Exception("Specify googleClientId"),
                googleClientSecret = config.googleClientSecret ?: throw Exception("Specify googleClientSecret"),
                fetchAccessToken = config.fetchAccessToken,
                fetchRefreshToken = config.fetchRefreshToken,
                accessTokenUpdatedCallback = config.accessTokenUpdatedCallback,
                refreshTokenRejectedCallback = config.refreshTokenRejectedCallback,
            )
        }

        @OptIn(InternalAPI::class)
        override fun install(plugin: UserAwareGoogleAuth, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                if (context.attributes.contains(CircuitBreakerAttributeKey)) return@intercept
                val uid: String = context.attributes.getOrNull(UidAttributeKey)
                    ?: throw Exception("You did not specify uid attribute on the request.")
                val accessToken: String = plugin.fetchAccessToken(uid)
                setAuthHeaders(context, accessToken)
            }

            scope.plugin(HttpSend).intercept { context ->
                val origin = execute(context)
                if (origin.response.status != HttpStatusCode.Unauthorized) return@intercept origin
                // To skip the following logic for the refresh token calls
                if (origin.request.attributes.contains(CircuitBreakerAttributeKey)) return@intercept origin

                val uid: String = context.attributes.getOrNull(UidAttributeKey)
                    ?: throw Exception("You did not specify uid attribute on the request.")
                val refreshToken = plugin.fetchRefreshToken(uid)
                val refreshTokenResponse: HttpResponse = scope.submitForm(
                    url = "https://accounts.google.com/o/oauth2/token",
                    formParameters = Parameters.build {
                        append("grant_type", "refresh_token")
                        append("client_id", plugin.googleClientId)
                        append("client_secret", plugin.googleClientSecret)
                        append("refresh_token", refreshToken)
                    },
                ) {
                    attributes.put(CircuitBreakerAttributeKey, Unit)
                }
                when (refreshTokenResponse.status) {
                    HttpStatusCode.OK -> {
                        val tokenResponse: GoogleRefreshTokenResponse = refreshTokenResponse.body()
                        plugin.accessTokenUpdatedCallback(
                            uid,
                            tokenResponse.accessToken,
                            tokenResponse.expiresInSeconds,
                            tokenResponse.scope,
                            tokenResponse.idToken,
                        )
                        val request = HttpRequestBuilder()
                        request.takeFromWithExecutionContext(context)
                        setAuthHeaders(request, tokenResponse.accessToken)
                        request.attributes.put(CircuitBreakerAttributeKey, Unit)
                        return@intercept execute(request)
                    }
                    HttpStatusCode.BadRequest -> {
                        // See: https://www.oauth.com/oauth2-servers/access-tokens/access-token-response/
                        val errorResponse: GoogleRefreshTokenErrorResponse = refreshTokenResponse.body()
                        if (errorResponse.error == "invalid_grant") {
                            // This means user revoked application permissions or refresh token
                            // was rejected for some other reason. No need to retry again. Discard the refresh token.
                            plugin.refreshTokenRejectedCallback(uid)
                            throw GoogleOauthRefreshTokenRejectedException(
                                "Could not refresh access token, " +
                                        "as refresh token was rejected. Response from Google OAuth: $errorResponse"
                            )
                        }
                        throw GoogleOauthRefreshTokenFailedException(
                            "Could not refresh access token, as refresh" +
                                    " token failed for unknown reason. Response from Google OAuth: $errorResponse"
                        )
                    }
                    else -> {
                        val errorResponse: GoogleRefreshTokenErrorResponse = refreshTokenResponse.body()
                        throw GoogleOauthRefreshTokenFailedException(
                            "Could not refresh access token, as refresh " +
                                    "token failed for unknown reason. Response from Google OAuth: $errorResponse"
                        )
                    }
                }
            }
        }

        private fun setAuthHeaders(request: HttpRequestBuilder, accessToken: String) {
            request.headers {
                val tokenValue = "Bearer $accessToken"
                if (contains(HttpHeaders.Authorization)) {
                    remove(HttpHeaders.Authorization)
                }
                append(HttpHeaders.Authorization, tokenValue)
            }
        }
    }
}
