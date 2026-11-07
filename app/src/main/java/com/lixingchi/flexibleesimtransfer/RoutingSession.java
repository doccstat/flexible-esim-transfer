package com.lixingchi.flexibleesimtransfer;

final class RoutingSession {
    private String selection;
    private String port;
    private boolean preserveCardSession;
    private Selection.Target pinned;
    private boolean invalidated;

    synchronized boolean enabled(String selected, String portText, boolean preserveSession) {
        if (invalidated) throw new Selection.Blocked("RESTART_REQUIRED");
        if (selection != null && (!selection.equals(selected) || !port.equals(portText)
                || preserveCardSession != preserveSession)) {
            invalidated = true;
            throw new Selection.Blocked("SETTINGS_CHANGED_RESTART_REQUIRED");
        }
        if ("".equals(selected)) return false;
        if (!Selection.configured(selected)) throw new Selection.Blocked("INVALID_SELECTION");
        selection = selected;
        port = portText;
        preserveCardSession = preserveSession;
        return true;
    }

    synchronized boolean preservesCardSession() { return preserveCardSession; }

    synchronized Selection.Target pin(Selection.Target target) {
        if (invalidated) throw new Selection.Blocked("RESTART_REQUIRED");
        if (pinned != null && !pinned.equals(target)) {
            invalidated = true;
            throw new Selection.Blocked("CARD_MOVED_RESTART_REQUIRED");
        }
        pinned = target;
        return target;
    }

    synchronized void unavailable() {
        // Even reinserting the same card must not resume an old bound download automatically.
        if (pinned != null) invalidated = true;
    }
}
