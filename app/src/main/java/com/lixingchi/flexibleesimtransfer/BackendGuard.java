package com.lixingchi.flexibleesimtransfer;

/** System properties are shared by separate module class loaders within the same process. */
final class BackendGuard {
    private static final String OWNER = "com.lixingchi.flexibleesimtransfer.backend";
    static void enter(String backend) {
        var properties = System.getProperties();
        synchronized (properties) {
            String current = properties.getProperty(OWNER);
            if (current == null) properties.setProperty(OWNER, backend);
            else if (!current.equals(backend)) properties.setProperty(OWNER, "conflict");
        }
    }
    static void check() {
        if ("conflict".equals(System.getProperty(OWNER))) throw new Selection.Blocked("BACKEND_CONFLICT");
    }
    private BackendGuard() { }
}
