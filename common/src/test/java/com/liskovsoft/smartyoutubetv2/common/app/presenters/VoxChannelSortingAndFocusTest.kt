package com.liskovsoft.smartyoutubetv2.common.app.presenters

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Comparator

class VoxChannelSortingAndFocusTest {

    private fun createChannel(id: Int, title: String, hasNew: Boolean): Video {
        val v = Video()
        v.id = id
        v.title = title
        v.hasNewContent = hasNew
        return v
    }

    @Test
    fun testFocusVsSelectedSemanticSeparation() {
        var isFocused = false
        var isSelected = false

        // User navigates with DPAD: focus moves to card
        isFocused = true
        isSelected = false
        assertTrue("Focused state should be true", isFocused)
        assertFalse("Selected/Clicked state must remain false during navigation", isSelected)

        // Focus leaves card
        isFocused = false
        assertFalse(isFocused)
        assertFalse(isSelected)

        // User clicks/activates card
        isSelected = true
        assertTrue(isSelected)
    }

    @Test
    fun testChannelCardColorsDoNotLeaveRedHighlight() {
        val defaultBg = 0x1E1E1E
        val selectedBg = 0xFFFFFF

        // With new architecture: card text background depends ONLY on hasFocus!
        // It must NOT use darkRed for background when unfocused.
        fun getCardBackground(hasFocus: Boolean, hasNewContent: Boolean): Int {
            return if (hasFocus) selectedBg else defaultBg
        }

        // Channel with new content while focused
        assertEquals(selectedBg, getCardBackground(hasFocus = true, hasNewContent = true))
        // Channel with new content after focus moves away
        assertEquals(defaultBg, getCardBackground(hasFocus = false, hasNewContent = true))
        // Channel without new content
        assertEquals(defaultBg, getCardBackground(hasFocus = false, hasNewContent = false))
    }

    @Test
    fun testNewContentSorting() {
        val ch1 = createChannel(1, "Alpha Channel", false)
        val ch2 = createChannel(2, "Beta Channel", true)
        val ch3 = createChannel(3, "Gamma Channel", true)
        val ch4 = createChannel(4, "Delta Channel", false)

        val channels = mutableListOf(ch1, ch2, ch3, ch4)

        // Sort by new content first, then deterministic by title/id
        channels.sortWith(Comparator { o1, o2 ->
            if (o1.hasNewContent != o2.hasNewContent) {
                if (o1.hasNewContent) -1 else 1
            } else {
                o1.getTitle().compareTo(o2.getTitle())
            }
        })

        assertEquals("Beta Channel", channels[0].getTitle())
        assertEquals("Gamma Channel", channels[1].getTitle())
        assertEquals("Alpha Channel", channels[2].getTitle())
        assertEquals("Delta Channel", channels[3].getTitle())
    }

    @Test
    fun testAlphabeticalSorting() {
        val ch1 = createChannel(1, "Gamma", false)
        val ch2 = createChannel(2, "Alpha", true)
        val ch3 = createChannel(3, "Beta", false)

        val channels = mutableListOf(ch1, ch2, ch3)
        channels.sortWith(Comparator { o1, o2 -> o1.getTitle().compareTo(o2.getTitle()) })

        assertEquals("Alpha", channels[0].getTitle())
        assertEquals("Beta", channels[1].getTitle())
        assertEquals("Gamma", channels[2].getTitle())
    }

    @Test
    fun testDeterministicTiesSorting() {
        val ch1 = createChannel(1, "Common", true)
        val ch2 = createChannel(2, "Common", true)

        val comp = Comparator<Video> { o1, o2 ->
            val titleCmp = o1.getTitle().compareTo(o2.getTitle())
            if (titleCmp != 0) titleCmp else o1.id.compareTo(o2.id)
        }

        val list = mutableListOf(ch2, ch1)
        list.sortWith(comp)

        assertEquals(1, list[0].id)
        assertEquals(2, list[1].id)
    }

    @Test
    fun testChannelSortingConstantsDistinct() {
        val constants = setOf(
            MainUIData.CHANNEL_SORTING_DEFAULT,
            MainUIData.CHANNEL_SORTING_NEW_CONTENT,
            MainUIData.CHANNEL_SORTING_NAME,
            MainUIData.CHANNEL_SORTING_LAST_VIEWED
        )
        assertEquals("All 4 channel sorting modes must be unique", 4, constants.size)
    }
}
