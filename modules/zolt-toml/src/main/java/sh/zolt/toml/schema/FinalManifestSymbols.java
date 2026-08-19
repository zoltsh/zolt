package sh.zolt.toml.schema;

import java.util.List;

/** Closed symbolic values accepted by the final 0.1.0 manifest language. */
public final class FinalManifestSymbols {
    private static final ManifestSymbolRegistry REGISTRY = new ManifestSymbolRegistry(List.of(
            family("package-mode", "jar", "uber-jar", "war", "spring-boot", "spring-boot-war", "quarkus"),
            family("toolchain-distribution", "temurin", "graalvm-community"),
            family("toolchain-policy", "prefer-managed", "require-managed", "allow-system"),
            family("toolchain-feature", "native-image"),
            family("conflict-policy", "resolve", "warn", "fail"),
            family("generated-tool-kind", "openapi", "protobuf", "jvm", "process"),
            family("generated-step-kind", "openapi", "protobuf", "exec", "declared-root"),
            family("generated-lane", "java-sources", "test-sources", "resources", "test-resources", "intermediate"),
            family("generated-cache-policy", "content", "none"),
            family("signing-method", "gpg"),
            family("central-mode", "manual", "automatic"),
            family("compiler-jdk-api-mode", "release", "host"),
            family("test-runtime-event-outcome", "passed", "skipped", "failed")));

    private FinalManifestSymbols() {
    }

    public static ManifestSymbolRegistry registry() {
        return REGISTRY;
    }

    private static ManifestSymbolFamily family(String name, String... values) {
        return new ManifestSymbolFamily(name, List.of(values));
    }
}
