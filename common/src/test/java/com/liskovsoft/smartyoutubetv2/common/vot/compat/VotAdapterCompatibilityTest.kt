package com.liskovsoft.smartyoutubetv2.common.vot.compat

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslationResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class VotAdapterCompatibilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.application
        VotAdapterFactory.setVot1121ExperimentEnabled(false)
        VotAdapterFactory.setCustomAdapterForTesting(null)
    }

    @After
    fun tearDown() {
        VotAdapterFactory.setVot1121ExperimentEnabled(false)
        VotAdapterFactory.setCustomAdapterForTesting(null)
    }

    @Test
    fun testLegacyVotAdapter_metadataAndEnabledByDefault() {
        val adapter = LegacyVotAdapter(context)
        assertEquals("1.11.17", adapter.version)
        assertTrue("Legacy adapter must be enabled by default", adapter.isEnabled)
    }

    @Test
    fun testVot1121Adapter_disabledByDefault() {
        val adapter = Vot1121Adapter()
        assertEquals("1.12.1", adapter.version)
        assertFalse("VOT 1.12.1 adapter must be disabled by default", adapter.isEnabled)
    }

    @Test
    fun testVot1121Adapter_whenDisabled_requestFailsImmediately() {
        val adapter = Vot1121Adapter()
        val errorInvoked = AtomicBoolean(false)

        adapter.requestTranslation(
            "https://youtu.be/test12345",
            60000L,
            "en",
            "ru",
            object : VotTranslationAdapter.Callback {
                override fun onReady(response: VotTranslationResponse) {}
                override fun onWaiting(remainingSeconds: Int) {}
                override fun onError(error: Exception) {
                    errorInvoked.set(true)
                    assertTrue(error.message?.contains("disabled") == true)
                }
            }
        )

        assertTrue("Disabled adapter must immediately report error", errorInvoked.get())
    }

    @Test
    fun testVot1121Adapter_whenEnabled_proceedsSafely() {
        val adapter = Vot1121Adapter(true)
        assertTrue(adapter.isEnabled)

        val waitingInvoked = AtomicBoolean(false)
        adapter.requestTranslation(
            "https://youtu.be/test12345",
            60000L,
            "en",
            "ru",
            object : VotTranslationAdapter.Callback {
                override fun onReady(response: VotTranslationResponse) {}
                override fun onWaiting(remainingSeconds: Int) {
                    waitingInvoked.set(true)
                }
                override fun onError(error: Exception) {}
            }
        )

        assertTrue("Enabled experimental adapter must proceed to waiting/polling", waitingInvoked.get())
    }

    @Test
    fun testVot1121Adapter_cancellationSetsFlag() {
        val adapter = Vot1121Adapter(true)
        assertFalse(adapter.isCancelled)
        adapter.cancel()
        assertTrue(adapter.isCancelled)
    }

    @Test
    fun testVotAdapterFactory_defaultResolvesLegacyAdapter() {
        val adapter = VotAdapterFactory.getActiveAdapter(context)
        assertNotNull(adapter)
        assertTrue("Default adapter must be LegacyVotAdapter", adapter is LegacyVotAdapter)
        assertEquals("1.11.17", adapter.version)
        assertTrue(adapter.isEnabled)
    }

    @Test
    fun testVotAdapterFactory_experimentalFlagSwitchesToVot1121() {
        VotAdapterFactory.setVot1121ExperimentEnabled(true)
        val adapter = VotAdapterFactory.getActiveAdapter(context)
        assertNotNull(adapter)
        assertTrue("When experiment enabled, adapter must be Vot1121Adapter", adapter is Vot1121Adapter)
        assertEquals("1.12.1", adapter.version)
        assertTrue(adapter.isEnabled)

        // Switching back
        VotAdapterFactory.setVot1121ExperimentEnabled(false)
        val restored = VotAdapterFactory.getActiveAdapter(context)
        assertTrue("Switching back must restore LegacyVotAdapter", restored is LegacyVotAdapter)
        assertEquals("1.11.17", restored.version)
    }
}
