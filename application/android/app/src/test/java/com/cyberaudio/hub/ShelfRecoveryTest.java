package com.cyberaudio.hub;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfRecoveryTest {
    @Test public void networkFailureRecoversInsteadOfPermanentlyStayingOffline() {
        ShelfRecovery state = new ShelfRecovery(false);
        assertFalse(state.shouldRetry(true, false));
        state.failed(0);
        assertTrue(state.shouldRetry(true, false));
        assertEquals(1500, state.delayMillis());
        state.connected();
        assertFalse(state.shouldRetry(true, false));
        state.failed(0);
        assertEquals(1500, state.delayMillis());
    }
    @Test public void retryDoesNotRunInBackgroundOrWhileLoading() {
        ShelfRecovery state = new ShelfRecovery(false); state.failed(0);
        assertFalse(state.shouldRetry(false, false));
        assertFalse(state.shouldRetry(true, true));
        assertTrue(state.shouldRetry(true, false));
    }
    @Test public void explicitDownloadedSectionStaysOffline() {
        ShelfRecovery state = new ShelfRecovery(true); state.failed(0);
        assertFalse(state.shouldRetry(true, false));
    }
    @Test public void authenticationAndPathErrorsDoNotPollOrBecomeNetworkErrors() {
        for (int status : new int[]{400, 401, 403, 404}) {
            ShelfRecovery state = new ShelfRecovery(false); state.failed(status);
            assertFalse(state.shouldRetry(true, false));
        }
    }
    @Test public void temporaryErrorsBackOffToThirtySeconds() {
        ShelfRecovery state = new ShelfRecovery(false);
        for (int i = 0; i < 20; i++) state.failed(503);
        assertTrue(state.shouldRetry(true, false));
        assertEquals(30000, state.delayMillis());
    }
}
