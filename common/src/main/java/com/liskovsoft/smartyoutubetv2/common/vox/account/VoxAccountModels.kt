package com.liskovsoft.smartyoutubetv2.common.vox.account

/**
 * High-level lifecycle state for Yandex OAuth account in VOT.
 */
enum class VoxAccountState {
    /** No token configured (standard anonymous translation). */
    ANONYMOUS,
    /** Token configured, unexpired, and accepted/valid. */
    AUTHENTICATED,
    /** Token configured but expired by timestamp or rejected by server (status 7 with token, or HTTP 401). */
    TOKEN_EXPIRED,
    /** Server requested account authorization (status 7 without token). */
    ACCOUNT_REQUIRED,
    /** Authentication failed (HTTP 401 / invalid token). */
    AUTH_ERROR
}

/**
 * Kind of authentication error classified from backend response.
 */
enum class VoxAuthErrorKind {
    /** Session/token expired on server (status 7 with token). */
    SESSION_EXPIRED,
    /** Video or feature requires account (status 7 without token). */
    ACCOUNT_REQUIRED,
    /** Token invalid or rejected (HTTP 401). */
    INVALID_TOKEN
}

/**
 * Safe immutable token model with zero secret leakage.
 */
data class VoxAccountToken(
    val token: String,
    val expiresAt: Long = 0L // epoch ms, 0 means unknown/indefinite
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return expiresAt in 1..nowMs
    }

    fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean {
        return token.isNotBlank() && !isExpired(nowMs)
    }

    fun maskToken(): String {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= 6) {
            "***"
        } else {
            "${trimmed.take(3)}****${trimmed.takeLast(2)}"
        }
    }

    override fun toString(): String {
        return "VoxAccountToken(masked=${maskToken()}, expiresAt=$expiresAt)"
    }
}
