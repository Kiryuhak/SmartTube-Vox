package com.liskovsoft.smartyoutubetv2.common.vox.account

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexOAuthTokenStore
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class VoxAccountManagerTest {
    private lateinit var context: Context
    private lateinit var tokenStore: YandexOAuthTokenStore
    private lateinit var votData: VotData
    private lateinit var accountManager: VoxAccountManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        YandexOAuthTokenStore.setTestSecretKey(SecretKeySpec(keyBytes, "AES"))
        YandexOAuthTokenStore.resetForTesting()
        VotData.resetForTesting()
        VoxAccountManager.resetForTesting()

        tokenStore = YandexOAuthTokenStore.instance(context)
        tokenStore.clear()
        votData = VotData.instance(context)
        votData.clearOAuthToken()

        accountManager = VoxAccountManager(tokenStore, votData)
    }

    @Test
    fun testNoToken_StateIsAnonymous() {
        assertEquals(VoxAccountState.ANONYMOUS, accountManager.getAccountState())
        assertFalse(accountManager.hasValidAccountToken())
        assertNull(accountManager.getAccountToken())
    }

    @Test
    fun testSaveToken_StateIsAuthenticated() {
        val testToken = "y0_test_oauth_token_secret_12345"
        accountManager.saveToken(testToken, 3600L)

        val retrieved = accountManager.getAccountToken()
        assertNotNull(retrieved)
        assertEquals(testToken, retrieved?.token)
        assertTrue(accountManager.hasValidAccountToken())
        assertEquals(VoxAccountState.AUTHENTICATED, accountManager.getAccountState())
        assertTrue(votData.isLivelyVoiceEnabled())
    }

    @Test
    fun testTokenExpiration_TriggersExpiredState() {
        val testToken = "y0_expiring_token_999"
        accountManager.saveToken(testToken, 10L) // 10 seconds

        val now = System.currentTimeMillis()
        assertFalse(accountManager.checkTokenExpiration(now))
        assertTrue(accountManager.hasValidAccountToken(now))

        // Check in the future (+20s)
        val future = now + 20_000L
        assertTrue(accountManager.checkTokenExpiration(future))
        assertEquals(VoxAccountState.TOKEN_EXPIRED, accountManager.getAccountState(future))
        assertFalse(accountManager.hasValidAccountToken(future))
        assertFalse(votData.isLivelyVoiceEnabled())
    }

    @Test
    fun testClassifyAuthResponse_Status7WithToken_IsSessionExpired() {
        val kind = accountManager.classifyAuthResponse(7, hasTokenSent = true)
        assertEquals(VoxAuthErrorKind.SESSION_EXPIRED, kind)

        accountManager.saveToken("y0_token_for_status7", 3600L)
        accountManager.handleAuthErrorKind(kind!!)

        assertEquals(VoxAccountState.TOKEN_EXPIRED, accountManager.getAccountState())
        assertFalse(votData.isLivelyVoiceEnabled())
    }

    @Test
    fun testClassifyAuthResponse_Status7WithoutToken_IsAccountRequired() {
        val kind = accountManager.classifyAuthResponse(7, hasTokenSent = false)
        assertEquals(VoxAuthErrorKind.ACCOUNT_REQUIRED, kind)

        accountManager.handleAuthErrorKind(kind!!)
        assertEquals(VoxAccountState.ACCOUNT_REQUIRED, accountManager.getAccountState())
    }

    @Test
    fun testClassifyAuthResponse_Status401_IsInvalidToken() {
        val kind = accountManager.classifyAuthResponse(401, hasTokenSent = true)
        assertEquals(VoxAuthErrorKind.INVALID_TOKEN, kind)

        accountManager.saveToken("y0_bad_token", 3600L)
        accountManager.handleAuthErrorKind(kind!!)

        assertEquals(VoxAccountState.TOKEN_EXPIRED, accountManager.getAccountState())
        assertEquals(VotData.AuthState.REJECTED, tokenStore.authState)
    }

    @Test
    fun testClearAccount_ResetsToAnonymous() {
        accountManager.saveToken("y0_token_to_clear", 3600L)
        assertTrue(accountManager.hasValidAccountToken())

        accountManager.clearAccount()
        assertEquals(VoxAccountState.ANONYMOUS, accountManager.getAccountState())
        assertFalse(accountManager.hasValidAccountToken())
        assertNull(accountManager.getAccountToken())
        assertFalse(votData.isLivelyVoiceEnabled())
    }

    @Test
    fun testZeroLogLeakage_MaskToken() {
        val token = VoxAccountToken("y0_secretToken_12345", 0L)
        val masked = token.maskToken()

        assertFalse(masked.contains("secretToken"))
        assertTrue(masked.startsWith("y0_"))
        assertTrue(masked.endsWith("45"))
        assertTrue(token.toString().contains("masked="))
        assertFalse(token.toString().contains("secretToken"))
    }
}
