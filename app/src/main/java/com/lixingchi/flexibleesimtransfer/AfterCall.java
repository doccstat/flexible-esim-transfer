package com.lixingchi.flexibleesimtransfer;

final class AfterCall {
    interface Original { Object run() throws Throwable; }
    interface Inspection { void accept(Object result) throws Throwable; }

    static Object run(Original original, Inspection inspection) throws Throwable {
        Object result = original.run();
        try {
            inspection.accept(result);
        } catch (Throwable ignored) {
            // Observation must not replace an application's result or retry its operation.
        }
        return result;
    }

    private AfterCall() { }
}
