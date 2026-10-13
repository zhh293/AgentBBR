package io.loglens.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CaptureBridgeTest {
    @AfterEach void reset() { CaptureBridge.disable(); CaptureBridge.exit(true); }
    @Test void rejectedCaptureNeverSuppressesOriginalConsole() {
        CaptureBridge.install(callback(false), Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try { assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE")); }
        finally { CaptureBridge.exit(owner); }
    }
    @Test void successfulCaptureSuppressesOnlyNamedCompatibleConsoleAndDisableReverts() {
        CaptureBridge.install(callback(true), Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try {
            assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
            assertFalse(CaptureBridge.skipConsole(new Object(), "OTHER"));
            CaptureBridge.disable();
            assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        } finally { CaptureBridge.exit(owner); }
    }
    @Test void nestedInstrumentationDoesNotRecaptureAndDoesNotRemoveOuterScope() {
        AtomicInteger captures = new AtomicInteger();
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) {
                captures.incrementAndGet();
                boolean nestedOwner = CaptureBridge.enter(logger, event);
                assertFalse(nestedOwner);
                CaptureBridge.exit(nestedOwner);
                return true;
            }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try { assertEquals(1, captures.get()); assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE")); }
        finally { CaptureBridge.exit(owner); }
        assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
    }
    @Test void captureExceptionsAreIsolatedAndNextEventCanCapture() {
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { throw new IllegalStateException("plugin"); }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(null, null);
        assertTrue(owner);
        assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        CaptureBridge.exit(owner);
        CaptureBridge.install(callback(true), Set.of("CONSOLE"));
        owner = CaptureBridge.enter(null, null);
        assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        CaptureBridge.exit(owner);
    }
    private CaptureBridge.Callback callback(boolean accepted) {
        return new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { return accepted; }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        };
    }
}
