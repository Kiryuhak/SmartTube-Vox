package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import android.annotation.SuppressLint
import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Менеджер локальных пользовательских групп подписок/каналов VOX.
 * Полностью изолирован от API YouTube (не вызывает subscribe/unsubscribe).
 */
class VoxChannelGroupManager private constructor(private val context: Context) : AppPrefs.ProfileChangeListener {

    interface OnGroupsChangedListener {
        fun onGroupsChanged()
    }

    companion object {
        private const val TAG = "VoxChannelGroupManager"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var sInstance: VoxChannelGroupManager? = null

        @JvmStatic
        fun instance(context: Context): VoxChannelGroupManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxChannelGroupManager(context.applicationContext).also {
                    sInstance = it
                }
            }
        }
    }

    private val storage = VoxChannelGroupStorage(context)
    private val groups = ConcurrentHashMap<String, VoxChannelGroup>()
    private val groupMembers = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val channelGroups = ConcurrentHashMap<String, MutableSet<String>>()
    private val memberships = ConcurrentHashMap<String, VoxChannelGroupMembership>()
    private val listeners = CopyOnWriteArrayList<OnGroupsChangedListener>()

    @Volatile
    var selectedGroupId: String? = null
        private set

    init {
        loadFromStorage()
        try {
            AppPrefs.instance(context).addListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register ProfileChangeListener: %s", e.message)
        }
    }

    @Synchronized
    private fun loadFromStorage() {
        groups.clear()
        groupMembers.clear()
        channelGroups.clear()
        memberships.clear()

        val (loadedGroups, loadedMemberships) = storage.load()
        for (g in loadedGroups) {
            groups[g.id] = g
            groupMembers[g.id] = CopyOnWriteArrayList()
        }

        // Sort memberships by orderIndex, then addedAt to strictly preserve custom order
        val sortedMemberships = loadedMemberships.sortedWith(compareBy({ it.orderIndex }, { it.addedAt }))
        for (m in sortedMemberships) {
            if (groups.containsKey(m.groupId)) {
                val key = membershipKey(m.groupId, m.channelId)
                memberships[key] = m
                val list = groupMembers.getOrPut(m.groupId) { CopyOnWriteArrayList() }
                if (!list.contains(m.channelId)) {
                    list.add(m.channelId)
                }
                channelGroups.getOrPut(m.channelId) { ConcurrentHashMap.newKeySet() }.add(m.groupId)
            }
        }

        if (selectedGroupId != null && !groups.containsKey(selectedGroupId)) {
            selectedGroupId = null
        }
    }

    override fun onProfileChanged() {
        Log.i(TAG, "Profile changed, reloading channel groups")
        synchronized(this) {
            loadFromStorage()
        }
        notifyListeners()
    }

    fun addListener(listener: OnGroupsChangedListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: OnGroupsChangedListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l.onGroupsChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying group listener: %s", e.message)
            }
        }
    }

    fun setSelectedGroupId(groupId: String?) {
        if (groupId != null && !groups.containsKey(groupId)) {
            selectedGroupId = null
        } else {
            selectedGroupId = groupId
        }
    }

    fun getSelectedGroup(): VoxChannelGroup? {
        val id = selectedGroupId ?: return null
        return groups[id]
    }

    fun getGroups(): List<VoxChannelGroup> {
        return groups.values
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    }

    fun getGroup(groupId: String): VoxChannelGroup? {
        return groups[groupId]
    }

    fun findGroupByName(name: String): VoxChannelGroup? {
        val clean = VoxChannelGroup.sanitizeName(name)
        return groups.values.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    fun getChannelCountInGroup(groupId: String): Int {
        return groupMembers[groupId]?.size ?: 0
    }

    fun getChannelIdsInGroup(groupId: String): Set<String> {
        return groupMembers[groupId]?.let { LinkedHashSet(it) } ?: emptySet()
    }

    fun getOrderedChannelIdsInGroup(groupId: String): List<String> {
        return groupMembers[groupId]?.toList() ?: emptyList()
    }

    fun getOrderedMembershipsInGroup(groupId: String): List<VoxChannelGroupMembership> {
        val members = groupMembers[groupId] ?: return emptyList()
        return members.mapNotNull { memberships[membershipKey(groupId, it)] }
    }

    fun getGroupIdsForChannel(channelId: String?): Set<String> {
        if (channelId.isNullOrBlank()) return emptySet()
        return channelGroups[channelId.trim()]?.toSet() ?: emptySet()
    }

    fun isChannelInGroup(groupId: String, channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        val members = groupMembers[groupId] ?: return false
        return members.contains(channelId.trim())
    }

    fun isChannelInAnyGroup(channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        val set = channelGroups[channelId.trim()] ?: return false
        return set.isNotEmpty()
    }

    @Synchronized
    fun createGroup(name: String): VoxChannelGroup {
        val cleanName = VoxChannelGroup.sanitizeName(name)
        if (cleanName.isEmpty()) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.INVALID_NAME,
                "Название группы не может быть пустым"
            )
        }
        if (cleanName.length > VoxChannelGroup.MAX_NAME_LENGTH) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.INVALID_NAME,
                "Название группы не может превышать ${VoxChannelGroup.MAX_NAME_LENGTH} символов"
            )
        }
        if (findGroupByName(cleanName) != null) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.DUPLICATE_NAME,
                "Группа с таким названием уже существует"
            )
        }

        val nextOrder = (groups.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val group = VoxChannelGroup(
            name = cleanName,
            sortOrder = nextOrder
        )
        groups[group.id] = group
        groupMembers[group.id] = CopyOnWriteArrayList()

        persist()

        VoxSafeLogger.i(
            VoxLogCategory.CHANNEL_GROUP,
            VoxLogCode.CHANNEL_GROUP_CREATED,
            "Создана группа каналов (всего: ${groups.size})"
        )

        notifyListeners()
        return group
    }

    @Synchronized
    fun renameGroup(groupId: String, newName: String): VoxChannelGroup {
        val group = groups[groupId]
            ?: throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.GROUP_NOT_FOUND,
                "Группа не найдена"
            )

        val cleanName = VoxChannelGroup.sanitizeName(newName)
        if (cleanName.isEmpty()) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.INVALID_NAME,
                "Название группы не может быть пустым"
            )
        }
        if (cleanName.length > VoxChannelGroup.MAX_NAME_LENGTH) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.INVALID_NAME,
                "Название группы не может превышать ${VoxChannelGroup.MAX_NAME_LENGTH} символов"
            )
        }

        val existing = findGroupByName(cleanName)
        if (existing != null && existing.id != groupId) {
            throw VoxChannelGroupException(
                VoxChannelGroupErrorCode.DUPLICATE_NAME,
                "Группа с таким названием уже существует"
            )
        }

        group.name = cleanName
        group.updatedAt = System.currentTimeMillis()

        if (group.isPinnedToSidebar) {
            val video = Video()
            video.title = cleanName
            video.channelGroupId = group.id
            try {
                BrowsePresenter.instance(context)?.renameSection(video)
                    ?: SidebarService.instance(context).renameSection(video.id, cleanName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not rename sidebar section: ${e.message}")
            }
        }

        persist()

        VoxSafeLogger.i(
            VoxLogCategory.CHANNEL_GROUP,
            VoxLogCode.CHANNEL_GROUP_RENAMED,
            "Переименована группа каналов"
        )

        notifyListeners()
        return group
    }

    @Synchronized
    fun deleteGroup(groupId: String): Boolean {
        val group = groups.remove(groupId) ?: return false

        if (group.isPinnedToSidebar) {
            val video = Video()
            video.title = group.name
            video.channelGroupId = group.id
            try {
                BrowsePresenter.instance(context)?.unpinItem(video)
                    ?: SidebarService.instance(context).removePinnedItem(video)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unpin group on delete: ${e.message}")
            }
        }

        val channelIds = groupMembers.remove(groupId) ?: emptyList()
        for (channelId in channelIds) {
            val set = channelGroups[channelId]
            set?.remove(groupId)
            if (set.isNullOrEmpty()) {
                channelGroups.remove(channelId)
            }
            memberships.remove(membershipKey(groupId, channelId))
        }

        if (selectedGroupId == groupId) {
            selectedGroupId = null
        }

        persist()

        VoxSafeLogger.i(
            VoxLogCategory.CHANNEL_GROUP,
            VoxLogCode.CHANNEL_GROUP_DELETED,
            "Удалена группа каналов (осталось: ${groups.size})"
        )

        notifyListeners()
        return true
    }

    @Synchronized
    fun addChannelToGroup(
        groupId: String,
        channelId: String,
        channelTitle: String? = null,
        channelAvatarUrl: String? = null
    ): Boolean {
        val cleanChannelId = channelId.trim()
        if (cleanChannelId.isEmpty() || !groups.containsKey(groupId)) {
            return false
        }

        val key = membershipKey(groupId, cleanChannelId)
        val existing = memberships[key]
        if (existing != null) {
            if (channelTitle != null) existing.channelTitle = channelTitle
            if (channelAvatarUrl != null) existing.channelAvatarUrl = channelAvatarUrl
            return true
        }

        val currentMembers = groupMembers.getOrPut(groupId) { CopyOnWriteArrayList() }
        val orderIndex = currentMembers.size
        val membership = VoxChannelGroupMembership(
            groupId = groupId,
            channelId = cleanChannelId,
            channelTitle = channelTitle,
            channelAvatarUrl = channelAvatarUrl,
            orderIndex = orderIndex
        )
        memberships[key] = membership
        if (!currentMembers.contains(cleanChannelId)) {
            currentMembers.add(cleanChannelId)
        }
        channelGroups.getOrPut(cleanChannelId) { ConcurrentHashMap.newKeySet() }.add(groupId)

        persist()

        VoxSafeLogger.i(
            VoxLogCategory.CHANNEL_GROUP,
            VoxLogCode.CHANNEL_GROUP_MEMBER_ADDED,
            "Канал добавлен в группу"
        )

        notifyListeners()
        return true
    }

    @Synchronized
    fun removeChannelFromGroup(groupId: String, channelId: String): Boolean {
        val cleanChannelId = channelId.trim()
        if (cleanChannelId.isEmpty()) return false

        val key = membershipKey(groupId, cleanChannelId)
        val removed = memberships.remove(key) != null
        val members = groupMembers[groupId]
        members?.remove(cleanChannelId)
        if (members != null) {
            updateOrderIndices(groupId, members)
        }

        val set = channelGroups[cleanChannelId]
        set?.remove(groupId)
        if (set.isNullOrEmpty()) {
            channelGroups.remove(cleanChannelId)
        }

        if (removed) {
            persist()

            VoxSafeLogger.i(
                VoxLogCategory.CHANNEL_GROUP,
                VoxLogCode.CHANNEL_GROUP_MEMBER_REMOVED,
                "Канал удалён из группы"
            )

            notifyListeners()
        }

        return removed
    }

    @Synchronized
    fun toggleChannelInGroup(
        groupId: String,
        channelId: String,
        channelTitle: String? = null,
        channelAvatarUrl: String? = null
    ): Boolean {
        return if (isChannelInGroup(groupId, channelId)) {
            removeChannelFromGroup(groupId, channelId)
            false
        } else {
            addChannelToGroup(groupId, channelId, channelTitle, channelAvatarUrl)
            true
        }
    }

    @Synchronized
    fun moveChannelUp(groupId: String, channelId: String): Boolean {
        val members = groupMembers[groupId] ?: return false
        val cleanId = channelId.trim()
        val index = members.indexOf(cleanId)
        if (index <= 0) return false

        val prevId = members[index - 1]
        members[index - 1] = cleanId
        members[index] = prevId
        updateOrderIndices(groupId, members)
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun moveChannelDown(groupId: String, channelId: String): Boolean {
        val members = groupMembers[groupId] ?: return false
        val cleanId = channelId.trim()
        val index = members.indexOf(cleanId)
        if (index < 0 || index >= members.size - 1) return false

        val nextId = members[index + 1]
        members[index + 1] = cleanId
        members[index] = nextId
        updateOrderIndices(groupId, members)
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun setChannelOrder(groupId: String, orderedChannelIds: List<String>): Boolean {
        val members = groupMembers[groupId] ?: return false
        val validIds = orderedChannelIds.map { it.trim() }.filter { members.contains(it) }
        val remaining = members.filter { !validIds.contains(it) }
        members.clear()
        members.addAll(validIds)
        members.addAll(remaining)
        updateOrderIndices(groupId, members)
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun setGroupPinnedToSidebar(groupId: String, pinned: Boolean): Boolean {
        val group = groups[groupId] ?: return false
        if (group.isPinnedToSidebar == pinned) return true
        group.isPinnedToSidebar = pinned
        group.updatedAt = System.currentTimeMillis()

        val video = Video()
        video.title = group.name
        video.channelGroupId = group.id

        try {
            val presenter = BrowsePresenter.instance(context)
            if (presenter != null && presenter.view != null) {
                if (pinned) {
                    presenter.pinItem(video)
                } else {
                    presenter.unpinItem(video)
                }
            } else {
                val sidebar = SidebarService.instance(context)
                if (pinned) {
                    sidebar.addPinnedItem(video)
                } else {
                    sidebar.removePinnedItem(video)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update sidebar for group ${group.id}: ${e.message}")
        }

        persist()
        notifyListeners()
        return true
    }

    fun isGroupPinnedToSidebar(groupId: String): Boolean {
        return groups[groupId]?.isPinnedToSidebar ?: false
    }

    private fun updateOrderIndices(groupId: String, orderedIds: List<String>) {
        orderedIds.forEachIndexed { idx, chId ->
            memberships[membershipKey(groupId, chId)]?.orderIndex = idx
        }
    }

    private fun membershipKey(groupId: String, channelId: String): String {
        return "$groupId:$channelId"
    }

    private fun persist() {
        storage.save(groups.values, memberships.values)
    }
}
