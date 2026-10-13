package io.loglens.agent;

import io.loglens.adapter.logback.LogbackCapture;
import io.loglens.api.LogClock;
import io.loglens.bootstrap.CaptureBridge;
import io.loglens.config.ConfigLoader;
import io.loglens.runtime.ProcessingRuntime;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.agent.builder.AgentBuilder;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry invoked reflectively from the minimal premain outer JAR in an isolated loader. */
public final class AgentRuntime implements AutoCloseable {
    public static final class StrictInitializationFailure extends Exception {
        StrictInitializationFailure(Throwable cause) { super("Strict Agent initialization failed", cause); }
    }
    private final ProcessingRuntime runtime;
    private final Instrumentation instrumentation;
    private final ResettableClassFileTransformer transformer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private AgentRuntime(ProcessingRuntime runtime, Instrumentation instrumentation,
                         ResettableClassFileTransformer transformer) {
        this.runtime = runtime; this.instrumentation = instrumentation; this.transformer = transformer;
    }
    public static AgentRuntime start(String configPath, Instrumentation instrumentation) throws Exception {
        var config = ConfigLoader.load(Path.of(configPath));
        LogClock clock = LogClock.system();
        ProcessingRuntime runtime;
        try { runtime = new ProcessingRuntime(config, clock); }
        catch (Exception failure) {
            if (config.agent().failurePolicy() == io.loglens.config.LogLensConfig.FailurePolicy.STRICT) throw new StrictInitializationFailure(failure);
            throw failure;
        }
        ResettableClassFileTransformer transformer = null;
        try {
            transformer = LogbackTransformer.install(instrumentation);
            AgentRuntime session = new AgentRuntime(runtime, instrumentation, transformer);
            CaptureBridge.install(new LogbackCapture(runtime, clock), config.agent().consoleAppenderNames());
            Thread hook = new Thread(() -> {
                try { session.close(); }
                catch (Exception failure) { System.err.println("[LogLens] shutdown failed: " + failure.getClass().getName()); }
            }, "loglens-shutdown");
            hook.setContextClassLoader(AgentRuntime.class.getClassLoader());
            Runtime.getRuntime().addShutdownHook(hook);
            runtime.storage().report("AGENT_ACTIVE", "mode=" + config.agent().mode() + " webPort=" + runtime.webPort());
            return session;
        } catch (Throwable failure) {
            CaptureBridge.disable();
            if (transformer != null) instrumentation.removeTransformer(transformer);
            try { runtime.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (config.agent().failurePolicy() == io.loglens.config.LogLensConfig.FailurePolicy.STRICT) throw new StrictInitializationFailure(failure);
            throw failure;
        }
    }
    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        CaptureBridge.disable();
        instrumentation.removeTransformer(transformer);
        runtime.close();
    }
}
