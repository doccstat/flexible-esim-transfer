package com.lixingchi.flexibleesimtransfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class Selection {
    // Observation settings must never silently enable routing on upgrade.
    static final String PREFS = "routing";
    static final String KEY = "destination_sha256";
    static final String PORT = "port";
    static final String PRESERVE_SESSION = "preserve_card_session";

    record Port(int index, int logicalSlot, boolean active) { }
    record Card(String fingerprint, int cardId, int slot, boolean euicc, List<Port> ports) { }
    record Target(String fingerprint, int cardId, int slot, int port, int logicalSlot) {
        void requireEid(String eid) {
            if (!matches(fingerprint, eid)) throw new Blocked("EID_MISMATCH");
        }
        void requireAddress(int requestedSlot, int requestedPort) {
            if (slot != requestedSlot || port != requestedPort) throw new Blocked("ADDRESS_MISMATCH");
        }
    }

    static final class Blocked extends IllegalStateException {
        Blocked(String reason) { super(reason); }
    }

    static String fingerprint(String eid) {
        if (eid == null || !eid.matches("[0-9]{32}") || eid.equals("0".repeat(32))) {
            throw new IllegalArgumentException("Invalid EID");
        }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(eid.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean configured(String fingerprint) {
        return fingerprint != null && fingerprint.matches("[a-f0-9]{64}");
    }

    static boolean matches(String selected, String eid) {
        try { return configured(selected) && selected.equals(fingerprint(eid)); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static int parsePort(String value) {
        if ("".equals(value)) return -1;
        if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid port");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid port"); }
    }

    static Target resolve(String selected, String portText, List<Card> cards) {
        if (!configured(selected)) throw new Blocked("INVALID_SELECTION");
        final int requestedPort;
        try { requestedPort = parsePort(portText); }
        catch (IllegalArgumentException invalid) { throw new Blocked("INVALID_PORT"); }
        if (cards == null) throw new Blocked("INVENTORY_UNAVAILABLE");
        Card match = null;
        for (Card card : cards) {
            if (card == null || !selected.equals(card.fingerprint())) continue;
            if (match != null) throw new Blocked("AMBIGUOUS_CARD");
            match = card;
        }
        if (match == null) throw new Blocked("CARD_NOT_FOUND");
        if (!match.euicc()) throw new Blocked("NOT_EUICC");
        if (match.cardId() < 0 || match.slot() < 0) throw new Blocked("INVALID_ADDRESS");
        Port port = null;
        if (match.ports() != null) for (Port candidate : match.ports()) {
            if (candidate == null || !candidate.active()) continue;
            if (requestedPort != -1 && candidate.index() != requestedPort) continue;
            if (port != null) throw new Blocked("SELECT_PORT");
            port = candidate;
        }
        if (port == null) throw new Blocked("PORT_INACTIVE");
        if (port.index() < 0 || port.logicalSlot() < 0) throw new Blocked("INVALID_PORT_ADDRESS");
        return new Target(selected, match.cardId(), match.slot(), port.index(), port.logicalSlot());
    }

    private Selection() { }
}
