package com.liskovsoft.smartyoutubetv2.common.vox.account

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexOAuthTokenStore
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Native manager for Yandex OAuth account tokens and lifecycle matching VOT userscript 1.11.17.
 *
 * References:
 * - src/utils/account.ts (hasAccountToken, isAccountExpired, hasValidAccountToken, deleteAccount, deleteExpiredAccount)
 * - src/core/auth.ts (VOTLoginViaToken default expiry: 3153418e4 ms ~ 1 year)
 * - src/core/translationAuthError.ts (status 7: session-expired if hasToken else account-required)
 */
class VoxAccountManager @JvmOverloads constructor(
    private val tokenStore: YandexOAuthTokenStore,
    private val votData: VotData? = null
) {
    companion object {
        /** Default token lifetime for manual login via token: 3153418e4 ms (~1 year) from userscript */
        const val DEFAULT_MANUAL_EXPIRY_SECONDS = 31534180L

        @Volatile
        private var sInstance: VoxAccountManager? = null

        @JvmStatic
        fun instance(context: Context): VoxAccountManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxAccountManager(
                    YandexOAuthTokenStore.instance(context.applicationContext),
                    VotData.instance(context.applicationContext)
                ).also { sInstance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        @JvmStatic
        fun resetForTesting() {
            sInstance = null
        }
    }

    private var cachedAccountRequired: Boolean = false

    /**
     * Retrieves currently stored token if present.
     */
    fun getAccountToken(): VoxAccountToken? {
        val rawToken = tokenStore.accessToken
        if (rawToken.isNullOrBlank()) return null
        return VoxAccountToken(rawToken, tokenStore.expiresAt)
    }

    /**
     * Determines the current account state.
     */
    @JvmOverloads
    fun getAccountState(nowMs: Long = System.currentTimeMillis()): VoxAccountState {
        val token = getAccountToken()
        if (token == null || token.token.isBlank()) {
            return if (cachedAccountRequired) VoxAccountState.ACCOUNT_REQUIRED else VoxAccountState.ANONYMOUS
        }

        if (token.isExpired(nowMs)) {
            return VoxAccountState.TOKEN_EXPIRED
        }

        return when (tokenStore.authState) {
            VotData.AuthState.REJECTED -> VoxAccountState.TOKEN_EXPIRED
            VotData.AuthState.CONFIRMED, VotData.AuthState.UNVERIFIED -> VoxAccountState.AUTHENTICATED
            VotData.AuthState.ABSENT -> VoxAccountState.ANONYMOUS
            else -> VoxAccountState.AUTHENTICATED
        }
    }

    /**
     * Checks if a valid, unexpired token exists.
     */
    @JvmOverloads
    fun hasValidAccountToken(nowMs: Long = System.currentTimeMillis()): Boolean {
        val token = getAccountToken() ?: return false
        if (!token.isValid(nowMs)) return false
        return tokenStore.authState != VotData.AuthState.REJECTED
    }

    /**
     * Saves or updates a token (e.g. from manual entry via dialog or external flow).
     * Defaults to ~1 year expiration matching userscript.
     */
    @JvmOverloads
    fun saveToken(token: String, expiresInSeconds: Long = DEFAULT_MANUAL_EXPIRY_SECONDS) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clearAccount()
            return
        }
        cachedAccountRequired = false
        tokenStore.saveTokens(trimmed, null, expiresInSeconds)
        votData?.setLivelyVoiceEnabled(true)

        val masked = VoxAccountToken(trimmed, tokenStore.expiresAt).maskToken()
        val ctx = mapOf("token" to masked, "expiresAt" to tokenStore.expiresAt.toString())
        VoxSafeLogger.i(
            VoxLogCategory.YANDEX_AUTH,
            VoxLogCode.VOT_ACCOUNT_AUTHENTICATED,
            "Saved Yandex OAuth token (masked: $masked)",
            ctx
        )
    }

    /**
     * Clears token and resets to anonymous state.
     */
    fun clearAccount() {
        cachedAccountRequired = false
        tokenStore.clear()
        votData?.setLivelyVoiceEnabled(false)
        VoxSafeLogger.i(
            VoxLogCategory.YANDEX_AUTH,
            VoxLogCode.VOT_ACCOUNT_ANONYMOUS,
            "Cleared Yandex OAuth account",
            null
        )
    }

    /**
     * Pre-request validation: checks if token has expired and marks state if so.
     * Returns true if expired.
     */
    @JvmOverloads
    fun checkTokenExpiration(nowMs: Long = System.currentTimeMillis()): Boolean {
        val token = getAccountToken() ?: return false
        if (token.isExpired(nowMs)) {
            markTokenExpired(token.token)
            return true
        }
        return false
    }

    /**
     * Marks token as expired/rejected by server. Token is preserved in store so user can inspect or re-enter.
     */
    @JvmOverloads
    fun markTokenExpired(token: String? = null) {
        val currentToken = token ?: tokenStore.accessToken
        tokenStore.markRejected(currentToken)
        votData?.setLivelyVoiceEnabled(false)

        val masked = currentToken?.let { VoxAccountToken(it, 0).maskToken() } ?: "none"
        val ctx = mapOf("token" to masked, "reason" to "token_expired")
        VoxSafeLogger.w(
            VoxLogCategory.YANDEX_AUTH,
            VoxLogCode.VOT_ACCOUNT_TOKEN_EXPIRED,
            "Yandex OAuth token expired or rejected by server",
            ctx
        )
    }

    /**
     * Marks that the server reported account required for a video.
     */
    fun markAccountRequired() {
        cachedAccountRequired = true
        VoxSafeLogger.w(
            VoxLogCategory.YANDEX_AUTH,
            VoxLogCode.VOT_ACCOUNT_REQUIRED,
            "Video translation requires Yandex account",
            null
        )
    }

    /**
     * Marks authentication failure (HTTP 401).
     */
    @JvmOverloads
    fun markAuthError(rejectedToken: String? = null) {
        val currentToken = rejectedToken ?: tokenStore.accessToken
        tokenStore.markRejected(currentToken)
        votData?.setLivelyVoiceEnabled(false)

        val masked = currentToken?.let { VoxAccountToken(it, 0).maskToken() } ?: "none"
        val ctx = mapOf("token" to masked, "httpCode" to "401")
        VoxSafeLogger.e(
            VoxLogCategory.YANDEX_AUTH,
            VoxLogCode.VOT_ACCOUNT_AUTH_FAILED,
            "Yandex OAuth authorization failed (HTTP 401)",
            ctx
        )
    }

    /**
     * Marks token as successfully confirmed by server.
     */
    @JvmOverloads
    fun markTokenConfirmed(token: String? = null) {
        tokenStore.markConfirmed(token)
    }

    /**
     * Classifies response status code from backend into typed auth error kind.
     * Matches src/core/translationAuthError.ts in reference userscript.
     */
    fun classifyAuthResponse(status: Int, hasTokenSent: Boolean): VoxAuthErrorKind? {
        if (status == 7 /* STATUS_SESSION_REQUIRED */) {
            return if (hasTokenSent) VoxAuthErrorKind.SESSION_EXPIRED else VoxAuthErrorKind.ACCOUNT_REQUIRED
        }
        if (status == 401) {
            return VoxAuthErrorKind.INVALID_TOKEN
        }
        return null
    }

    /**
     * Handles auth response error kind and updates state / logs accordingly.
     */
    @JvmOverloads
    fun handleAuthErrorKind(errorKind: VoxAuthErrorKind, tokenUsed: String? = null) {
        when (errorKind) {
            VoxAuthErrorKind.SESSION_EXPIRED -> markTokenExpired(tokenUsed)
            VoxAuthErrorKind.ACCOUNT_REQUIRED -> markAccountRequired()
            VoxAuthErrorKind.INVALID_TOKEN -> markAuthError(tokenUsed)
        }
    }
}
