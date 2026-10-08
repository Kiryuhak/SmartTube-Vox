package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem

/**
 * Обертка MediaGroup для фильтрации каналов по пользовательской группе VOX.
 */
class VoxFilteredMediaGroup(
    private val delegate: MediaGroup,
    private val filteredItems: List<MediaItem>
) : MediaGroup {
    override fun getType(): Int = delegate.type
    override fun getMediaItems(): List<MediaItem> = filteredItems
    override fun getTitle(): String? = delegate.title
    override fun getChannelId(): String? = delegate.channelId
    override fun getParams(): String? = delegate.params
    override fun getReloadPageKey(): String? = delegate.reloadPageKey
    override fun getNextPageKey(): String? = delegate.nextPageKey
    override fun getChannelUrl(): String? = delegate.channelUrl
    override fun isEmpty(): Boolean = filteredItems.isEmpty()
}
