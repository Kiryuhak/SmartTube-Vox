package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxStatusHostTest {
    @Test fun downloadReplacesTranslationAndRejectsLateReadyMessage() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val translation = View(context)
        val download = View(context)
        root.addView(translation); root.addView(download)
        assertTrue(VoxStatusHost.claim(translation, VoxStatusHost.TRANSLATION))
        assertTrue(VoxStatusHost.claim(download, VoxStatusHost.DOWNLOAD))
        assertEquals(View.GONE, translation.visibility)
        assertFalse(VoxStatusHost.claim(translation, VoxStatusHost.TRANSLATION))
        download.visibility = View.GONE
        VoxStatusHost.release(download)
        assertTrue(VoxStatusHost.claim(translation, VoxStatusHost.TRANSLATION))
    }
}
