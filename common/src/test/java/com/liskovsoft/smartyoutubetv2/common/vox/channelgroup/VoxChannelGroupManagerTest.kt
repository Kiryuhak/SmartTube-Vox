package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxChannelGroupManagerTest {

    private lateinit var manager: VoxChannelGroupManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Reset profile data for test isolation
        AppPrefs.instance(context).setProfileData("vox_channel_groups_data_v1", null)
        manager = VoxChannelGroupManager.instance(context)
        // Clear all existing groups if any
        for (g in manager.getGroups()) {
            manager.deleteGroup(g.id)
        }
        manager.setSelectedGroupId(null)
    }

    @Test
    fun testCreateGroupSuccess() {
        val group = manager.createGroup("Новости")
        assertNotNull(group.id)
        assertEquals("Новости", group.name)
        assertTrue(group.createdAt > 0)
        assertEquals(1, manager.getGroups().size)

        val retrieved = manager.getGroup(group.id)
        assertNotNull(retrieved)
        assertEquals("Новости", retrieved?.name)
    }

    @Test
    fun testCreateGroupTrimAndValidation() {
        val group = manager.createGroup("  Технологии  ")
        assertEquals("Технологии", group.name)

        try {
            manager.createGroup("")
            fail("Expected INVALID_NAME for empty string")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.INVALID_NAME, e.errorCode)
        }

        try {
            manager.createGroup("   ")
            fail("Expected INVALID_NAME for whitespace string")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.INVALID_NAME, e.errorCode)
        }
    }

    @Test
    fun testCreateGroupTooLongName() {
        val longName = "A".repeat(51)
        try {
            manager.createGroup(longName)
            fail("Expected INVALID_NAME for name > 50 chars")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.INVALID_NAME, e.errorCode)
        }
    }

    @Test
    fun testCreateGroupDuplicateCaseInsensitive() {
        manager.createGroup("Игры")
        try {
            manager.createGroup("игры")
            fail("Expected DUPLICATE_NAME")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.DUPLICATE_NAME, e.errorCode)
        }

        try {
            manager.createGroup(" ИГРЫ ")
            fail("Expected DUPLICATE_NAME for uppercase trimmed")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.DUPLICATE_NAME, e.errorCode)
        }
    }

    @Test
    fun testRenameGroupSuccess() {
        val group = manager.createGroup("Обучение")
        val renamed = manager.renameGroup(group.id, "Образование")
        assertEquals("Образование", renamed.name)
        assertEquals("Образование", manager.getGroup(group.id)?.name)
    }

    @Test
    fun testRenameGroupDuplicateAndInvalid() {
        manager.createGroup("Музыка")
        val g2 = manager.createGroup("Кино")

        try {
            manager.renameGroup(g2.id, "музыка")
            fail("Expected DUPLICATE_NAME")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.DUPLICATE_NAME, e.errorCode)
        }

        try {
            manager.renameGroup(g2.id, "")
            fail("Expected INVALID_NAME")
        } catch (e: VoxChannelGroupException) {
            assertEquals(VoxChannelGroupErrorCode.INVALID_NAME, e.errorCode)
        }
    }

    @Test
    fun testDeleteGroupSuccess() {
        val group = manager.createGroup("Позже")
        manager.addChannelToGroup(group.id, "channel-123", "Тестовый Канал")
        manager.setSelectedGroupId(group.id)

        assertEquals(1, manager.getChannelCountInGroup(group.id))
        assertEquals(group.id, manager.selectedGroupId)

        val deleted = manager.deleteGroup(group.id)
        assertTrue(deleted)
        assertEquals(0, manager.getGroups().size)
        assertNull(manager.getGroup(group.id))
        assertNull(manager.selectedGroupId)
        assertEquals(0, manager.getChannelCountInGroup(group.id))
    }

    @Test
    fun testAddAndRemoveChannel() {
        val group = manager.createGroup("Детям")
        val chId = "UC_DISNEY_123"

        assertFalse(manager.isChannelInGroup(group.id, chId))

        val added = manager.addChannelToGroup(group.id, chId, "Disney Channel")
        assertTrue(added)
        assertTrue(manager.isChannelInGroup(group.id, chId))
        assertEquals(1, manager.getChannelCountInGroup(group.id))
        assertTrue(manager.getChannelIdsInGroup(group.id).contains(chId))

        val removed = manager.removeChannelFromGroup(group.id, chId)
        assertTrue(removed)
        assertFalse(manager.isChannelInGroup(group.id, chId))
        assertEquals(0, manager.getChannelCountInGroup(group.id))
    }

    @Test
    fun testIdempotentAddChannel() {
        val group = manager.createGroup("Техно")
        val chId = "UC_TECH_1"

        assertTrue(manager.addChannelToGroup(group.id, chId, "Tech 1"))
        assertTrue(manager.addChannelToGroup(group.id, chId, "Tech 1 Updated"))
        assertEquals(1, manager.getChannelCountInGroup(group.id))
    }

    @Test
    fun testMultipleGroupsPerChannel() {
        val g1 = manager.createGroup("Group A")
        val g2 = manager.createGroup("Group B")
        val chId = "UC_SHARED"

        manager.addChannelToGroup(g1.id, chId)
        manager.addChannelToGroup(g2.id, chId)

        assertTrue(manager.isChannelInGroup(g1.id, chId))
        assertTrue(manager.isChannelInGroup(g2.id, chId))

        val groupsForChannel = manager.getGroupIdsForChannel(chId)
        assertEquals(2, groupsForChannel.size)
        assertTrue(groupsForChannel.contains(g1.id))
        assertTrue(groupsForChannel.contains(g2.id))

        // Remove from G1 should not affect G2
        manager.removeChannelFromGroup(g1.id, chId)
        assertFalse(manager.isChannelInGroup(g1.id, chId))
        assertTrue(manager.isChannelInGroup(g2.id, chId))
    }

    @Test
    fun testToggleChannelInGroup() {
        val group = manager.createGroup("Toggle Test")
        val chId = "UC_TOGGLE"

        // First toggle: adds
        val state1 = manager.toggleChannelInGroup(group.id, chId)
        assertTrue(state1)
        assertTrue(manager.isChannelInGroup(group.id, chId))

        // Second toggle: removes
        val state2 = manager.toggleChannelInGroup(group.id, chId)
        assertFalse(state2)
        assertFalse(manager.isChannelInGroup(group.id, chId))
    }

    @Test
    fun testPersistenceAndRestore() {
        val context = RuntimeEnvironment.getApplication()
        val storage = VoxChannelGroupStorage(context)

        val g1 = VoxChannelGroup(name = "Сохранение 1")
        val g2 = VoxChannelGroup(name = "Сохранение 2")
        val m1 = VoxChannelGroupMembership(groupId = g1.id, channelId = "UC_SAVED", channelTitle = "Saved Channel")

        val saved = storage.save(listOf(g1, g2), listOf(m1))
        assertTrue(saved)

        val (loadedGroups, loadedMemberships) = storage.load()
        assertEquals(2, loadedGroups.size)
        assertEquals(1, loadedMemberships.size)
        assertEquals("Сохранение 1", loadedGroups[0].name)
        assertEquals("UC_SAVED", loadedMemberships[0].channelId)
    }

    @Test
    fun testStorageCorruptDataRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val storage = VoxChannelGroupStorage(context)

        AppPrefs.instance(context).setProfileData("vox_channel_groups_data_v1", "{ malformed json :::")

        val (groups, members) = storage.load()
        assertTrue(groups.isEmpty())
        assertTrue(members.isEmpty())
    }

    @Test
    fun testFilteredMediaGroup() {
        val g = manager.createGroup("Фильтр")
        manager.addChannelToGroup(g.id, "UC_ALLOWED")

        val v1 = com.liskovsoft.smartyoutubetv2.common.app.models.data.Video().apply {
            title = "Allowed Channel"
            channelId = "UC_ALLOWED"
        }
        val v2 = com.liskovsoft.smartyoutubetv2.common.app.models.data.Video().apply {
            title = "Other Channel"
            channelId = "UC_OTHER"
        }
        val item1 = SimpleMediaItem.from(v1)
        val item2 = SimpleMediaItem.from(v2)

        val dummyItems = listOf(item1, item2)

        val dummyGroup = object : MediaGroup {
            override fun getType(): Int = MediaGroup.TYPE_CHANNEL_UPLOADS
            override fun getMediaItems(): List<MediaItem> = dummyItems
            override fun getTitle(): String = "Original"
            override fun getChannelId(): String? = null
            override fun getParams(): String? = null
            override fun getReloadPageKey(): String? = null
            override fun getNextPageKey(): String? = null
            override fun getChannelUrl(): String? = null
            override fun isEmpty(): Boolean = dummyItems.isEmpty()
        }

        val channelIds = manager.getChannelIdsInGroup(g.id)
        val filteredItems = dummyGroup.mediaItems.filter { channelIds.contains(it.channelId) }
        val filteredGroup = VoxFilteredMediaGroup(dummyGroup, filteredItems)

        assertEquals(MediaGroup.TYPE_CHANNEL_UPLOADS, filteredGroup.type)
        assertEquals("Original", filteredGroup.title)
        assertFalse(filteredGroup.isEmpty)
        assertEquals(1, filteredGroup.mediaItems.size)
        assertEquals("UC_ALLOWED", filteredGroup.mediaItems[0].channelId)
    }
}
