package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class CategoryEmptyError implements ErrorFragmentData {
    private final Context mContext;
    private final Throwable mError;

    public CategoryEmptyError(Context context, @Nullable Throwable error) {
        mContext = context;
        mError = error;
    }

    @Override
    public void onAction() {
        YTSignInPresenter.instance(mContext).start();
    }

    @Override
    public String getMessage() {
        String result = mContext.getString(R.string.msg_cant_load_content);
        if (mError != null && !Helpers.containsAny(mError.getMessage(), "fromNullable result is null")) {
            if (isDnsError(mError)) {
                return mContext.getString(R.string.msg_dns_error);
            }
            String className = mError.getClass().getSimpleName();
            result = String.format("%s: %s", className, Utils.getStackTraceAsString(mError));
            //result = mError.getMessage();
        }
        return result;
    }

    private static boolean isDnsError(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof java.net.UnknownHostException) return true;
            String msg = curr.getMessage();
            if (msg != null && (msg.contains("Unable to resolve host") || msg.contains("No address associated with hostname"))) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    @Override
    public String getActionText() {
        return mError != null && Helpers.startsWith(mError.getMessage(), "AuthError") ? mContext.getString(R.string.action_signin) : null;
    }
}
