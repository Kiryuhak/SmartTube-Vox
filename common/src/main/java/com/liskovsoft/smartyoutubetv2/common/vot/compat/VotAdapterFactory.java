package com.liskovsoft.smartyoutubetv2.common.vot.compat;

import android.content.Context;
import androidx.annotation.NonNull;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Factory for resolving the active VOT translation adapter.
 *
 * Guarantees that LegacyVotAdapter (1.11.17) is always selected by default,
 * while allowing controlled opt-in to experimental VOT 1.12.1 in debug environments.
 */
public final class VotAdapterFactory {
    private static final String TAG = VotAdapterFactory.class.getSimpleName();

    private static final AtomicBoolean sVot1121ExperimentEnabled = new AtomicBoolean(false);
    private static volatile VotTranslationAdapter sCustomAdapter = null;

    private VotAdapterFactory() {}

    /**
     * Checks if experimental VOT 1.12.1 adapter is enabled.
     * Default is FALSE.
     */
    public static boolean isVot1121ExperimentEnabled() {
        return sVot1121ExperimentEnabled.get();
    }

    /**
     * Enables or disables the experimental VOT 1.12.1 adapter.
     * Intended solely for developer testing and local regression suites.
     */
    public static void setVot1121ExperimentEnabled(boolean enabled) {
        sVot1121ExperimentEnabled.set(enabled);
        Log.i(TAG, "VotAdapterFactory: experimental VOT 1.12.1 set to %b", enabled);
    }

    /**
     * Allows setting a test double or mock adapter in unit tests.
     */
    public static void setCustomAdapterForTesting(VotTranslationAdapter adapter) {
        sCustomAdapter = adapter;
    }

    /**
     * Resolves the active translation adapter.
     *
     * @param context Android application context
     * @return LegacyVotAdapter by default; Vot1121Adapter only if experimental flag is true.
     */
    @NonNull
    public static VotTranslationAdapter getActiveAdapter(@NonNull Context context) {
        Objects.requireNonNull(context, "context cannot be null");

        if (sCustomAdapter != null) {
            return sCustomAdapter;
        }

        if (sVot1121ExperimentEnabled.get()) {
            Log.w(TAG, "Using EXPERIMENTAL VOT 1.12.1 adapter");
            return new Vot1121Adapter(true);
        }

        return new LegacyVotAdapter(context);
    }
}
