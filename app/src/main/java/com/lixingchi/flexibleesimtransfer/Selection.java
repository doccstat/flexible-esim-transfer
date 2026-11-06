package com.lixingchi.flexibleesimtransfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class Selection {
    static final String PREFS = "preflight";
    static final String KEY = "destination_sha256";

    record Card(String fingerprint, int cardId, int slot, boolean euicc,
                boolean removable, boolean active) { }

    record Result(String status, Card card) {
        String compareCard(int observed) {
            return summary() + ";cardMatch=" + (card != null && card.cardId() == observed);
        }
        String compareSlot(int observed) {
            return summary() + ";slotMatch=" + (card != null && card.slot() == observed);
        }
        private String summary() {
            return "selection=" + status;
        }
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

    static String compareEid(String selected, String observed) {
        if (!configured(selected)) return "selection=OFF";
        try {
            return "eidMatch=" + selected.equals(fingerprint(observed));
        } catch (IllegalArgumentException unavailable) {
            return "eidMatch=UNAVAILABLE";
        }
    }

    static Result resolve(String selected, List<Card> cards) {
        if (!configured(selected)) return new Result("OFF", null);
        if (cards == null) return new Result("UNAVAILABLE", null);
        Card match = null;
        for (Card card : cards) {
            if (card == null || !selected.equals(card.fingerprint())) continue;
            if (match != null) return new Result("AMBIGUOUS", null);
            match = card;
        }
        if (match == null) return new Result("NOT_FOUND", null);
        if (!match.euicc() || !match.removable()) return new Result("NOT_REMOVABLE_EUICC", null);
        if (match.cardId() < 0 || match.slot() < 0) return new Result("INVALID_ADDRESS", null);
        if (!match.active()) return new Result("INACTIVE", null);
        return new Result("READY", match);
    }

    private Selection() { }
}
