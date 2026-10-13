package io.loglens.config;

import java.util.List;
import java.util.Map;

/** Explicit allowlist: never serialize the raw configuration or plugin parameters. */
public final class ConfigSummary {
    private ConfigSummary() {}
    public static Map<String, Object> from(LogLensConfig config) {
        return Map.of("schemaVersion", config.schemaVersion(), "application", config.application(), "configHash", config.configHash(),
                "mode", config.agent().mode().name(), "restartRequired", true,
                "captureLevels", config.capture().levels().stream().map(Enum::name).sorted().toList(),
                "ringBufferSize", config.runtime().ringBufferSize(), "overflowPolicy", config.runtime().overflowPolicy().name(),
                "pipeline", config.pipeline().stream().map(n -> Map.of("id", n.id(), "provider", n.provider())).toList(),
                "redactionRules", config.redaction().messageRules().stream().map(LogLensConfig.MessageRule::id).toList());
    }
}
