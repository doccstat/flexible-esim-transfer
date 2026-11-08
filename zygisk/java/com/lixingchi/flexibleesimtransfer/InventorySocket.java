package com.lixingchi.flexibleesimtransfer;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone owns the inventory; only an authenticated root peer may retrieve it. No mutation commands. */
final class InventorySocket {
    private static final String NAME = "com.lixingchi.flexibleesimtransfer.inventory.v1";
    private static boolean started;
    static synchronized void start(Supplier<List<CardInventory.Card>> inventory) {
        if (started) return;
        started = true;
        Thread thread = new Thread(() -> {
            try (LocalServerSocket server = new LocalServerSocket(NAME)) {
                android.util.Log.i("FlexibleEsimTransfer", "inventory_bridge: READY");
                while (true) try (LocalSocket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    if (socket.getPeerCredentials().getUid() != 0) continue;
                    if (new DataInputStream(socket.getInputStream()).readInt() != 1) continue;
                    BackendGuard.check();
                    JSONArray cards = new JSONArray();
                    for (var card : inventory.get()) {
                        JSONArray ports = new JSONArray();
                        for (var p : card.ports()) ports.put(new JSONObject().put("index", p.index())
                                .put("logicalSlot", p.logicalSlot()).put("active", p.active()));
                        cards.put(new JSONObject().put("eid", card.eid()).put("fingerprint", card.fingerprint())
                                .put("slot", card.slot()).put("cardId", card.cardId())
                                .put("removable", card.removable()).put("ports", ports));
                    }
                    byte[] bytes = cards.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 65536) continue;
                    var output = new DataOutputStream(socket.getOutputStream());
                    output.writeInt(bytes.length); output.write(bytes); output.flush();
                } catch (Exception unavailable) { /* Reject one request without leaking platform exceptions. */ }
            } catch (Exception unavailable) { android.util.Log.i("FlexibleEsimTransfer", "inventory_bridge: UNAVAILABLE"); }
        }, "FET inventory");
        thread.setDaemon(true); thread.start();
    }
    static JSONArray query(boolean reveal) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(NAME));
            socket.setSoTimeout(5000);
            if (socket.getPeerCredentials().getUid() != 1001) throw new SecurityException();
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(1); output.flush();
            var input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length < 2 || length > 65536) throw new IllegalArgumentException();
            byte[] bytes = new byte[length]; input.readFully(bytes);
            JSONArray cards = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            if (!reveal) for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                String eid = card.getString("eid");
                card.put("eid", eid.length() == 32 ? "…" + eid.substring(26) : "unavailable");
            }
            return cards;
        }
    }
    private InventorySocket() { }
}
