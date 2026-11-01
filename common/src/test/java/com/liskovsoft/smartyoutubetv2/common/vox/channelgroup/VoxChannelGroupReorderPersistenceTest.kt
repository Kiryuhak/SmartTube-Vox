package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxChannelGroupReorderPersistenceTest {

    private lateinit var context: Context
    private lateinit var manager: VoxChannelGroupManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Reset storage completely
        AppPrefs.instance(context).setProfileData("vox_channel_groups_data_v1", null)
        AppPrefs.instance(context).putString("vox_channel_groups_data_v1", null)

        manager = VoxChannelGroupManager.instance(context)
        for (g in manager.getGroups()) {
            manager.deleteGroup(g.id)
        }
        manager.setSelectedGroupId(null)
    }

    @Test
    fun testReorderingChannelsUpAndDown() {
        val group = manager.createGroup("Избранные каналы")
        val chA = "channel_A"
        val chB = "channel_B"
        val chC = "channel_C"
        val chD = "channel_D"

        manager.addChannelToGroup(group.id, chA, "Канал A")
        manager.addChannelToGroup(group.id, chB, "Канал B")
        manager.addChannelToGroup(group.id, chC, "Канал C")
        manager.addChannelToGroup(group.id, chD, "Канал D")

        assertEquals(listOf(chA, chB, chC, chD), manager.getOrderedChannelIdsInGroup(group.id))

        // Move B up -> [B, A, C, D]
        assertTrue(manager.moveChannelUp(group.id, chB))
        assertEquals(listOf(chB, chA, chC, chD), manager.getOrderedChannelIdsInGroup(group.id))

        // Move B up again (already at top) -> returns false, no change
        assertTrue(!manager.moveChannelUp(group.id, chB))
        assertEquals(listOf(chB, chA, chC, chD), manager.getOrderedChannelIdsInGroup(group.id))

        // Move D up -> [B, A, D, C]
        assertTrue(manager.moveChannelUp(group.id, chD))
        assertEquals(listOf(chB, chA, chD, chC), manager.getOrderedChannelIdsInGroup(group.id))

        // Move A down -> [B, D, A, C]
        assertTrue(manager.moveChannelDown(group.id, chA))
        assertEquals(listOf(chB, chD, chA, chC), manager.getOrderedChannelIdsInGroup(group.id))

        // Move C down (already at bottom) -> returns false, no change
        assertTrue(!manager.moveChannelDown(group.id, chC))
        assertEquals(listOf(chB, chD, chA, chC), manager.getOrderedChannelIdsInGroup(group.id))
    }

    @Test
    fun testDualPersistenceProfileAndSharedPreferences() {
        val group = manager.createGroup("Тест сохранения")
        manager.addChannelToGroup(group.id, "ch1", "Первый")
        manager.addChannelToGroup(group.id, "ch2", "Второй")
        manager.moveChannelUp(group.id, "ch2") // [ch2, ch1]

        // Check primary storage (AppPrefs profile data)
        val profileJson = AppPrefs.instance(context).getProfileData("vox_channel_groups_data_v1")
        assertNotNull("Primary profile storage must contain saved JSON", profileJson)
        assertTrue(profileJson!!.contains("ch1"))
        assertTrue(profileJson.contains("ch2"))

        // Check secondary storage (SharedPreferences fallback backup)
        val backupJson = AppPrefs.instance(context).getString("vox_channel_groups_data_v1", null)
        assertNotNull("SharedPreferences backup must contain saved JSON", backupJson)
        assertEquals("Primary and backup JSON representations must match", profileJson, backupJson)
    }

    @Test
    fun testFallbackRecoveryFromSharedPreferencesWhenProfileIsWiped() {
        val group = manager.createGroup("Надёжная группа")
        manager.addChannelToGroup(group.id, "ch_alpha", "Альфа")
        manager.addChannelToGroup(group.id, "ch_beta", "Бета")
        manager.moveChannelUp(group.id, "ch_beta") // [ch_beta, ch_alpha]

        assertEquals(listOf("ch_beta", "ch_alpha"), manager.getOrderedChannelIdsInGroup(group.id))

        // Simulate Dune HD / Haier TV file wipe: profile data becomes null
        AppPrefs.instance(context).setProfileData("vox_channel_groups_data_v1", null)

        // Instantiate new storage directly and load
        val storage = VoxChannelGroupStorage(context)
        val (restoredGroups, restoredMemberships) = storage.load()
        assertNotNull(restoredGroups)
        assertEquals(1, restoredGroups.size)
        assertEquals("Надёжная группа", restoredGroups[0].name)

        val orderedIds = restoredMemberships
            .filter { it.groupId == group.id }
            .sortedBy { it.orderIndex }
            .map { it.channelId }

        assertEquals("Order must be restored identically from SharedPreferences backup",
            listOf("ch_beta", "ch_alpha"), orderedIds)
    }

    @Test
    fun testFallbackRecoveryFromSharedPreferencesWhenProfileIsCorrupted() {
        val group = manager.createGroup("Устойчивая группа")
        manager.addChannelToGroup(group.id, "ch_x", "X")
        manager.addChannelToGroup(group.id, "ch_y", "Y")

        // Simulate file corruption: profile data contains truncated garbage
        AppPrefs.instance(context).setProfileData("vox_channel_groups_data_v1", "{ \"version\": 1, \"groups\": [ {\"id\": \"incomplete...")

        // Storage load must not crash and must cleanly restore from clean SharedPreferences backup
        val storage = VoxChannelGroupStorage(context)
        val (restoredGroups, restoredMemberships) = storage.load()
        assertNotNull(restoredGroups)
        assertEquals(1, restoredGroups.size)
        assertEquals("Устойчивая группа", restoredGroups[0].name)
        assertEquals(2, restoredMemberships.size)

        // Verify that profile data was automatically repaired with valid JSON
        val repairedProfileJson = AppPrefs.instance(context).getProfileData("vox_channel_groups_data_v1")
        assertNotNull(repairedProfileJson)
        assertTrue(repairedProfileJson!!.contains("Устойчивая группа"))
    }

    @Test
    fun testSortingMediaItemsMatchingUserChannelOrder() {
        // Simulates the exact logic implemented in BrowsePresenter.filterChannelMediaGroup
        val group = manager.createGroup("Музыка")
        manager.addChannelToGroup(group.id, "ch_rock", "Rock")
        manager.addChannelToGroup(group.id, "ch_jazz", "Jazz")
        manager.addChannelToGroup(group.id, "ch_classical", "Classical")

        // User reorders to: Jazz, Classical, Rock
        manager.moveChannelUp(group.id, "ch_jazz")       // [Jazz, Rock, Classical]
        manager.moveChannelDown(group.id, "ch_rock")     // [Jazz, Classical, Rock]

        val orderedChannelIds = manager.getOrderedChannelIdsInGroup(group.id)
        assertEquals(listOf("ch_jazz", "ch_classical", "ch_rock"), orderedChannelIds)

        // YouTube returns channels in arbitrary order: Rock, Classical, Jazz
        val itemRock = SimpleMediaItem.from(Video().apply { channelId = "ch_rock"; title = "Rock Channel" })
        val itemClassical = SimpleMediaItem.from(Video().apply { channelId = "ch_classical"; title = "Classical Channel" })
        val itemJazz = SimpleMediaItem.from(Video().apply { channelId = "ch_jazz"; title = "Jazz Channel" })

        val incomingList: List<MediaItem> = listOf(itemRock, itemClassical, itemJazz)

        // Apply sorting by orderedChannelIds.indexOf(channelId)
        val sortedList = incomingList
            .filter { it.channelId != null && orderedChannelIds.contains(it.channelId) }
            .sortedBy { orderedChannelIds.indexOf(it.channelId) }

        assertEquals(3, sortedList.size)
        assertEquals("ch_jazz", sortedList[0].channelId)
        assertEquals("ch_classical", sortedList[1].channelId)
        assertEquals("ch_rock", sortedList[2].channelId)
    }
}
