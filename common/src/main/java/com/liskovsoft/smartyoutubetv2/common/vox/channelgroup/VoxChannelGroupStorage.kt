package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import org.json.JSONArray
import org.json.JSONObject

class VoxChannelGroupStorage(private val context: Context) {
    companion object {
        private const val TAG = "VoxChannelGroupStorage"
        private const val PREF_KEY = "vox_channel_groups_data_v1"
        private const val CURRENT_VERSION = 1
    }

    private val prefs: AppPrefs by lazy { AppPrefs.instance(context) }

    @Synchronized
    fun load(): Pair<List<VoxChannelGroup>, List<VoxChannelGroupMembership>> {
        val raw = try {
            prefs.getProfileData(PREF_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting profile data: %s", e.message)
            null
        }

        if (raw.isNullOrBlank()) {
            return Pair(emptyList(), emptyList())
        }

        return try {
            val root = JSONObject(raw)
            val groupsList = mutableListOf<VoxChannelGroup>()
            val groupsArray = root.optJSONArray("groups")
            if (groupsArray != null) {
                for (i in 0 until groupsArray.length()) {
                    val groupJson = groupsArray.optJSONObject(i) ?: continue
                    val group = VoxChannelGroup.fromJson(groupJson)
                    if (group != null) {
                        groupsList.add(group)
                    }
                }
            }

            val membershipsList = mutableListOf<VoxChannelGroupMembership>()
            val membersArray = root.optJSONArray("memberships")
            if (membersArray != null) {
                for (i in 0 until membersArray.length()) {
                    val memberJson = membersArray.optJSONObject(i) ?: continue
                    val member = VoxChannelGroupMembership.fromJson(memberJson)
                    if (member != null) {
                        membershipsList.add(member)
                    }
                }
            }

            Pair(groupsList, membershipsList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse channel groups JSON: %s", e.message)
            VoxSafeLogger.e(
                VoxLogCategory.CHANNEL_GROUP,
                VoxLogCode.CHANNEL_GROUP_STORAGE_ERROR,
                "Ошибка чтения хранилища групп каналов: ${e.message}"
            )
            Pair(emptyList(), emptyList())
        }
    }

    @Synchronized
    fun save(groups: Collection<VoxChannelGroup>, memberships: Collection<VoxChannelGroupMembership>): Boolean {
        return try {
            val root = JSONObject()
            root.put("version", CURRENT_VERSION)

            val groupsArray = JSONArray()
            for (group in groups) {
                groupsArray.put(group.toJson())
            }
            root.put("groups", groupsArray)

            val membersArray = JSONArray()
            for (member in memberships) {
                membersArray.put(member.toJson())
            }
            root.put("memberships", membersArray)

            prefs.setProfileData(PREF_KEY, root.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize channel groups: %s", e.message)
            VoxSafeLogger.e(
                VoxLogCategory.CHANNEL_GROUP,
                VoxLogCode.CHANNEL_GROUP_STORAGE_ERROR,
                "Ошибка записи хранилища групп каналов: ${e.message}"
            )
            false
        }
    }
}
