package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxFeedQualityResolver;
import com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxQualityBindingGuard;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview.ComplexImageCardView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/*
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an Image CardView
 */
public class VideoCardPresenter extends LongClickPresenter {
    private static final String TAG = VideoCardPresenter.class.getSimpleName();
    private int mDefaultBackgroundColor = -1;
    private int mDefaultTextColor = -1;
    private int mSelectedBackgroundColor = -1;
    private int mSelectedTextColor = -1;
    private int mCardPreviewType;
    private int mThumbQuality;
    private int mWidth;
    private int mHeight;
    private final Handler mQualityHandler = new Handler(Looper.getMainLooper());
    private final java.util.IdentityHashMap<Presenter.ViewHolder, QualityBinding> mQualityBindings = new java.util.IdentityHashMap<>();

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        mDefaultBackgroundColor =
            ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardDefaultBackground));
        mDefaultTextColor =
                ContextCompat.getColor(context, R.color.card_default_text);
        mSelectedBackgroundColor =
                ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardSelectedBackground));
        mSelectedTextColor =
                ContextCompat.getColor(context, R.color.card_selected_text_grey);

        mCardPreviewType = getCardPreviewType(context);
        mThumbQuality = getThumbQuality(context);

        boolean isCardMultilineTitleEnabled = isCardMultilineTitleEnabled(context);
        boolean isCardMultilineSubtitleEnabled = isCardMultilineSubtitleEnabled(context);
        boolean isCardTextAutoScrollEnabled = isCardTextAutoScrollEnabled(context);
        float cardTextScrollSpeed = getCardTextScrollSpeed(context);

        updateDimensions(context);

        ComplexImageCardView cardView = new ComplexImageCardView(context) {
            @Override
            public void setSelected(boolean selected) {
                updateCardBackgroundColor(this, selected);
                super.setSelected(selected);
            }
        };

        cardView.setTitleLinesNum(isCardMultilineTitleEnabled ? 2 : 1);
        cardView.setContentLinesNum(isCardMultilineSubtitleEnabled ? 2 : 1);
        cardView.enableTextAutoScroll(isCardTextAutoScrollEnabled);
        cardView.setTextScrollSpeed(cardTextScrollSpeed);
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        cardView.enableBadge(isBadgeEnabled());
        cardView.enableTitle(isTitleEnabled());
        cardView.enableContent(isContentEnabled());
        cardView.setBackgroundColor(mDefaultBackgroundColor); // background is temporarily visible during animations
        //if (VERSION.SDK_INT >= 23 && MainUIData.instance(context).isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS)) {
        //    cardView.setForeground(ContextCompat.getDrawable(context, R.drawable.lb_card_outline));
        //}
        updateCardBackgroundColor(cardView, false);
        return new ViewHolder(cardView);
    }

    private void updateCardBackgroundColor(ComplexImageCardView view, boolean selected) {
        int backgroundColor = selected ? mSelectedBackgroundColor : mDefaultBackgroundColor;
        int textColor = selected ? mSelectedTextColor : mDefaultTextColor;

        // Both background colors should be set because the view's
        // background is temporarily visible during animations.
        // NOTE: has visual bug with rounded corners
        //view.setBackgroundColor(backgroundColor);

        View infoField = view.findViewById(R.id.info_field);
        if (infoField != null) {
            infoField.setBackgroundColor(backgroundColor);
        }

        TextView titleText = view.findViewById(R.id.title_text);
        if (titleText != null) {
            titleText.setTextColor(textColor);
        }
        TextView contentText = view.findViewById(R.id.content_text);
        if (contentText != null) {
            contentText.setTextColor(textColor);
        }
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        clearQualityBinding(viewHolder);

        Video video = (Video) item;

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;
        Context context = cardView.getContext();

        cardView.setTitleText(video.getTitle());
        cardView.setContentText(video.getSecondTitle());
        // Count progress that very close to zero. E.g. when user closed video immediately.
        cardView.setProgress(video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
        String qualityBadge = com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxBadgeHelper.getQualityBadge(video);
        VoxFeedQualityResolver resolver = VoxFeedQualityResolver.get(context);
        if (qualityBadge != null && video.videoId != null) {
            resolver.rememberBadge(video.videoId, qualityBadge,
                    video.isLocal ? VoxFeedQualityResolver.Source.DOWNLOAD : VoxFeedQualityResolver.Source.FEED);
        }
        if (qualityBadge == null && !video.isLocal && video.videoId != null) {
            qualityBadge = resolver.cached(video.videoId);
        }
        cardView.setQualityBadge(qualityBadge);
        if (qualityBadge != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.debug(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PLAYER,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.QUALITY_BADGE_BOUND,
                    "Quality badge bound: " + qualityBadge
            );
        } else if (canResolveQuality(video)) {
            QualityBinding binding = new QualityBinding(cardView, video.videoId, resolver);
            cardView.addOnAttachStateChangeListener(binding);
            mQualityBindings.put(viewHolder, binding);
            binding.schedule();
        }

        String ageBadge = com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxBadgeHelper.getAgeBadge(video);
        cardView.setAgeBadge(ageBadge);
        if (ageBadge != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.debug(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PLAYER,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AGE_BADGE_BOUND,
                    "Age badge bound: " + ageBadge
            );
        }

        boolean isLocal = video.isLocal;
        boolean isTranslated = video.isDownloadedTranslated();
        cardView.setLocalMarker(isLocal, isTranslated);

        String badgeText = video.hasNewContent ? context.getString(R.string.badge_new_content) :
                video.isLive ? context.getString(R.string.badge_live) :
                video.isShorts ? context.getString(R.string.header_shorts).toUpperCase() :
                video.badge;

        if (qualityBadge != null && badgeText != null && com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxBadgeHelper.isQualityText(badgeText)) {
            badgeText = null;
        }
        if (ageBadge != null && badgeText != null && com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxBadgeHelper.isAgeText(badgeText)) {
            badgeText = null;
        }

        String durationBadge = com.liskovsoft.smartyoutubetv2.common.vox.badge.VoxBadgeHelper.getDurationBadge(video);
        cardView.setBadgeText(durationBadge != null && !video.hasNewContent ? durationBadge : badgeText);
        cardView.setBadgeColor(video.hasNewContent || video.isLive || video.isUpcoming ?
                ContextCompat.getColor(context, R.color.dark_red) : ContextCompat.getColor(context, R.color.black));

        if (mCardPreviewType != MainUIData.CARD_PREVIEW_DISABLED) {
            cardView.setPreview(video);
            cardView.setMute(mCardPreviewType == MainUIData.CARD_PREVIEW_MUTED);
        }

        cardView.setMainImageDimensions(mWidth, mHeight);

        if (context instanceof Activity && ((Activity) context).isDestroyed()) {
            // Glide.with(context): IllegalArgumentException: You cannot start a load for a destroyed activity
            return;
        }

        Glide.with(context)
                //.asBitmap() // disable animation (webp, gif)
                .load(ClickbaitRemover.updateThumbnail(video, mThumbQuality))
                .placeholder(R.drawable.card_placeholder)
                .apply(ViewUtil.glideOptions())
                // improve image compression on low end devices
                .override(mWidth, mHeight)
                // com.liskovsoft.smartyoutubetv2.tv.util.CacheGlideModule
                // Cache makes app crashing on old android versions
                .diskCacheStrategy(VERSION.SDK_INT > 21 ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .error(
                    // Updated thumbnail url not found, fallback to original cardImageUrl
                    Glide.with(context)
                        .load(video.cardImageUrl) // always working
                        .placeholder(R.drawable.card_placeholder)
                        .apply(ViewUtil.glideOptions())
                        .listener(mErrorListener)
                        .error(R.drawable.card_placeholder) // R.color.lb_grey
                )
                .into(cardView.getMainImageView());
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        super.onUnbindViewHolder(viewHolder);
        clearQualityBinding(viewHolder);

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;

        // Remove references to images so that the garbage collector can free up memory.
        cardView.setBadgeImage(null);
        cardView.setQualityBadge(null);
        cardView.setAgeBadge(null);
        cardView.setLocalMarker(false, false);
        cardView.setBadgeText(null);
        cardView.setMainImage(null);

        // Cleanup Glide resources. https://chatgpt.com/share/682120c5-e428-8010-b848-371b2dec0cd5
        Glide.with(cardView.getContext().getApplicationContext()).clear(cardView.getMainImageView());
    }

    private static boolean canResolveQuality(Video video) {
        return video.videoId != null && !video.isLocal && !video.isChannel() && !video.isMix() &&
                !video.isLive && !video.isUpcoming && !video.isShorts;
    }

    private void clearQualityBinding(Presenter.ViewHolder holder) {
        QualityBinding previous = mQualityBindings.remove(holder);
        if (previous != null) previous.close();
    }

    private final class QualityBinding implements View.OnAttachStateChangeListener {
        private final ComplexImageCardView view;
        private final String videoId;
        private final VoxFeedQualityResolver resolver;
        private final VoxQualityBindingGuard guard = new VoxQualityBindingGuard();
        private final long generation;
        private final Runnable dwell = this::resolveIfVisible;
        private VoxFeedQualityResolver.Subscription subscription;
        private boolean active = true;

        QualityBinding(ComplexImageCardView view, String videoId, VoxFeedQualityResolver resolver) {
            this.view = view;
            this.videoId = videoId;
            this.resolver = resolver;
            this.generation = guard.bind(videoId);
        }

        void schedule() {
            mQualityHandler.removeCallbacks(dwell);
            if (active) mQualityHandler.postDelayed(dwell, VoxFeedQualityResolver.VISIBLE_DWELL_MS);
        }

        private void resolveIfVisible() {
            Rect visible = new Rect();
            if (!active || view.getWindowToken() == null || !view.isShown() ||
                    !view.getGlobalVisibleRect(visible) ||
                    visible.width() * visible.height() < view.getWidth() * view.getHeight() / 2) return;
            subscription = resolver.resolve(videoId, (resolvedId, badge) -> {
                if (active && guard.accepts(resolvedId, generation) && view.getWindowToken() != null && view.isShown())
                    view.setQualityBadge(badge);
            });
        }

        void close() {
            active = false;
            guard.unbind();
            mQualityHandler.removeCallbacks(dwell);
            if (subscription != null) subscription.cancel();
            view.removeOnAttachStateChangeListener(this);
        }

        @Override public void onViewAttachedToWindow(View v) { schedule(); }
        @Override public void onViewDetachedFromWindow(View v) {
            mQualityHandler.removeCallbacks(dwell);
            if (subscription != null) subscription.cancel();
            subscription = null;
        }
    }

    private void updateDimensions(Context context) {
        Pair<Integer, Integer> dimens = getCardDimensPx(context);

        mWidth = dimens.first;
        mHeight = dimens.second;
    }
    
    protected Pair<Integer, Integer> getCardDimensPx(Context context) {
        return GridFragmentHelper.getCardDimensPx(context, R.dimen.card_width, R.dimen.card_height, MainUIData.instance(context).getVideoGridScale());
    }

    protected boolean isCardTextAutoScrollEnabled(Context context) {
        return MainUIData.instance(context).isCardTextAutoScrollEnabled();
    }

    protected int getCardPreviewType(Context context) {
        return MainUIData.instance(context).getCardPreviewType();
    }

    protected boolean isCardMultilineTitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineTitleEnabled();
    }

    protected boolean isCardMultilineSubtitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineSubtitleEnabled();
    }

    protected float getCardTextScrollSpeed(Context context) {
        return MainUIData.instance(context).getCardTextScrollSpeed();
    }

    protected int getThumbQuality(Context context) {
        return MainUIData.instance(context).getThumbQuality();
    }

    protected boolean isContentEnabled() {
        return true;
    }

    protected boolean isTitleEnabled() {
        return true;
    }

    protected boolean isBadgeEnabled() {
        return true;
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            com.liskovsoft.smartyoutubetv2.common.vox.image.VoxImageRetryPolicy.logFailure(e, 1, "card_thumbnail");
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };

    private final RequestListener<Bitmap> mErrorListener2 = new RequestListener<Bitmap>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            com.liskovsoft.smartyoutubetv2.common.vox.image.VoxImageRetryPolicy.logFailure(e, 1, "card_bitmap");
            return false;
        }

        @Override
        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
