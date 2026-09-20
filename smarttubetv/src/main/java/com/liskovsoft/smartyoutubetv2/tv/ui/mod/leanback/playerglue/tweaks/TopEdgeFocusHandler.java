package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks;

final class TopEdgeFocusHandler {
    private TopEdgeFocusHandler() {
    }

    static void onFocusGained(boolean isVox, Runnable clearFocus, Runnable notifyFocused) {
        // VOX hides the overlay from notifyFocused(). Clearing first makes its compact
        // transport row immediately focus top_edge again and recurse until stack overflow.
        if (!isVox) {
            clearFocus.run();
        }

        notifyFocused.run();
    }
}
