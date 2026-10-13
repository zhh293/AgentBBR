package io.loglens.bootstrap;

import java.util.Set;

/** Bootstrap-visible, JDK-only boundary. Never keep event references beyond enter(). */
public final class CaptureBridge {
    public interface Callback {
        /** Synchronously snapshot and enqueue. Return true only when accepted and takeover healthy. */
        boolean capture(Object logger, Object event);
        boolean healthy();
        boolean compatibleConsole(Object appender);
    }
    private record Scope(boolean takeover, Set<String> names) {}
    private static volatile Callback callback;
    private static volatile Set<String> consoleNames = Set.of();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private CaptureBridge() {}
    public static void install(Callback value, Set<String> names) {
        consoleNames = Set.copyOf(names);
        callback = value;
    }
    public static void disable() { callback = null; }
    /** Returns true when this invocation owns the scope, so advice can clean it in finally. */
    public static boolean enter(Object logger, Object event) {
        if (SCOPE.get() != null) return false;
        SCOPE.set(new Scope(false, Set.of()));
        Callback target = callback;
        if (target != null) {
            try {
                boolean accepted = target.capture(logger, event);
                SCOPE.set(new Scope(accepted, consoleNames));
            } catch (Throwable ignored) { /* Instrumentation must not break business logging. */ }
        }
        return true;
    }
    public static boolean skipConsole(Object appender, String name) {
        Scope scope = SCOPE.get();
        Callback target = callback;
        if (scope == null || !scope.takeover() || target == null || !scope.names().contains(name)) return false;
        try { return target.healthy() && target.compatibleConsole(appender); }
        catch (Throwable ignored) { return false; }
    }
    public static void exit(boolean owner) { if (owner) SCOPE.remove(); }
}
