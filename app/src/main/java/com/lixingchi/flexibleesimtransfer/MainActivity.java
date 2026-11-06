package com.lixingchi.flexibleesimtransfer;

import android.app.Activity;
import android.app.AlertDialog;
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
    private EditText port;
    private Button route;
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
        port = new EditText(this);
        port.setHint(R.string.port_hint);
        port.setInputType(InputType.TYPE_CLASS_TEXT);
        port.setSingleLine(true);
        body.addView(port);
        route = new Button(this);
        route.setText(R.string.route);
        body.addView(route);
        route.setOnClickListener(view -> {
            final String selected;
            try { selected = Selection.fingerprint(eid.getText().toString()); }
            catch (IllegalArgumentException invalid) {
                eid.setError(getString(R.string.invalid_eid));
                return;
            }
            String portText = port.getText().toString();
            try { Selection.parsePort(portText); }
            catch (IllegalArgumentException invalid) {
                port.setError(getString(R.string.invalid_port));
                return;
            }
            new AlertDialog.Builder(this).setTitle(R.string.route)
                    .setMessage(R.string.confirm_routing)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.enable, (dialog, which) ->
                            settings.save(selected, portText, () -> eid.setText(""))).show();
        });
        off = new Button(this);
        off.setText(R.string.off);
        body.addView(off);
        off.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(R.string.off)
                .setMessage(R.string.confirm_off).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.off, (dialog, which) ->
                        settings.save("", "", () -> { eid.setText(""); port.setText(""); })).show());
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
        route.setEnabled(settings.ready());
        off.setEnabled(settings.ready());
        if (settings.saving) status.setText(R.string.saving);
        else if (settings.error != 0) status.setText(settings.error);
        else if (settings.confirmed.isEmpty()) status.setText(R.string.status_off);
        else status.setText(getString(R.string.status_route, settings.confirmed.substring(0, 12),
                settings.confirmedPort.isEmpty() ? getString(R.string.single_port) : settings.confirmedPort));
    }
}
