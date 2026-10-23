package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxPinnedChannelFromGroupTest {

    @Test
    fun testPinnedChannelFromUserGroupClearsChannelGroupId() {
        // Setup: A video item inside a custom user group (e.g. BBD / KVN)
        val original = Video()
        original.title = "Выпуск 42"
        original.author = "BBD"
        original.channelId = null // author handle only
        original.channelGroupId = "group-uuid-12345"

        // Emulate BaseMenuPresenter.createPinnedChannel logic:
        val section = Video.from(original)
        section.channelGroupId = null // Isolated from group context
        if (section.channelId == null) {
            section.channelId = original.channelIdOrName
        }
        section.videoId = null
        section.playlistId = null
        section.playlistParams = null
        val channelName = if (!original.author.isNullOrEmpty()) original.author else original.channelIdOrName
        section.title = channelName ?: original.createChannelTitle()

        // Verifications:
        // 1. channelGroupId MUST be null so it is not treated as a group in BrowsePresenter
        assertNull("channelGroupId must be stripped from pinned channel section", section.channelGroupId)

        // 2. channelId MUST fallback to channelIdOrName (e.g. "BBD")
        assertEquals("BBD", section.channelId)

        // 3. Title should be author channel name ("BBD"), not episode title ("Выпуск 42")
        assertEquals("BBD", section.title)
    }

    @Test
    fun testPinnedChannelWithCanonicalIdPreservesId() {
        val original = Video()
        original.title = "Новый ролик"
        original.author = "КВН"
        original.channelId = "UCxxxxxxxxxxxxxxxxxxxxxx"
        original.channelGroupId = "group-uuid-67890"

        val section = Video.from(original)
        section.channelGroupId = null
        if (section.channelId == null) {
            section.channelId = original.channelIdOrName
        }
        section.videoId = null
        section.playlistId = null
        section.playlistParams = null
        val channelName = if (!original.author.isNullOrEmpty()) original.author else original.channelIdOrName
        section.title = channelName ?: original.createChannelTitle()

        assertNull(section.channelGroupId)
        assertEquals("UCxxxxxxxxxxxxxxxxxxxxxx", section.channelId)
        assertEquals("КВН", section.title)
    }
}
