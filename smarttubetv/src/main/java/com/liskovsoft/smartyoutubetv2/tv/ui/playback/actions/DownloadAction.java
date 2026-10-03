package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.leanback.widget.PlaybackControlsRow.MultiAction;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Player HUD Action for VOX Download.
 * INDEX_DOWNLOAD = 0: "Скачать" / idle
 * INDEX_PROGRESS = 1: "Загрузка идёт" / in-progress
 * INDEX_COMPLETED = 2: "Скачано" / completed
 */
public class DownloadAction extends MultiAction {
    public static final int INDEX_DOWNLOAD = 0;
    public static final int INDEX_PROGRESS = 1;
    public static final int INDEX_COMPLETED = 2;

    private final Context mContext;
    private final String[] mLabels;

    public DownloadAction(Context context) {
        super(R.id.action_download);
        mContext = context;

        int highlightColor = ActionHelpers.getIconHighlightColor(context);
        int orangeAccent = 0xFFFFA726; // Material Orange / Amber accent for progress state

        BitmapDrawable idleDrawable = ActionHelpers.getBitmapDrawable(context, R.drawable.action_download);
        BitmapDrawable rawPending = ActionHelpers.getBitmapDrawable(context, R.drawable.action_download_pending);
        BitmapDrawable progressDrawable = rawPending == null ? null
                : ActionHelpers.createDrawable(context, rawPending, orangeAccent);
        BitmapDrawable completedDrawable = idleDrawable == null ? null
                : new BitmapDrawable(context.getResources(),
                ActionHelpers.createBitmap(idleDrawable.getBitmap(), highlightColor));

        Drawable[] drawables = new Drawable[3];
        drawables[INDEX_DOWNLOAD] = idleDrawable;
        drawables[INDEX_PROGRESS] = progressDrawable;
        drawables[INDEX_COMPLETED] = completedDrawable;
        setDrawables(drawables);

        mLabels = new String[3];
        mLabels[INDEX_DOWNLOAD] = context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vox_download_action);
        mLabels[INDEX_PROGRESS] = context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vox_download_stage_video);
        mLabels[INDEX_COMPLETED] = context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vox_download_stage_completed);
        setLabels(mLabels);
        setIndex(INDEX_DOWNLOAD);
    }

    public void updateProgressLabel(String progressText) {
        if (progressText != null && !progressText.isEmpty()) {
            mLabels[INDEX_PROGRESS] = progressText;
        } else {
            mLabels[INDEX_PROGRESS] = mContext.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vox_download_stage_video);
        }
        setLabels(mLabels);
    }
}
