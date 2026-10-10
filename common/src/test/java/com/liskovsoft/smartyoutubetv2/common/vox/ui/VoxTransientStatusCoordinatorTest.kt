package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxTransientStatusCoordinatorTest {

    private lateinit var root: FrameLayout
    private lateinit var translationView: View
    private lateinit var downloadView: View

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        root = FrameLayout(context)
        translationView = View(context)
        downloadView = View(context)
        root.addView(translationView)
        root.addView(downloadView)
        VoxTransientStatusCoordinator.clear()
    }

    @Test
    fun translationCanBeClaimedWhenNoDownloadActive() {
        assertTrue(VoxTransientStatusCoordinator.canPresent(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertTrue(VoxTransientStatusCoordinator.claim(translationView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertEquals(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION, VoxTransientStatusCoordinator.getActiveOwner())
    }

    @Test
    fun downloadReplacesTranslationAndSuppressesLateTranslationToasts() {
        assertTrue(VoxTransientStatusCoordinator.claim(translationView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertEquals(View.VISIBLE, translationView.visibility)

        // Download starts -> should preempt translation and hide it
        assertTrue(VoxTransientStatusCoordinator.claim(downloadView, VoxTransientStatusCoordinator.StatusOwner.DOWNLOAD))
        assertEquals(View.GONE, translationView.visibility)
        assertTrue(VoxTransientStatusCoordinator.isDownloadActive())
        assertTrue(VoxTransientStatusCoordinator.shouldSuppressTranslationToast())

        // Late translation toast attempts to show -> must be rejected
        assertFalse(VoxTransientStatusCoordinator.canPresent(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertFalse(VoxTransientStatusCoordinator.claim(translationView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertEquals(View.GONE, translationView.visibility)

        // Once download finishes and is released, translation is permitted again
        VoxTransientStatusCoordinator.release(downloadView, VoxTransientStatusCoordinator.StatusOwner.DOWNLOAD)
        assertFalse(VoxTransientStatusCoordinator.isDownloadActive())
        assertTrue(VoxTransientStatusCoordinator.canPresent(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
        assertTrue(VoxTransientStatusCoordinator.claim(translationView, VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
    }

    @Test
    fun notifyDownloadActiveExplicitlySuppressesTranslation() {
        VoxTransientStatusCoordinator.notifyDownloadActive(true)
        assertTrue(VoxTransientStatusCoordinator.isDownloadActive())
        assertFalse(VoxTransientStatusCoordinator.canPresent(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))

        VoxTransientStatusCoordinator.notifyDownloadActive(false)
        assertFalse(VoxTransientStatusCoordinator.isDownloadActive())
        assertTrue(VoxTransientStatusCoordinator.canPresent(VoxTransientStatusCoordinator.StatusOwner.TRANSLATION))
    }
}
