package com.liskovsoft.smartyoutubetv2.tv.ui.showcase;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Отладочная Activity для визуальной валидации компонентов UI (Sidebar States и Card 3-Badge Scheme).
 * Включается ИСКЛЮЧИТЕЛЬНО в отладочный (debug) вариант сборки.
 */
public class VoxUiShowcaseActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vox_ui_showcase);

        View selectedItem = findViewById(R.id.sidebar_state_selected);
        if (selectedItem != null) {
            selectedItem.setSelected(true);
        }

        View focusedItem = findViewById(R.id.sidebar_state_focused);
        if (focusedItem != null) {
            focusedItem.requestFocus();
        }
    }
}
