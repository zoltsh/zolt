package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FinalManifestSymbolsTest {
    @Test
    void freezesEveryFinalSymbolFamily() {
        List<ManifestSymbolFamily> families = FinalManifestSchema.registry().symbols().families();
        assertEquals(List.of(
                "package-mode",
                "toolchain-distribution",
                "toolchain-policy",
                "toolchain-feature",
                "conflict-policy",
                "generated-tool-kind",
                "generated-step-kind",
                "generated-lane",
                "generated-cache-policy",
                "signing-method",
                "central-mode",
                "compiler-jdk-api-mode",
                "test-runtime-event-outcome"), families.stream().map(ManifestSymbolFamily::name).toList());

        Map<String, List<String>> actual = new LinkedHashMap<>();
        families.forEach(family -> actual.put(family.name(), family.values()));

        assertEquals(Map.ofEntries(
                Map.entry(
                        "package-mode",
                        List.of("jar", "uber-jar", "war", "spring-boot", "spring-boot-war", "quarkus")),
                Map.entry("toolchain-distribution", List.of("temurin", "graalvm-community")),
                Map.entry("toolchain-policy", List.of("prefer-managed", "require-managed", "allow-system")),
                Map.entry("toolchain-feature", List.of("native-image")),
                Map.entry("conflict-policy", List.of("resolve", "warn", "fail")),
                Map.entry("generated-tool-kind", List.of("openapi", "protobuf", "jvm", "process")),
                Map.entry("generated-step-kind", List.of("openapi", "protobuf", "exec", "declared-root")),
                Map.entry(
                        "generated-lane",
                        List.of("java-sources", "test-sources", "resources", "test-resources", "intermediate")),
                Map.entry("generated-cache-policy", List.of("content", "none")),
                Map.entry("signing-method", List.of("gpg")),
                Map.entry("central-mode", List.of("manual", "automatic")),
                Map.entry("compiler-jdk-api-mode", List.of("release", "host")),
                Map.entry("test-runtime-event-outcome", List.of("passed", "skipped", "failed"))), actual);
    }
}
