package com.cyberaudio.hub;

/** Foreground-only retry policy; has no access to account or download storage. */
final class ShelfRecovery {
    private final boolean savedOnly;
    private boolean failed, retryable;
    private int failures;

    ShelfRecovery(boolean savedOnly) { this.savedOnly = savedOnly; }
    void failed(int httpStatus) {
        failed = true;
        retryable = httpStatus == 0 || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
        failures = Math.min(failures + 1, 6);
    }
    void connected() { failed = false; retryable = false; failures = 0; }
    boolean shouldRetry(boolean resumed, boolean loading) {
        return !savedOnly && failed && retryable && resumed && !loading;
    }
    long delayMillis() { return Math.min(30000L, 1500L << Math.max(0, failures - 1)); }
}
