package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

public interface ErrorFragmentData {
    void onAction();
    default void onAction(android.content.Context context) {
        onAction();
    }
    String getMessage();
    String getActionText();
}
