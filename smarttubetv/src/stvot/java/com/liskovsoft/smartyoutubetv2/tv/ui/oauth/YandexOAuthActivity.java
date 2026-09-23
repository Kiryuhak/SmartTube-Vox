package com.liskovsoft.smartyoutubetv2.tv.ui.oauth;

import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexDeviceAuthDialog;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOnboardingHelper;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOAuthTokenValidator;
import com.yandex.authsdk.YandexAuthLoginOptions;
import com.yandex.authsdk.YandexAuthOptions;
import com.yandex.authsdk.YandexAuthResult;
import com.yandex.authsdk.YandexAuthSdk;
import android.util.Log;

public class YandexOAuthActivity extends AppCompatActivity {
    private ActivityResultLauncher<YandexAuthLoginOptions> mLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (VotOnboardingHelper.isAndroidTv(this)) {
            YandexDeviceAuthDialog.show(this, this::finish);
            return;
        }

        YandexAuthSdk sdk = YandexAuthSdk.create(new YandexAuthOptions(this, true));

        mLauncher = registerForActivityResult(
                sdk.getContract(),
                this::handleResult
        );

        if (savedInstanceState == null) {
            mLauncher.launch(new YandexAuthLoginOptions());
        }
    }

    private void handleResult(YandexAuthResult result) {
        if (result instanceof YandexAuthResult.Success) {
            String token = ((YandexAuthResult.Success) result)
                    .getToken()
                    .getValue();

            if (VotOAuthTokenValidator.isValid(token)) {
                Log.d("SmartTubeVOT-OAuth", "Yandex OAuth SUCCESS (tokenPresent=true)");
                VotData.instance(this).setOAuthToken(token);
            } else {
                Log.w("SmartTubeVOT-OAuth", "Yandex OAuth returned an invalid token");
            }
        } else if (result instanceof YandexAuthResult.Failure) {
            Log.e("SmartTubeVOT-OAuth", "Yandex OAuth FAILURE");
        } else {
            Log.w("SmartTubeVOT-OAuth", "Yandex OAuth CANCELLED");
        }

        finish();
    }
}
