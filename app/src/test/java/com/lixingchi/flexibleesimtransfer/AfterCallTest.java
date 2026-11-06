package com.lixingchi.flexibleesimtransfer;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AfterCallTest {
    @Test public void returnsIdenticalObjectAndInvokesOriginalOnce() throws Throwable {
        Object value = new Object();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Object> seen = new AtomicReference<>();
        Object result = AfterCall.run(() -> { calls.incrementAndGet(); return value; }, seen::set);
        assertSame(value, result);
        assertSame(value, seen.get());
        assertEquals(1, calls.get());
    }
    @Test public void observerFailureCannotReplaceResultOrRetry() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        Object value = new Object();
        assertSame(value, AfterCall.run(() -> { calls.incrementAndGet(); return value; },
                observed -> { throw new LinkageError("observer failure"); }));
        assertEquals(1, calls.get());
    }
    @Test public void originalExceptionPropagatesWithoutObservation() {
        Exception failure = new Exception("original failure");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        Throwable actual = assertThrows(Exception.class,
                () -> AfterCall.run(() -> { calls.incrementAndGet(); throw failure; },
                        observed -> observations.incrementAndGet()));
        assertSame(failure, actual);
        assertEquals(1, calls.get());
        assertEquals(0, observations.get());
    }
    @Test public void nullResultUnchanged() throws Throwable {
        AtomicReference<Object> seen = new AtomicReference<>(new Object());
        assertNull(AfterCall.run(() -> null, seen::set));
        assertNull(seen.get());
    }
}
