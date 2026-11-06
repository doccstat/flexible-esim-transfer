package com.lixingchi.flexibleesimtransfer;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Insets;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private SettingsStore settings;
    private TextView status;
    private EditText eid;
    private Button observe;
    private Button off;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = (SettingsStore) getApplication();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addView(body);
        text(body, R.string.app_name).setTextSize(24);
        text(body, R.string.intro);
        text(body, R.string.instructions);
        eid = new EditText(this);
        eid.setHint(R.string.eid_hint);
        eid.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        eid.setSingleLine(true);
        eid.setSaveEnabled(false);
        eid.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(eid);
        observe = new Button(this);
        observe.setText(R.string.observe);
        body.addView(observe);
        observe.setOnClickListener(view -> {
            try {
                String selected = Selection.fingerprint(eid.getText().toString());
                settings.save(selected, () -> eid.setText(""));
            } catch (IllegalArgumentException invalid) {
                eid.setError(getString(R.string.invalid_eid));
            }
        });
        off = new Button(this);
        off.setText(R.string.off);
        body.addView(off);
        off.setOnClickListener(view -> settings.save("", () -> eid.setText("")));
        status = text(body, R.string.disconnected);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        text(body, R.string.warning);
        setContentView(scroll);
    }

    private TextView text(LinearLayout body, int resource) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(16);
        view.setPadding(0, 0, 0, 20);
        body.addView(view);
        return view;
    }

    @Override public void onResume() {
        super.onResume();
        settings.changed = this::render;
        render();
    }

    @Override public void onPause() {
        settings.changed = null;
        super.onPause();
    }

    private void render() {
        observe.setEnabled(settings.ready());
        off.setEnabled(settings.ready());
        if (settings.saving) status.setText(R.string.saving);
        else if (settings.error != 0) status.setText(settings.error);
        else if (settings.confirmed.isEmpty()) status.setText(R.string.status_off);
        else status.setText(getString(R.string.status_observe, settings.confirmed.substring(0, 12)));
    }
}
