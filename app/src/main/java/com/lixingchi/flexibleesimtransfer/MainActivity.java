package com.lixingchi.flexibleesimtransfer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Insets;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class MainActivity extends Activity {
    private SettingsStore settings;
    private TextView status, scanStatus, manualHints;
    private EditText eid, port;
    private Button route, off, refresh, preserveSession;
    private CheckBox showEids;
    private RadioGroup destinations;
    private LinearLayout manualFields;
    private List<CardInventory.Card> cards;
    private CardInventory.Card selectedCard;
    private Selection.Port selectedPort;
    private boolean manual, scanning;
    private InventoryBridge.Query query;
    private AlertDialog confirmation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = (SettingsStore) getApplication();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        body.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addView(body);
        text(body, R.string.app_name).setTextSize(24);
        text(body, R.string.esim_risk);
        text(body, R.string.instructions);
        refresh = new Button(this);
        refresh.setText(R.string.refresh_cards);
        body.addView(refresh);
        refresh.setOnClickListener(view -> scan());
        scanStatus = text(body, R.string.scanning);
        scanStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        showEids = new CheckBox(this);
        showEids.setText(R.string.show_eids);
        body.addView(showEids);
        showEids.setOnCheckedChangeListener((button, checked) -> rebuildDestinations());
        destinations = new RadioGroup(this);
        body.addView(destinations);
        text(body, R.string.port_explanation);
        manualFields = new LinearLayout(this);
        manualFields.setOrientation(LinearLayout.VERTICAL);
        body.addView(manualFields);
        eid = new EditText(this);
        eid.setHint(R.string.eid_hint);
        eid.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        eid.setSingleLine(true);
        eid.setSaveEnabled(false);
        manualFields.addView(eid);
        port = new EditText(this);
        port.setHint(R.string.port_hint);
        port.setInputType(InputType.TYPE_CLASS_TEXT);
        port.setSingleLine(true);
        port.setSaveEnabled(false);
        manualFields.addView(port);
        manualHints = text(manualFields, R.string.manual_unknown);
        eid.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) { updateManualHints(); }
            public void afterTextChanged(Editable value) { }
        });
        route = new Button(this);
        route.setText(R.string.route);
        body.addView(route);
        route.setOnClickListener(view -> confirmRoute());
        preserveSession = new Button(this);
        body.addView(preserveSession);
        preserveSession.setOnClickListener(view -> {
            boolean enable = !settings.confirmedPreserveSession;
            confirmation = new AlertDialog.Builder(this).setTitle(R.string.preserve_session)
                    .setMessage(enable ? R.string.confirm_session_on : R.string.confirm_session_off)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(enable ? R.string.enable_session : R.string.disable_session,
                            (dialog, which) -> settings.save(settings.confirmed, settings.confirmedPort, enable, () -> {}))
                    .show();
        });
        off = new Button(this);
        off.setText(R.string.off);
        body.addView(off);
        off.setOnClickListener(view -> {
            confirmation = new AlertDialog.Builder(this).setTitle(R.string.off)
                    .setMessage(R.string.confirm_off).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.off, (dialog, which) -> settings.save("", "", false, () -> {
                        eid.setText(""); port.setText(""); manual = false;
                        selectedCard = null; selectedPort = null; rebuildDestinations();
                    })).show();
        });
        status = text(body, R.string.disconnected);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        text(body, R.string.warning);
        Button diagnostics = new Button(this);
        diagnostics.setText(R.string.diagnostics);
        diagnostics.setOnClickListener(view -> startActivity(new android.content.Intent(this, DiagnosticsActivity.class)));
        body.addView(diagnostics);
        setContentView(scroll);
        rebuildDestinations();
    }

    private void scan() {
        if (query != null) query.cancel();
        // A new scan never carries a possibly removed card/port forward as an implicit choice.
        cards = null;
        selectedCard = null;
        selectedPort = null;
        scanning = true;
        scanStatus.setText(R.string.scanning);
        rebuildDestinations();
        query = new InventoryBridge.Query(settings.main);
        query.start(this, result -> {
            scanning = false;
            cards = result.cards();
            if (result.status() == InventoryBridge.OK)
                scanStatus.setText(cards.isEmpty() ? getString(R.string.no_cards)
                        : getString(R.string.cards_found, cards.size()));
            else scanStatus.setText(switch (result.status()) {
                case InventoryBridge.FAILED -> R.string.scan_failed;
                case InventoryBridge.INVALID -> R.string.scan_invalid;
                case InventoryBridge.TIMEOUT -> R.string.scan_timeout;
                default -> R.string.scan_unavailable;
            });
            rebuildDestinations();
        });
    }

    private void rebuildDestinations() {
        if (destinations == null) return;
        destinations.removeAllViews();
        if (cards != null) for (CardInventory.Card card : cards) {
            TextView title = new TextView(this);
            title.setText(getString(R.string.card_details, cardLabel(card), eidLabel(card, showEids.isChecked())));
            title.setTextSize(18);
            title.setPadding(0, 16, 0, 4);
            title.setAccessibilityHeading(true);
            destinations.addView(title);
            if (card.ports().isEmpty()) text(destinations, R.string.no_ports);
            for (Selection.Port p : card.ports()) {
                RadioButton option = new RadioButton(this);
                option.setId(View.generateViewId());
                String label = portLabel(p);
                try { CardInventory.choose(cards, card, p); }
                catch (IllegalArgumentException | Selection.Blocked unavailable) {
                    option.setEnabled(false);
                    if (p.active()) label += "\n" + getString(selectionError(unavailable));
                }
                option.setText(label);
                option.setContentDescription(cardLabel(card) + ", " + eidLabel(card, false) + ", " + label);
                destinations.addView(option);
                option.setChecked(!manual && card.equals(selectedCard) && p.equals(selectedPort));
                option.setOnCheckedChangeListener((button, checked) -> {
                    if (!checked) return;
                    manual = false; selectedCard = card; selectedPort = p; render();
                });
            }
        }
        RadioButton custom = new RadioButton(this);
        custom.setId(View.generateViewId());
        custom.setText(R.string.manual_entry);
        destinations.addView(custom);
        custom.setChecked(manual);
        custom.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) return;
            manual = true; selectedCard = null; selectedPort = null; render();
        });
        updateManualHints();
        render();
    }

    private String cardLabel(CardInventory.Card card) {
        return getString(R.string.card_label, getString(card.removable() ? R.string.removable : R.string.built_in),
                card.slot() < 0 ? getString(R.string.unknown) : Integer.toString(card.slot()));
    }

    private String eidLabel(CardInventory.Card card, boolean reveal) {
        if (card.fingerprint().isEmpty()) return getString(R.string.eid_unavailable);
        return getString(reveal ? R.string.full_eid : R.string.masked_eid,
                reveal ? card.eid() : card.eid().substring(card.eid().length() - 6));
    }

    private String portLabel(Selection.Port p) {
        return getString(R.string.port_label, p.index(), getString(p.active() ? R.string.active : R.string.inactive),
                p.logicalSlot() < 0 ? getString(R.string.not_mapped) : Integer.toString(p.logicalSlot()));
    }

    private void updateManualHints() {
        if (manualHints == null) return;
        String entered = eid.getText().toString();
        StringBuilder hints = new StringBuilder();
        if (cards != null) for (CardInventory.Card card : cards) {
            if (!entered.equals(card.eid()) || card.fingerprint().isEmpty()) continue;
            hints.append(cardLabel(card)).append('\n');
            for (Selection.Port p : card.ports()) hints.append(portLabel(p)).append('\n');
            if (card.ports().isEmpty()) hints.append(getString(R.string.no_ports)).append('\n');
        }
        manualHints.setText(hints.length() == 0 ? getString(R.string.manual_unknown)
                : hints + getString(R.string.manual_known));
    }

    private void confirmRoute() {
        if (scanning || !settings.ready()) return;
        final String fingerprint, portText, destination;
        if (manual) {
            String entered = eid.getText().toString();
            try { fingerprint = Selection.fingerprint(entered); }
            catch (IllegalArgumentException invalid) { eid.setError(getString(R.string.invalid_eid)); return; }
            portText = port.getText().toString();
            try { Selection.parsePort(portText); }
            catch (IllegalArgumentException invalid) { port.setError(getString(R.string.invalid_port)); return; }
            final boolean verified;
            try { verified = CardInventory.verifyManual(cards, entered, portText); }
            catch (Selection.Blocked invalid) { port.setError(getString(selectionError(invalid))); return; }
            destination = getString(R.string.manual_confirmation, entered.substring(entered.length() - 6),
                    portText.isEmpty() ? getString(R.string.single_port) : portText)
                    + (verified ? "" : "\n" + getString(R.string.manual_unverified));
        } else {
            if (selectedCard == null || selectedPort == null) return;
            try { CardInventory.choose(cards, selectedCard, selectedPort); }
            catch (IllegalArgumentException | Selection.Blocked invalid) {
                scanStatus.setText(selectionError(invalid)); return;
            }
            fingerprint = selectedCard.fingerprint();
            portText = Integer.toString(selectedPort.index());
            destination = cardLabel(selectedCard) + "\n" + eidLabel(selectedCard, false) + "\n" + portLabel(selectedPort);
        }
        confirmation = new AlertDialog.Builder(this).setTitle(R.string.route)
                .setMessage(destination + "\n\n" + getString(R.string.confirm_routing))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.enable, (dialog, which) ->
                        settings.save(fingerprint, portText, settings.confirmedPreserveSession, () -> eid.setText(""))).show();
    }

    private int selectionError(Exception error) {
        if (!(error instanceof Selection.Blocked)) return R.string.eid_unavailable;
        return switch (error.getMessage()) {
            case "SELECT_PORT" -> R.string.choose_port;
            case "PORT_INACTIVE", "INVALID_PORT_ADDRESS" -> R.string.port_unavailable;
            case "AMBIGUOUS_CARD" -> R.string.ambiguous_card;
            default -> R.string.card_unavailable;
        };
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
        scan();
    }

    @Override public void onPause() {
        settings.changed = null;
        if (query != null) query.cancel();
        if (confirmation != null) confirmation.dismiss();
        cards = null; selectedCard = null; selectedPort = null;
        showEids.setChecked(false);
        rebuildDestinations();
        super.onPause();
    }

    private void render() {
        if (route == null) return;
        manualFields.setVisibility(manual ? View.VISIBLE : View.GONE);
        route.setEnabled(settings.ready() && !scanning && (manual || selectedCard != null));
        off.setEnabled(settings.ready());
        preserveSession.setEnabled(settings.ready());
        preserveSession.setText(settings.ready()
                ? (settings.confirmedPreserveSession ? R.string.session_on : R.string.session_off)
                : R.string.preserve_session);
        refresh.setEnabled(!scanning);
        if (settings.saving) status.setText(R.string.saving);
        else if (settings.error != 0) status.setText(settings.error);
        else if (settings.confirmed.isEmpty()) status.setText(R.string.status_off);
        else status.setText(getString(R.string.status_route, settings.confirmed.substring(0, 12),
                settings.confirmedPort.isEmpty() ? getString(R.string.single_port) : settings.confirmedPort));
    }
}
