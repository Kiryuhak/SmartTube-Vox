package com.liskovsoft.smartyoutubetv2.common.vox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxSidebarMarqueePolicyTest {

    @Test
    fun testShouldStartMarqueeWhenFocusedAndClippedAndAttached() {
        assertTrue(
            VoxSidebarMarqueePolicy.shouldStartMarquee(
                isFocusedOrSelected = true,
                isTextClipped = true,
                isAttached = true
            )
        )
    }

    @Test
    fun testShouldNotStartMarqueeWhenUnfocused() {
        assertFalse(
            VoxSidebarMarqueePolicy.shouldStartMarquee(
                isFocusedOrSelected = false,
                isTextClipped = true,
                isAttached = true
            )
        )
    }

    @Test
    fun testShouldNotStartMarqueeWhenTextNotClipped() {
        assertFalse(
            VoxSidebarMarqueePolicy.shouldStartMarquee(
                isFocusedOrSelected = true,
                isTextClipped = false,
                isAttached = true
            )
        )
    }

    @Test
    fun testMarqueeConstants() {
        assertEquals(600L, VoxSidebarMarqueePolicy.MARQUEE_START_DELAY_MS)
        assertEquals(1.0f, VoxSidebarMarqueePolicy.MARQUEE_SPEED_FACTOR, 0.01f)
    }
}
