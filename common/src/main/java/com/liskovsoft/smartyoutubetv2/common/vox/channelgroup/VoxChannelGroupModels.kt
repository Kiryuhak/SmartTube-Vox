package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import org.json.JSONObject
import java.util.UUID

/**
 * Пользовательская локальная группа каналов VOX (коллекция).
 */
data class VoxChannelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var sortOrder: Int = 0
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("createdAt", createdAt)
        json.put("updatedAt", updatedAt)
        json.put("sortOrder", sortOrder)
        return json
    }

    companion object {
        const val MAX_NAME_LENGTH = 50

        fun sanitizeName(raw: String?): String {
            return raw?.trim() ?: ""
        }

        fun fromJson(json: JSONObject): VoxChannelGroup? {
            val id = json.optString("id")
            val name = json.optString("name")
            if (id.isNullOrBlank() || name.isNullOrBlank()) {
                return null
            }
            val createdAt = json.optLong("createdAt", System.currentTimeMillis())
            val updatedAt = json.optLong("updatedAt", createdAt)
            val sortOrder = json.optInt("sortOrder", 0)
            return VoxChannelGroup(id, name, createdAt, updatedAt, sortOrder)
        }
    }
}

/**
 * Привязка канала к пользовательской группе.
 */
data class VoxChannelGroupMembership(
    val groupId: String,
    val channelId: String,
    var channelTitle: String? = null,
    var channelAvatarUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("groupId", groupId)
        json.put("channelId", channelId)
        if (channelTitle != null) json.put("channelTitle", channelTitle)
        if (channelAvatarUrl != null) json.put("channelAvatarUrl", channelAvatarUrl)
        json.put("addedAt", addedAt)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): VoxChannelGroupMembership? {
            val groupId = json.optString("groupId")
            val channelId = json.optString("channelId")
            if (groupId.isNullOrBlank() || channelId.isNullOrBlank()) {
                return null
            }
            val title = if (json.has("channelTitle") && !json.isNull("channelTitle")) json.optString("channelTitle") else null
            val avatar = if (json.has("channelAvatarUrl") && !json.isNull("channelAvatarUrl")) json.optString("channelAvatarUrl") else null
            val addedAt = json.optLong("addedAt", System.currentTimeMillis())
            return VoxChannelGroupMembership(groupId, channelId, title, avatar, addedAt)
        }
    }
}

enum class VoxChannelGroupErrorCode {
    INVALID_NAME,
    DUPLICATE_NAME,
    GROUP_NOT_FOUND,
    STORAGE_ERROR
}

class VoxChannelGroupException(
    val errorCode: VoxChannelGroupErrorCode,
    message: String
) : Exception(message)
