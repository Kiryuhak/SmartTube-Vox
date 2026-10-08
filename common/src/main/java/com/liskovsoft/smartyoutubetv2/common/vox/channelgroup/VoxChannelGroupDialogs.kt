package com.liskovsoft.smartyoutubetv2.common.vox.channelgroup

import android.content.Context
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog

/**
 * TV-диалоги управления пользовательскими группами каналов.
 */
object VoxChannelGroupDialogs {

    /**
     * Диалог добавления/удаления канала из групп (вызывается из контекстного меню карточки канала).
     */
    @JvmStatic
    fun showAddToGroupDialog(context: Context, video: Video?, onUpdated: Runnable? = null) {
        if (video == null) return
        val channelId = video.channelIdOrName ?: return
        val dialogPresenter = AppDialogPresenter.instance(context)
        val manager = VoxChannelGroupManager.instance(context)

        dialogPresenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_group_create),
                {
                    showCreateGroupDialog(context, initialVideo = video) { newGroup ->
                        onUpdated?.run()
                        // Переоткрываем диалог со списком групп
                        showAddToGroupDialog(context, video, onUpdated)
                    }
                }
            )
        )

        val groups = manager.getGroups()
        if (groups.isNotEmpty()) {
            val options = mutableListOf<OptionItem>()
            for (group in groups) {
                val isChecked = manager.isChannelInGroup(group.id, channelId)
                val count = manager.getChannelCountInGroup(group.id)
                val desc = context.getString(R.string.vox_channel_group_channels_count, count)
                options.add(
                    UiOptionItem.from(
                        group.name,
                        desc,
                        { optionItem ->
                            manager.toggleChannelInGroup(
                                group.id,
                                channelId,
                                video.title,
                                video.cardImageUrl
                            )
                            onUpdated?.run()
                        },
                        isChecked
                    )
                )
            }
            dialogPresenter.appendCheckedCategory(
                context.getString(R.string.vox_channel_groups),
                options
            )
        }

        dialogPresenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_group_manage),
                {
                    showManageGroupsDialog(context) {
                        onUpdated?.run()
                        showAddToGroupDialog(context, video, onUpdated)
                    }
                }
            )
        )

        val title = video.title ?: context.getString(R.string.vox_channel_groups)
        dialogPresenter.showDialog(title)
    }

    /**
     * Диалог выбора активного фильтра групп (вызывается из меню раздела «Каналы»).
     */
    @JvmStatic
    fun showGroupSelectorDialog(context: Context, onSelected: Runnable? = null) {
        val dialogPresenter = AppDialogPresenter.instance(context)
        val manager = VoxChannelGroupManager.instance(context)
        val currentSelectedId = manager.selectedGroupId

        val radioItems = mutableListOf<OptionItem>()

        // 1. «Все каналы»
        radioItems.add(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_groups_all),
                {
                    manager.setSelectedGroupId(null)
                    dialogPresenter.closeDialog()
                    onSelected?.run()
                },
                currentSelectedId == null
            )
        )

        // 2. Список групп
        val groups = manager.getGroups()
        for (group in groups) {
            val count = manager.getChannelCountInGroup(group.id)
            val desc = context.getString(R.string.vox_channel_group_channels_count, count)
            radioItems.add(
                UiOptionItem.from(
                    group.name,
                    desc,
                    {
                        manager.setSelectedGroupId(group.id)
                        dialogPresenter.closeDialog()
                        onSelected?.run()
                    },
                    currentSelectedId == group.id
                )
            )
        }

        dialogPresenter.appendRadioCategory(
            context.getString(R.string.vox_channel_groups),
            radioItems
        )

        // 3. Создать группу
        dialogPresenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_group_create),
                {
                    showCreateGroupDialog(context, null) { newGroup ->
                        manager.setSelectedGroupId(newGroup.id)
                        dialogPresenter.closeDialog()
                        onSelected?.run()
                    }
                }
            )
        )

        // 4. Управление группами
        if (groups.isNotEmpty()) {
            dialogPresenter.appendSingleButton(
                UiOptionItem.from(
                    context.getString(R.string.vox_channel_group_manage),
                    {
                        showManageGroupsDialog(context) {
                            onSelected?.run()
                            showGroupSelectorDialog(context, onSelected)
                        }
                    }
                )
            )
        }

        dialogPresenter.showDialog(context.getString(R.string.vox_channel_groups))
    }

    /**
     * Диалог создания новой группы каналов.
     */
    @JvmStatic
    fun showCreateGroupDialog(
        context: Context,
        initialVideo: Video? = null,
        onCreated: ((VoxChannelGroup) -> Unit)? = null
    ) {
        val manager = VoxChannelGroupManager.instance(context)

        SimpleEditDialog.show(
            context,
            context.getString(R.string.vox_channel_group_create_title),
            context.getString(R.string.new_subscriptions_group),
            ""
        ) { newValue ->
            val clean = VoxChannelGroup.sanitizeName(newValue)
            if (clean.isEmpty()) {
                MessageHelpers.showMessage(context, R.string.vox_channel_group_name_empty)
                return@show false
            }
            if (clean.length > VoxChannelGroup.MAX_NAME_LENGTH) {
                MessageHelpers.showMessage(context, R.string.vox_channel_group_name_too_long)
                return@show false
            }
            try {
                val group = manager.createGroup(clean)
                if (initialVideo != null) {
                    val chId = initialVideo.channelIdOrName
                    if (!chId.isNullOrBlank()) {
                        manager.addChannelToGroup(
                            group.id,
                            chId,
                            initialVideo.title,
                            initialVideo.cardImageUrl
                        )
                    }
                }
                MessageHelpers.showMessage(context, R.string.msg_done)
                onCreated?.invoke(group)
                true
            } catch (e: VoxChannelGroupException) {
                if (e.errorCode == VoxChannelGroupErrorCode.DUPLICATE_NAME) {
                    MessageHelpers.showMessage(context, R.string.vox_channel_group_name_exists)
                } else {
                    MessageHelpers.showMessage(context, e.message)
                }
                false
            } catch (e: Exception) {
                MessageHelpers.showMessage(context, e.message)
                false
            }
        }
    }

    /**
     * Диалог переименования группы каналов.
     */
    @JvmStatic
    fun showRenameGroupDialog(
        context: Context,
        group: VoxChannelGroup,
        onRenamed: Runnable? = null
    ) {
        val manager = VoxChannelGroupManager.instance(context)

        SimpleEditDialog.show(
            context,
            context.getString(R.string.vox_channel_group_rename_title),
            context.getString(R.string.vox_channel_group_rename_title),
            group.name
        ) { newValue ->
            val clean = VoxChannelGroup.sanitizeName(newValue)
            if (clean.isEmpty()) {
                MessageHelpers.showMessage(context, R.string.vox_channel_group_name_empty)
                return@show false
            }
            if (clean.length > VoxChannelGroup.MAX_NAME_LENGTH) {
                MessageHelpers.showMessage(context, R.string.vox_channel_group_name_too_long)
                return@show false
            }
            try {
                manager.renameGroup(group.id, clean)
                MessageHelpers.showMessage(context, R.string.msg_done)
                onRenamed?.run()
                true
            } catch (e: VoxChannelGroupException) {
                if (e.errorCode == VoxChannelGroupErrorCode.DUPLICATE_NAME) {
                    MessageHelpers.showMessage(context, R.string.vox_channel_group_name_exists)
                } else {
                    MessageHelpers.showMessage(context, e.message)
                }
                false
            } catch (e: Exception) {
                MessageHelpers.showMessage(context, e.message)
                false
            }
        }
    }

    /**
     * Подтверждение удаления группы каналов.
     * ВАЖНО: Подписки YouTube НЕ затрагиваются!
     */
    @JvmStatic
    fun showDeleteGroupDialog(
        context: Context,
        group: VoxChannelGroup,
        onDeleted: Runnable? = null
    ) {
        val manager = VoxChannelGroupManager.instance(context)
        val title = String.format(
            context.getString(R.string.vox_channel_group_delete_title),
            group.name
        )

        AppDialogUtil.showConfirmationDialog(
            context,
            title,
            {
                manager.deleteGroup(group.id)
                MessageHelpers.showMessage(context, R.string.msg_done)
                onDeleted?.run()
            }
        )
    }

    /**
     * Диалог управления существующими группами (переименование, удаление).
     */
    @JvmStatic
    fun showManageGroupsDialog(context: Context, onUpdated: Runnable? = null) {
        val dialogPresenter = AppDialogPresenter.instance(context)
        val manager = VoxChannelGroupManager.instance(context)
        val groups = manager.getGroups()

        if (groups.isEmpty()) {
            MessageHelpers.showMessage(context, R.string.vox_channel_group_empty)
            return
        }

        for (group in groups) {
            val count = manager.getChannelCountInGroup(group.id)
            val desc = context.getString(R.string.vox_channel_group_channels_count, count)
            dialogPresenter.appendSingleButton(
                UiOptionItem.from(
                    group.name,
                    desc,
                    {
                        showGroupOptionsDialog(context, group, onUpdated)
                    }
                )
            )
        }

        dialogPresenter.showDialog(context.getString(R.string.vox_channel_group_manage))
    }

    private fun showGroupOptionsDialog(
        context: Context,
        group: VoxChannelGroup,
        onUpdated: Runnable? = null
    ) {
        val dialogPresenter = AppDialogPresenter.instance(context)

        dialogPresenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_group_rename_title),
                {
                    showRenameGroupDialog(context, group) {
                        onUpdated?.run()
                    }
                }
            )
        )

        dialogPresenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_channel_group_delete_btn),
                {
                    showDeleteGroupDialog(context, group) {
                        onUpdated?.run()
                    }
                }
            )
        )

        dialogPresenter.showDialog(group.name)
    }
}
