package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowHeaderPresenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class IconHeaderItemPresenter extends RowHeaderPresenter {
    private static final String TAG = IconHeaderItemPresenter.class.getSimpleName();
    private float mUnselectedAlpha;
    private final int mResId;
    private final String mIconUrl;
    private Drawable mDefaultIcon;

    public IconHeaderItemPresenter(int resId, String iconUrl) {
        mResId = resId;
        mIconUrl = iconUrl;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup) {
        mUnselectedAlpha = viewGroup.getResources()
                .getFraction(R.fraction.lb_browse_header_unselect_alpha, 1, 1);
        LayoutInflater inflater = (LayoutInflater) viewGroup.getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mDefaultIcon = new ColorDrawable(ContextCompat.getColor(viewGroup.getContext(), R.color.lb_grey));

        View view = inflater.inflate(R.layout.icon_header_item, null);
        view.setAlpha(mUnselectedAlpha); // Initialize icons to be at half-opacity.

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        HeaderItem headerItem;

        if (item instanceof PageRow) {
            headerItem = ((PageRow) item).getHeaderItem();
        } else {
            headerItem = ((ListRow) item).getHeaderItem();
        }

        View rootView = viewHolder.view;
        rootView.setFocusable(true);
        rootView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.setActivated(hasFocus);
            if (hasFocus) {
                v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start();
                v.setAlpha(1.0f);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                v.setAlpha(mUnselectedAlpha);
            }
            ImageView iconView = v.findViewById(R.id.header_icon);
            if (iconView != null) {
                iconView.setAlpha(hasFocus ? 1.0f : 0.7f);
            }
            View lbl = v.findViewById(R.id.header_label);
            if (lbl instanceof com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.HeaderMarqueeTextViewCompat) {
                ((com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.HeaderMarqueeTextViewCompat) lbl).setFocusedState(hasFocus);
            } else if (lbl != null) {
                lbl.setSelected(hasFocus);
            }
            if (hasFocus && headerItem != null) {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.info(
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.APP,
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.SIDEBAR_FOCUS_CHANGED,
                        "Sidebar header focused: " + headerItem.getName()
                );
            }
        });

        ImageView iconView = rootView.findViewById(R.id.header_icon);
        if (iconView != null) {
            if (mIconUrl != null) {
                Glide.with(rootView.getContext())
                        .load(mIconUrl)
                        .apply(ViewUtil.glideOptions().error(mDefaultIcon))
                        .listener(mErrorListener)
                        .into(iconView);

                //ViewUtil.makeMonochrome(iconView);
            } else {
                Drawable icon = mResId > 0 ? ContextCompat.getDrawable(rootView.getContext(), mResId) : mDefaultIcon;
                iconView.setImageDrawable(icon);
            }
        }

        TextView label = rootView.findViewById(R.id.header_label);
        if (label != null) {
            label.setText(headerItem != null ? headerItem.getName() : null);
        }
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        // NOP
    }

    // TODO: This is a temporary fix. Remove me when leanback onCreateViewHolder no longer sets the
    // mUnselectAlpha, and also assumes the xml inflation will return a RowHeaderView.
    @Override
    protected void onSelectLevelChanged(RowHeaderPresenter.ViewHolder holder) {
        if (holder.view.hasFocus()) {
            holder.view.setAlpha(1.0f);
        } else {
            holder.view.setAlpha(mUnselectedAlpha + holder.getSelectLevel() *
                    (1.0f - mUnselectedAlpha));
        }
        View lbl = holder.view.findViewById(R.id.header_label);
        if (lbl instanceof com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.HeaderMarqueeTextViewCompat) {
            ((com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.HeaderMarqueeTextViewCompat) lbl).setFocusedState(holder.view.hasFocus());
        } else if (lbl != null) {
            lbl.setSelected(holder.view.hasFocus());
        }
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
